package dev.kosha.feature.insights.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.repo.InsightsRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.insight.OpportunityCostSimulator
import dev.kosha.core.engine.insight.WhatIfSimulator
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class WhatIfState(
    val categoryName: String? = null,
    val monthlySpend: Money = Money.ZERO,
    val cutPercent: Int = 10,
) {
    val result: WhatIfSimulator.Result get() = WhatIfSimulator.simulate(monthlySpend, cutPercent)
}

data class OpportunityCostState(
    val categoryName: String? = null,
    val monthlySpend: Money = Money.ZERO,
    val ratePercent: Double = 8.0,
    val months: Int = 36,
) {
    val result: OpportunityCostSimulator.Result
        get() = OpportunityCostSimulator.simulate(monthlySpend, months, ratePercent)
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insightsRepository: InsightsRepository,
    transactionDao: TransactionDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * How many periods back the whole hub is looking. Every section reads the
     * same number, so the screen can never show one month's chart above
     * another month's advice.
     */
    private val _periodsBack = MutableStateFlow(0)
    val periodsBack: StateFlow<Int> = _periodsBack.asStateFlow()

    val insights: StateFlow<InsightsRepository.Insights?> = combine(
        transactionDao.observeTransactionCount(),
        settingsRepository.settings,
        _periodsBack,
    ) { _, settings, back ->
        runCatching {
            insightsRepository.load(settings.periodAnchorDay, settings.emergencyFundMonths, back)
        }.getOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Older. Capped at two years so the arrow cannot walk off into empty months forever. */
    fun showEarlierPeriod() {
        _periodsBack.value = (_periodsBack.value + 1).coerceAtMost(MAX_PERIODS_BACK)
    }

    /** Newer. Stops at the current period — there is nothing after today. */
    fun showLaterPeriod() {
        _periodsBack.value = (_periodsBack.value - 1).coerceAtLeast(0)
    }

    private val _whatIf = MutableStateFlow(WhatIfState())
    val whatIf: StateFlow<WhatIfState> = _whatIf.asStateFlow()

    private val _opportunityCost = MutableStateFlow(OpportunityCostState())
    val opportunityCost: StateFlow<OpportunityCostState> = _opportunityCost.asStateFlow()

    fun selectWhatIfCategory(name: String, monthlySpend: Money) {
        _whatIf.value = _whatIf.value.copy(categoryName = name, monthlySpend = monthlySpend)
    }

    fun setCutPercent(percent: Int) {
        _whatIf.value = _whatIf.value.copy(cutPercent = percent.coerceIn(0, 100))
    }

    fun selectOpportunityCategory(name: String, monthlySpend: Money) {
        _opportunityCost.value = _opportunityCost.value.copy(
            categoryName = name,
            monthlySpend = monthlySpend,
        )
    }

    /** The benchmark rate is the user's own assumption (spec C5.9). */
    fun setBenchmarkRate(percent: Double) {
        _opportunityCost.value = _opportunityCost.value.copy(
            ratePercent = percent.coerceIn(0.0, 30.0),
        )
    }

    fun setHorizonMonths(months: Int) {
        _opportunityCost.value = _opportunityCost.value.copy(months = months.coerceIn(12, 120))
    }

    private companion object {
        const val MAX_PERIODS_BACK = 24
    }
}
