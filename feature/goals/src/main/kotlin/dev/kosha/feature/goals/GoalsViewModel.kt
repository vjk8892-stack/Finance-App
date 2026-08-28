package dev.kosha.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.common.Periods
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.GoalsDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.AssetLiabilityKind
import dev.kosha.core.database.model.FinancialGoalEntity
import dev.kosha.core.database.model.GoalKind
import dev.kosha.core.database.model.TaxTag
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.debt.NetWorthCalculator
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Debt and Net Worth moved to their own screens/ViewModels (design review:
 * a debt avalanche/snowball simulator and a net-worth tracker are each
 * substantial enough to be their own destination). What's left here is
 * sinking-fund goals and the tax report, plus a one-line summary of the two
 * that moved out — enough to decide whether to open them, not a duplicate
 * of what's on those screens.
 */
data class DebtSummary(val count: Int, val totalOwed: Money)

data class GoalsUiState(
    val goals: List<FinancialGoalEntity> = emptyList(),
    val averageMonthlyExpense: Money = Money.ZERO,
    val emergencyFundMonths: Int = 3,
    val debtSummary: DebtSummary = DebtSummary(0, Money.ZERO),
    val netWorth: NetWorthCalculator.NetWorth? = null,
    val taxTotals: List<Pair<TaxTag, Money>> = emptyList(),
    val financialYearLabel: String = "",
)

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalsDao: GoalsDao,
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val periodRepository: PeriodRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    val uiState: StateFlow<GoalsUiState> = combine(
        goalsDao.observeGoals(),
        goalsDao.observeDebts(),
        goalsDao.observeAssetsLiabilities(),
        transactionDao.observeTransactionCount(),
        settingsRepository.settings,
    ) { goals, debts, items, _, settings ->
        val accounts = accountDao.activeAccounts()
        val accountTotal = Money(accounts.sumOf { it.currentBalancePaise })

        val netWorth = NetWorthCalculator.compute(
            manualAssets = items.filter { it.kind == AssetLiabilityKind.ASSET }
                .map { NetWorthCalculator.Item(it.name, Money(it.valuePaise), false) },
            manualLiabilities = items.filter { it.kind == AssetLiabilityKind.LIABILITY }
                .map { NetWorthCalculator.Item(it.name, Money(it.valuePaise), true) },
            // Tracked debts are the authoritative source — counted here and
            // never re-entered as a manual liability (spec B5).
            trackedDebtBalances = debts.map {
                NetWorthCalculator.Item(it.name, Money(it.principalPaise), true)
            },
            accountBalances = accountTotal,
        )

        // Tax report groups by India FY, 1 April – 31 March (spec G1).
        val fy = Periods.financialYearContaining(LocalDate.now(zone))
        val taxTotals = transactionDao
            .inWindow(fy.startEpochMillis(zone), fy.endEpochMillisExclusive(zone))
            .filter { it.status == TxnStatus.COMMITTED && it.taxTag != null }
            .groupBy { it.taxTag!! }
            .map { (tag, txns) -> tag to Money(txns.sumOf { it.amountPaise }) }
            .sortedByDescending { it.second.paise }

        // Was the CURRENT period's expense, which is not an average and not a
        // month. The emergency-fund card multiplies this by the months target,
        // so on the 2nd of the month it reported a target near zero and a fund
        // that was already complete — the one number on this screen that is
        // supposed to tell you whether you are safe.
        val avgExpense = periodRepository.averageExpense(settings.periodAnchorDay)

        GoalsUiState(
            goals = goals,
            averageMonthlyExpense = avgExpense,
            emergencyFundMonths = settings.emergencyFundMonths,
            debtSummary = DebtSummary(debts.size, Money(debts.sumOf { it.principalPaise })),
            netWorth = netWorth,
            taxTotals = taxTotals,
            financialYearLabel = "${fy.start.year}–${fy.endInclusive.year % 100}",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    fun addGoal(name: String, targetRupees: String, allocatedRupees: String, isEmergency: Boolean) {
        val target = Money.parseOrNull(targetRupees) ?: return
        viewModelScope.launch {
            goalsDao.insertGoal(
                FinancialGoalEntity(
                    name = name,
                    targetAmountPaise = target.paise,
                    allocatedPaise = Money.parseOrNull(allocatedRupees)?.paise ?: 0,
                    kind = if (isEmergency) GoalKind.EMERGENCY_FUND else GoalKind.SINKING_FUND,
                    // Emergency fund is pinned first (spec C7).
                    priority = if (isEmergency) 0 else 1,
                ),
            )
        }
    }

    fun deleteGoal(goal: FinancialGoalEntity) {
        viewModelScope.launch { goalsDao.deleteGoal(goal) }
    }
}
