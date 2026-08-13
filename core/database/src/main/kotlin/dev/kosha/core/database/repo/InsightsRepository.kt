package dev.kosha.core.database.repo

import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import dev.kosha.core.common.Periods
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.GoalsDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.GoalKind
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.debt.DebtPlanner
import dev.kosha.core.engine.insight.Advisor
import dev.kosha.core.engine.insight.AnomalyEngine
import dev.kosha.core.engine.insight.HealthScore
import dev.kosha.core.engine.insight.LeakDetector
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Feeds the pure-Kotlin insight engines from the ledger (spec Phase 6/7/9).
 * All the arithmetic lives in `:core:engine`; this only assembles inputs.
 */
@Singleton
class InsightsRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val planningDao: PlanningDao,
    private val goalsDao: GoalsDao,
    private val periodRepository: PeriodRepository,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    data class Insights(
        val period: Period,
        val income: Money,
        val expense: Money,
        val savings: Money,
        val spendByCategoryName: List<Pair<String, Money>>,
        val dailySpend: Map<LocalDate, Money>,
        val dnaCurrent: List<Pair<String, Money>>,
        val dnaBaseline: List<Pair<String, Money>>,
        val trend: List<TrendPoint>,
        val health: HealthScore.Result,
        val leaks: List<LeakDetector.Leak>,
        val anomalies: List<AnomalyEngine.Flag>,
        val advice: Advisor.Advice,
        val debtComparison: DebtPlanner.Comparison?,
    )

    data class TrendPoint(
        val period: Period,
        val income: Money,
        val expense: Money,
        val savingsGap: Money,
    )

    suspend fun load(anchorDay: Int, emergencyFundMonths: Int): Insights {
        val period = periodRepository.currentPeriod(anchorDay)
        val snapshot = periodRepository.snapshot(period)
        val categories = categoryDao.observeAll().first().associateBy { it.id }
        val excluded = setOfNotNull(
            categoryDao.bySystemKey(SystemCategoryKey.TRANSFERS)?.id,
            categoryDao.bySystemKey(SystemCategoryKey.CASH_WITHDRAWAL)?.id,
        )

        val spendByName = snapshot.spendByCategory
            .mapNotNull { (categoryId, amount) ->
                val name = categoryId?.let { categories[it]?.name } ?: "Uncategorized"
                name to amount
            }
            .sortedByDescending { it.second.paise }

        // Daily spend for the heatmap.
        val periodTxns = transactionDao.inWindow(
            period.startEpochMillis(zone),
            period.endEpochMillisExclusive(zone),
        ).filter { it.status == TxnStatus.COMMITTED && it.type == TxnType.DEBIT }
        val dailySpend = periodTxns
            .groupBy { Periods.localDateOf(it.timestampMillis, zone) }
            .mapValues { (_, txns) -> Money(txns.sumOf { it.amountPaise }) }

        // 12-month trajectory + the DNA baseline.
        val trend = buildList {
            var p = period
            repeat(TREND_PERIODS) {
                val snap = periodRepository.snapshot(p)
                add(
                    TrendPoint(
                        period = p,
                        income = snap.totals.actualIncome,
                        expense = snap.totals.totalExpense,
                        savingsGap = snap.totals.savingsGap,
                    ),
                )
                p = Periods.previousMonthlyPeriod(p, anchorDay)
            }
        }.reversed()

        val baseline = baselineSpend(period, anchorDay, categories, excluded)

        // Leaks over the trailing 90 days.
        val leakWindowStart = LocalDate.now(zone).minusDays(LeakDetector.WINDOW_DAYS.toLong())
        val leakTxns = transactionDao.inWindow(
            leakWindowStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            System.currentTimeMillis(),
        ).filter {
            it.status == TxnStatus.COMMITTED &&
                it.type == TxnType.DEBIT &&
                it.categoryId !in excluded &&
                !it.merchantNormalized.isNullOrBlank()
        }
        val leaks = LeakDetector.detect(
            leakTxns.map {
                LeakDetector.Spend(it.merchantNormalized!!, Money(it.amountPaise), it.timestampMillis)
            },
        )

        // Anomalies over this period's transactions, against 6 months of history.
        val historyStart = LocalDate.now(zone).minusDays(AnomalyEngine.HISTORY_WINDOW_DAYS)
        val historyTxns = transactionDao.inWindow(
            historyStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            System.currentTimeMillis(),
        ).filter { it.status == TxnStatus.COMMITTED && it.type == TxnType.DEBIT }

        val anomalies = periodTxns.mapNotNull { txn ->
            val priorMerchant = historyTxns.filter {
                it.id != txn.id && it.merchantNormalized != null &&
                    it.merchantNormalized == txn.merchantNormalized
            }
            val priorCategory = historyTxns.filter {
                it.id != txn.id && it.categoryId != null && it.categoryId == txn.categoryId
            }
            AnomalyEngine.evaluate(
                candidate = AnomalyEngine.Candidate(
                    transactionId = txn.id,
                    amount = Money(txn.amountPaise),
                    merchantNormalized = txn.merchantNormalized,
                    categoryId = txn.categoryId,
                    timestampMillis = txn.timestampMillis,
                ),
                merchantHistory = priorMerchant
                    .takeIf { it.isNotEmpty() }
                    ?.let { list -> AnomalyEngine.History(list.map { Money(it.amountPaise) }) },
                categoryHistory = priorCategory
                    .takeIf { it.isNotEmpty() }
                    ?.let { list -> AnomalyEngine.History(list.map { Money(it.amountPaise) }) },
            )
        }.let(AnomalyEngine::cap)

        // Health score — components only where they are measurable (G4).
        val closedPeriods = planningDao.observePeriodSummaries().first()
        val avgExpense = if (trend.isEmpty()) {
            snapshot.totals.totalExpense
        } else {
            Money(trend.sumOf { it.expense.paise } / trend.size)
        }
        val goals = goalsDao.observeGoals().first()
        val emergencyFund = goals.firstOrNull { it.kind == GoalKind.EMERGENCY_FUND }
        val debts = goalsDao.observeDebts().first()
        val budgets = planningDao.budgetsOnce()
        val overBudget = budgets.count { budget ->
            val spent = if (budget.categoryId == null) {
                snapshot.spendByCategory.values.sumOf { it.paise }
            } else {
                snapshot.spendByCategory[budget.categoryId]?.paise ?: 0
            }
            spent > budget.limitPaise
        }

        val health = HealthScore.compute(
            HealthScore.Input(
                savingsGap = snapshot.totals.savingsGap,
                actualIncome = snapshot.totals.actualIncome,
                // null until a fund exists — the score then EXCLUDES the
                // component rather than scoring it as zero or perfect (G4).
                emergencyFundBalance = emergencyFund?.let { Money(it.allocatedPaise) },
                averageMonthlyExpense = avgExpense,
                emergencyFundMonthsTarget = emergencyFundMonths,
                categoriesWithBudgets = budgets.size,
                categoriesOverBudget = overBudget,
                monthlyEmiOutflow = debts.takeIf { it.isNotEmpty() }
                    ?.let { list -> Money(list.sumOf { it.emiAmountPaise }) },
                closedPeriods = closedPeriods.size,
            ),
        )

        val averageSurplus = if (closedPeriods.isEmpty()) {
            snapshot.totals.savingsGap
        } else {
            Money(closedPeriods.take(4).sumOf { it.savingsGapPaise } / closedPeriods.take(4).size)
        }

        val advice = Advisor.advise(
            Advisor.Input(
                averageSurplus = averageSurplus,
                emergencyFundBalance = Money(emergencyFund?.allocatedPaise ?: 0),
                averageMonthlyExpense = avgExpense,
                emergencyFundMonthsTarget = emergencyFundMonths,
                highInterestDebtOutstanding = Money(
                    debts.filter { it.rateBps >= HIGH_INTEREST_BPS }.sumOf { it.principalPaise },
                ),
                goalShortfalls = goals
                    .filter { it.kind != GoalKind.EMERGENCY_FUND }
                    .sortedBy { it.priority }
                    .map { it.name to Money((it.targetAmountPaise - it.allocatedPaise).coerceAtLeast(0)) },
            ),
        )

        val debtComparison = debts.takeIf { it.isNotEmpty() }?.let { list ->
            DebtPlanner.compare(
                list.map {
                    DebtPlanner.Debt(
                        id = it.id,
                        name = it.name,
                        principal = Money(it.principalPaise),
                        rateBps = it.rateBps,
                        minimumPayment = Money(it.emiAmountPaise),
                    )
                },
            )
        }

        return Insights(
            period = period,
            income = snapshot.totals.actualIncome,
            expense = snapshot.totals.totalExpense,
            savings = snapshot.totals.savingsGap,
            spendByCategoryName = spendByName,
            dailySpend = dailySpend,
            dnaCurrent = spendByName.take(DNA_AXES),
            dnaBaseline = baseline,
            trend = trend,
            health = health,
            leaks = leaks,
            anomalies = anomalies,
            advice = advice,
            debtComparison = debtComparison,
        )
    }

    /** 3-month average spend per category — the DNA radar's baseline. */
    private suspend fun baselineSpend(
        current: Period,
        anchorDay: Int,
        categories: Map<Long, dev.kosha.core.database.model.CategoryEntity>,
        excluded: Set<Long>,
    ): List<Pair<String, Money>> {
        val totals = mutableMapOf<String, Long>()
        var period = Periods.previousMonthlyPeriod(current, anchorDay)
        repeat(BASELINE_PERIODS) {
            val snapshot = periodRepository.snapshot(period)
            snapshot.spendByCategory.forEach { (categoryId, amount) ->
                if (categoryId in excluded) return@forEach
                val name = categoryId?.let { categories[it]?.name } ?: "Uncategorized"
                totals.merge(name, amount.paise, Long::plus)
            }
            period = Periods.previousMonthlyPeriod(period, anchorDay)
        }
        return totals
            .map { (name, total) -> name to Money(total / BASELINE_PERIODS) }
            .sortedByDescending { it.second.paise }
    }

    private companion object {
        const val TREND_PERIODS = 12
        const val BASELINE_PERIODS = 3
        const val DNA_AXES = 6
        /** ≥ 12% a year counts as high-interest for advisory ordering. */
        const val HIGH_INTEREST_BPS = 1200
    }
}
