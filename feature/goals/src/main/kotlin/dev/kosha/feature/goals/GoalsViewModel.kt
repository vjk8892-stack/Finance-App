package dev.kosha.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.common.Periods
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.GoalsDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.AssetLiabilityEntity
import dev.kosha.core.database.model.AssetLiabilityKind
import dev.kosha.core.database.model.DebtAccountEntity
import dev.kosha.core.database.model.FinancialGoalEntity
import dev.kosha.core.database.model.GoalKind
import dev.kosha.core.database.model.TaxTag
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.debt.DebtPlanner
import dev.kosha.core.engine.debt.NetWorthCalculator
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GoalsUiState(
    val goals: List<FinancialGoalEntity> = emptyList(),
    val debts: List<DebtAccountEntity> = emptyList(),
    val assetsLiabilities: List<AssetLiabilityEntity> = emptyList(),
    val netWorth: NetWorthCalculator.NetWorth? = null,
    val debtComparison: DebtPlanner.Comparison? = null,
    val averageMonthlyExpense: Money = Money.ZERO,
    val emergencyFundMonths: Int = 3,
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

        val comparison = debts.takeIf { it.isNotEmpty() }?.let { list ->
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

        // Tax report groups by India FY, 1 April – 31 March (spec G1).
        val fy = Periods.financialYearContaining(LocalDate.now(zone))
        val taxTotals = transactionDao
            .inWindow(fy.startEpochMillis(zone), fy.endEpochMillisExclusive(zone))
            .filter { it.status == TxnStatus.COMMITTED && it.taxTag != null }
            .groupBy { it.taxTag!! }
            .map { (tag, txns) -> tag to Money(txns.sumOf { it.amountPaise }) }
            .sortedByDescending { it.second.paise }

        val avgExpense = periodRepository
            .snapshot(periodRepository.currentPeriod(settings.periodAnchorDay))
            .totals.totalExpense

        GoalsUiState(
            goals = goals,
            debts = debts,
            assetsLiabilities = items,
            netWorth = netWorth,
            debtComparison = comparison,
            averageMonthlyExpense = avgExpense,
            emergencyFundMonths = settings.emergencyFundMonths,
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

    fun addDebt(
        name: String,
        principalRupees: String,
        ratePercent: String,
        emiRupees: String,
        tenureMonths: String,
    ) {
        val principal = Money.parseOrNull(principalRupees) ?: return
        val emi = Money.parseOrNull(emiRupees) ?: return
        val rateBps = ((ratePercent.toDoubleOrNull() ?: 0.0) * 100).toInt()
        viewModelScope.launch {
            goalsDao.insertDebt(
                DebtAccountEntity(
                    name = name,
                    principalPaise = principal.paise,
                    rateBps = rateBps,
                    emiAmountPaise = emi.paise,
                    tenureMonths = tenureMonths.toIntOrNull() ?: 0,
                    startDateMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteDebt(debt: DebtAccountEntity) {
        viewModelScope.launch { goalsDao.deleteDebt(debt) }
    }

    fun addAssetLiability(name: String, valueRupees: String, isLiability: Boolean) {
        val value = Money.parseOrNull(valueRupees) ?: return
        viewModelScope.launch {
            goalsDao.insertAssetLiability(
                AssetLiabilityEntity(
                    name = name,
                    kind = if (isLiability) AssetLiabilityKind.LIABILITY else AssetLiabilityKind.ASSET,
                    valuePaise = value.paise,
                    valuationDateMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteAssetLiability(item: AssetLiabilityEntity) {
        viewModelScope.launch { goalsDao.deleteAssetLiability(item) }
    }
}
