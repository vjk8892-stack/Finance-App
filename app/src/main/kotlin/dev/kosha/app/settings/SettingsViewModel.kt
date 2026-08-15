package dev.kosha.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.repo.BalanceMaintainer
import dev.kosha.core.database.settings.KoshaSettings
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.database.settings.trackingStartDate
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A gap opened up by moving the tracking date EARLIER.
 *
 * Rows that are already in the database simply reappear — nothing was ever
 * deleted. But bank messages in the newly covered window were never imported,
 * because the importer refuses to reach below the boundary. Without saying so,
 * moving the date back would reveal a period that looks suspiciously empty and
 * give no clue why.
 */
data class BackfillOffer(val from: LocalDate, val to: LocalDate)

data class SettingsUiState(
    val settings: KoshaSettings = KoshaSettings(),
    val trackingStart: LocalDate? = null,
    val trackedTransactions: Int = 0,
    val hiddenTransactions: Int = 0,
    val backfill: BackfillOffer? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val transactionDao: TransactionDao,
    private val balanceMaintainer: BalanceMaintainer,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val _backfill = MutableStateFlow<BackfillOffer?>(null)
    val backfill: StateFlow<BackfillOffer?> = _backfill.asStateFlow()

    val state: StateFlow<SettingsUiState> = settingsRepository.settings
        .map { settings ->
            val boundary = settings.trackingStartDate
            val cutoff = boundary?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
            // Counted rather than estimated: "482 hidden" is a fact the user
            // can act on, where "some older transactions" is not.
            val all = transactionDao.observeLedger().first()
            SettingsUiState(
                settings = settings,
                trackingStart = boundary,
                trackedTransactions = all.count { it.txn.timestampMillis >= cutoff },
                hiddenTransactions = all.count { it.txn.timestampMillis < cutoff },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * Moving the boundary is never destructive, so this only ever changes a
     * date and recomputes balances. Balances have to be redone immediately:
     * they are stored, not derived on read, so leaving them would show figures
     * summed over the previous window until the next unrelated edit.
     */
    fun setTrackingStart(date: LocalDate?) {
        viewModelScope.launch {
            val previous = settingsRepository.settings.first().trackingStartDate
            settingsRepository.setTrackingStart(date)
            balanceMaintainer.recomputeEverything()

            // Only an EARLIER boundary can uncover un-scanned messages.
            val newlyCovered = when {
                previous == null -> null
                date == null -> LocalDate.ofEpochDay(0) to previous.minusDays(1)
                date.isBefore(previous) -> date to previous.minusDays(1)
                else -> null
            }
            _backfill.value = newlyCovered?.let { (from, to) -> BackfillOffer(from, to) }
        }
    }

    fun dismissBackfill() {
        _backfill.value = null
    }

    fun setPeriodAnchorDay(day: Int) {
        viewModelScope.launch { settingsRepository.setPeriodAnchorDay(day) }
    }

    fun setRetainRawSms(retain: Boolean) {
        viewModelScope.launch { settingsRepository.setRetainRawSms(retain) }
    }

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.setAppLock(enabled, current.appLockTimeoutMillis)
        }
    }
}
