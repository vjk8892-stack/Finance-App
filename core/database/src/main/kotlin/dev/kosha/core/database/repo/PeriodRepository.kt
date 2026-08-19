package dev.kosha.core.database.repo

import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import dev.kosha.core.common.Periods
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.IncomeFrequency
import dev.kosha.core.database.model.PeriodSummaryEntity
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.period.PeriodMath
import java.time.LocalDate
import java.time.ZoneId
import dev.kosha.core.database.settings.TrackingWindow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Period engine (spec Phase 3): assembles the ledger into the pure-Kotlin
 * [PeriodMath] inputs, and writes the immutable [PeriodSummaryEntity] at
 * close (manual close or auto-close on rollover).
 */
@Singleton
class PeriodRepository @Inject constructor(
    private val trackingWindow: TrackingWindow,
    private val transactionDao: TransactionDao,
    private val planningDao: PlanningDao,
    private val categoryDao: CategoryDao,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    data class PeriodSnapshot(
        val period: Period,
        val totals: PeriodMath.Totals,
        val expectedIncome: Money,
        val spendByCategory: Map<Long?, Money>,
        val tone: PeriodMath.WeatherTone,
    )

    fun currentPeriod(anchorDay: Int, today: LocalDate = LocalDate.now(zone)): Period =
        Periods.monthlyPeriodContaining(today, anchorDay)

    suspend fun expectedIncome(): Money {
        val sources = planningDao.incomeSourcesOnce()
        val total = sources.filter { it.frequency != IncomeFrequency.ONE_TIME }
            .sumOf { it.amountPaise }
        return Money(total)
    }

    /**
     * Typical spending per period, over the last [periods] COMPLETED ones.
     *
     * The current period is excluded deliberately. Callers want this to size
     * an emergency fund — "six months of expenses" — and on the 2nd of the
     * month the period-to-date total is nearly zero, which would report a
     * target of almost nothing and a fund that is already complete. A partial
     * month is not a month's spending.
     *
     * Falls back to the current period only when there is no completed history
     * at all, which is the one case where a partial figure beats no figure.
     */
    suspend fun averageExpense(anchorDay: Int, periods: Int = 6): Money {
        var p = Periods.previousMonthlyPeriod(currentPeriod(anchorDay), anchorDay)
        val totals = mutableListOf<Long>()
        repeat(periods) {
            totals += snapshot(p).totals.totalExpense.paise
            p = Periods.previousMonthlyPeriod(p, anchorDay)
        }
        // Periods before any data exist and total zero; averaging them in
        // would halve the figure for someone three months into using the app.
        val real = totals.filter { it > 0 }
        if (real.isEmpty()) return snapshot(currentPeriod(anchorDay)).totals.totalExpense
        return Money(real.sum() / real.size)
    }

    suspend fun snapshot(period: Period): PeriodSnapshot {
        // A period that begins before the tracking boundary is clamped to it,
        // so a month you only started tracking halfway through reports what
        // you tracked rather than silently including what you asked to ignore.
        val from = trackingWindow.clampFrom(period.startEpochMillis(zone))
        val to = period.endEpochMillisExclusive(zone)
        val rows = transactionDao.inWindow(from, to).filter { it.status == TxnStatus.COMMITTED }
        val transferLegIds = transactionDao.transferLegIds().toSet()
        val excludedCategoryIds = setOfNotNull(
            categoryDao.bySystemKey(SystemCategoryKey.TRANSFERS)?.id,
            categoryDao.bySystemKey(SystemCategoryKey.CASH_WITHDRAWAL)?.id,
        )

        val txns = rows.map { r ->
            PeriodMath.Txn(
                id = r.id,
                amountPaise = r.amountPaise,
                direction = when (r.type) {
                    TxnType.CREDIT -> PeriodMath.Direction.CREDIT
                    TxnType.DEBIT -> PeriodMath.Direction.DEBIT
                },
                categoryId = r.categoryId,
                parentId = r.parentTransactionId,
                isTransferLeg = r.id in transferLegIds,
                isAnalyticsExcludedCategory = r.categoryId in excludedCategoryIds,
            )
        }
        val expected = expectedIncome()
        val totals = PeriodMath.totals(txns, expected)
        return PeriodSnapshot(
            period = period,
            totals = totals,
            expectedIncome = expected,
            spendByCategory = PeriodMath.spendByCategory(txns),
            tone = PeriodMath.weatherTone(totals, expected),
        )
    }

    /**
     * Closes [period] into an immutable summary. Idempotent: a period that is
     * already closed is returned as-is, never rewritten (spec B5).
     */
    suspend fun close(period: Period): PeriodSummaryEntity {
        val startMillis = period.startEpochMillis(zone)
        planningDao.summaryForPeriodStart(startMillis)?.let { return it }

        val snap = snapshot(period)
        val summary = PeriodSummaryEntity(
            periodStartMillis = startMillis,
            periodEndMillis = period.endEpochMillisExclusive(zone),
            expectedIncomePaise = snap.expectedIncome.paise,
            actualIncomePaise = snap.totals.actualIncome.paise,
            totalExpensePaise = snap.totals.totalExpense.paise,
            savingsGapPaise = snap.totals.savingsGap.paise,
            untrackedGapPaise = snap.totals.untrackedGap.paise,
            closedAtMillis = System.currentTimeMillis(),
        )
        val id = planningDao.insertPeriodSummary(summary)
        return summary.copy(id = id)
    }

    /**
     * Auto-close on rollover: closes every completed period that has ledger
     * activity and no summary yet, oldest first. Called on app start.
     */
    suspend fun closeElapsedPeriods(anchorDay: Int, today: LocalDate = LocalDate.now(zone)) {
        val oldest = transactionDao.oldestTimestamp() ?: return
        val oldestDate = Periods.localDateOf(oldest, zone)
        var period = Periods.monthlyPeriodContaining(oldestDate, anchorDay)
        val current = currentPeriod(anchorDay, today)
        while (period.start < current.start) {
            close(period)
            period = Periods.nextMonthlyPeriod(period, anchorDay)
        }
    }
}
