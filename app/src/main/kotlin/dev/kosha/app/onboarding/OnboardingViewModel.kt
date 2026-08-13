package dev.kosha.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.app.settings.SettingsRepository
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.model.IncomeFrequency
import dev.kosha.core.database.model.IncomeSourceEntity
import dev.kosha.core.database.repo.AccountRepository
import dev.kosha.feature.ingest.sms.HistoricalSmsImporter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Onboarding state machine (spec C8): philosophy → accounts → income →
 * SMS disclosure (skippable) → historical import → notifications → app lock
 * → Home with real data.
 */
enum class OnboardingStep { PHILOSOPHY, ACCOUNTS, INCOME, SMS, IMPORT, NOTIFICATIONS, APP_LOCK }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.PHILOSOPHY,
    val smsGranted: Boolean = false,
    val importing: Boolean = false,
    val importProgress: Pair<Int, Int>? = null,
    val importSummary: HistoricalSmsImporter.ImportSummary? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val planningDao: PlanningDao,
    private val settingsRepository: SettingsRepository,
    private val importer: HistoricalSmsImporter,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun next() {
        val steps = OnboardingStep.entries
        val current = _state.value.step
        val nextIndex = steps.indexOf(current) + 1
        if (nextIndex < steps.size) {
            var next = steps[nextIndex]
            // Without SMS permission the import step is meaningless — skip it.
            if (next == OnboardingStep.IMPORT && !_state.value.smsGranted) {
                next = OnboardingStep.NOTIFICATIONS
            }
            _state.value = _state.value.copy(step = next)
        }
    }

    fun addAccount(name: String, type: AccountType, openingRupees: String) {
        viewModelScope.launch {
            accountRepository.create(
                name = name,
                type = type,
                openingBalancePaise = Money.parseOrNull(openingRupees)?.paise ?: 0,
            )
        }
    }

    fun setMonthlyIncome(rupees: String, anchorDay: Int) {
        val amount = Money.parseOrNull(rupees) ?: return
        viewModelScope.launch {
            planningDao.insertIncomeSource(
                IncomeSourceEntity(
                    name = "Salary",
                    amountPaise = amount.paise,
                    frequency = IncomeFrequency.MONTHLY,
                    expectedDay = anchorDay.coerceIn(1, 28),
                ),
            )
            settingsRepository.setPeriodAnchorDay(anchorDay)
        }
    }

    fun onSmsPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(smsGranted = granted)
    }

    fun runImport(monthsBack: Int) {
        if (_state.value.importing) return
        _state.value = _state.value.copy(importing = true)
        viewModelScope.launch {
            val summary = try {
                importer.import(monthsBack) { scanned, total ->
                    _state.value = _state.value.copy(importProgress = scanned to total)
                }
            } catch (e: Exception) {
                HistoricalSmsImporter.ImportSummary(0, 0, 0, 0, 0)
            }
            _state.value = _state.value.copy(importing = false, importSummary = summary)
        }
    }

    fun setAppLock(enabled: Boolean, timeoutMillis: Long) {
        viewModelScope.launch { settingsRepository.setAppLock(enabled, timeoutMillis) }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setOnboardingDone()
            onDone()
        }
    }
}
