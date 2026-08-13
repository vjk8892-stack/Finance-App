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

    val insights: StateFlow<InsightsRepository.Insights?> = combine(
        transactionDao.observeTransactionCount(),
        settingsRepository.settings,
    ) { _, settings ->
        runCatching {
            insightsRepository.load(settings.periodAnchorDay, settings.emergencyFundMonths)
        }.getOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
}
