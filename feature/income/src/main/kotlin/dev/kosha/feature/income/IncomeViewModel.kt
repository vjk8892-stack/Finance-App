package dev.kosha.feature.income

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.model.IncomeFrequency
import dev.kosha.core.database.model.IncomeSourceEntity
import dev.kosha.core.database.model.PeriodSummaryEntity
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IncomeUiState(
    val sources: List<IncomeSourceEntity> = emptyList(),
    val closedPeriods: List<PeriodSummaryEntity> = emptyList(),
    val anchorDay: Int = 1,
    val currentPeriod: Period? = null,
)

@HiltViewModel
class IncomeViewModel @Inject constructor(
    private val planningDao: PlanningDao,
    private val periodRepository: PeriodRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<IncomeUiState> = combine(
        planningDao.observeIncomeSources(),
        planningDao.observePeriodSummaries(),
        settingsRepository.settings,
    ) { sources, summaries, settings ->
        IncomeUiState(
            sources = sources,
            closedPeriods = summaries,
            anchorDay = settings.periodAnchorDay,
            currentPeriod = periodRepository.currentPeriod(settings.periodAnchorDay),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeUiState())

    fun addSource(name: String, amountRupees: String, frequency: IncomeFrequency, expectedDay: Int?) {
        val amount = Money.parseOrNull(amountRupees) ?: return
        viewModelScope.launch {
            planningDao.insertIncomeSource(
                IncomeSourceEntity(
                    name = name,
                    amountPaise = amount.paise,
                    frequency = frequency,
                    expectedDay = expectedDay?.coerceIn(1, 28),
                ),
            )
        }
    }

    fun removeSource(id: Long) {
        viewModelScope.launch { planningDao.deactivateIncomeSource(id) }
    }

    fun setAnchorDay(day: Int) {
        viewModelScope.launch { settingsRepository.setPeriodAnchorDay(day) }
    }

    /** Manual close (spec Phase 3); auto-close on rollover runs at app start. */
    fun closeCurrentPeriod() {
        viewModelScope.launch {
            val anchor = settingsRepository.settings.first().periodAnchorDay
            periodRepository.close(periodRepository.currentPeriod(anchor))
        }
    }
}
