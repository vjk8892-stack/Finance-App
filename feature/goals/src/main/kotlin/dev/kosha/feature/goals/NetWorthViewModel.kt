package dev.kosha.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.GoalsDao
import dev.kosha.core.database.model.AssetLiabilityEntity
import dev.kosha.core.database.model.AssetLiabilityKind
import dev.kosha.core.database.model.NetWorthSnapshotEntity
import dev.kosha.core.engine.debt.NetWorthCalculator
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NetWorthUiState(
    val items: List<AssetLiabilityEntity> = emptyList(),
    val netWorth: NetWorthCalculator.NetWorth? = null,
    /** Recorded points, oldest first — the trend line the design review asked for. */
    val history: List<NetWorthSnapshotEntity> = emptyList(),
)

/**
 * Split out of `GoalsViewModel` (design review: net worth deserves its own
 * screen, and its own trend line — the old card only ever showed today's
 * number with no way to see whether it's moving up or down).
 */
@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val goalsDao: GoalsDao,
    private val accountDao: AccountDao,
) : ViewModel() {

    private val historyWindowDays = 365L

    val uiState: StateFlow<NetWorthUiState> = combine(
        goalsDao.observeAssetsLiabilities(),
        goalsDao.observeDebts(),
        goalsDao.observeNetWorthSnapshotsSince(LocalDate.now().toEpochDay() - historyWindowDays),
    ) { items, debts, history ->
        val accounts = accountDao.activeAccounts()
        val accountTotal = Money(accounts.sumOf { it.currentBalancePaise })

        val netWorth = NetWorthCalculator.compute(
            manualAssets = items.filter { it.kind == AssetLiabilityKind.ASSET }
                .map { NetWorthCalculator.Item(it.name, Money(it.valuePaise), false) },
            manualLiabilities = items.filter { it.kind == AssetLiabilityKind.LIABILITY }
                .map { NetWorthCalculator.Item(it.name, Money(it.valuePaise), true) },
            trackedDebtBalances = debts.map {
                NetWorthCalculator.Item(it.name, Money(it.principalPaise), true)
            },
            accountBalances = accountTotal,
        )

        recordSnapshotIfNeeded(netWorth)
        NetWorthUiState(items = items, netWorth = netWorth, history = history)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetWorthUiState())

    /**
     * At most one point per day, written the first time the screen is opened
     * that day. This is real history built from actual computed figures —
     * never backfilled or interpolated — so a fresh install's trend starts
     * empty and fills in visit by visit, which is honest about what the app
     * actually knows.
     */
    private fun recordSnapshotIfNeeded(netWorth: NetWorthCalculator.NetWorth) {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            if (goalsDao.netWorthSnapshotForDay(today) != null) return@launch
            goalsDao.insertNetWorthSnapshot(
                NetWorthSnapshotEntity(
                    epochDay = today,
                    assetsPaise = netWorth.assets.paise,
                    liabilitiesPaise = netWorth.liabilities.paise,
                    netPaise = netWorth.net.paise,
                ),
            )
        }
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
