package dev.kosha.app.constitution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.CategoryType
import dev.kosha.core.database.model.ConstitutionRuleEntity
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.ConstitutionRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.constitution.ConstitutionEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConstitutionUiState(
    val statuses: List<ConstitutionRepository.RuleStatus> = emptyList(),
    val expenseCategories: List<CategoryEntity> = emptyList(),
    val trend: ConstitutionEngine.Trend = ConstitutionEngine.Trend.NOT_ENOUGH_DATA,
)

/**
 * The personal financial constitution had rows in the database and an
 * engine with its own test suite, but no screen anywhere — a design review
 * finding. This is that screen's ViewModel.
 */
@HiltViewModel
class ConstitutionViewModel @Inject constructor(
    private val constitutionRepository: ConstitutionRepository,
    private val categoryRepository: CategoryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Bumped after every write so machine-checkable rules re-evaluate immediately, not on the next natural tick. */
    private val refresh = MutableStateFlow(0)

    val uiState: StateFlow<ConstitutionUiState> = combine(
        constitutionRepository.allRules,
        categoryRepository.observeAll(),
        settingsRepository.settings,
        refresh,
    ) { rules, categories, settings, _ ->
        val statuses = constitutionRepository.evaluateActive(settings.periodAnchorDay)
        val byId = statuses.associateBy { it.rule.id }
        // evaluateActive only covers ACTIVE rules; inactive ones still need a
        // row here (with no evaluation) so the toggle can turn them back on.
        val merged = rules.map { rule -> byId[rule.id] ?: ConstitutionRepository.RuleStatus(rule, null) }
        ConstitutionUiState(
            statuses = merged,
            expenseCategories = categories.filter { it.type == CategoryType.EXPENSE },
            trend = constitutionRepository.violationTrend(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConstitutionUiState())

    fun addFreeTextRule(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            constitutionRepository.addFreeTextRule(text.trim())
            refresh.value++
        }
    }

    fun addCategoryLimitRule(categoryName: String, limitRupees: String) {
        val limit = Money.parseOrNull(limitRupees) ?: return
        viewModelScope.launch {
            constitutionRepository.addCategoryLimitRule(categoryName, limit)
            refresh.value++
        }
    }

    fun setActive(rule: ConstitutionRuleEntity, active: Boolean) {
        viewModelScope.launch {
            constitutionRepository.setActive(rule, active)
            refresh.value++
        }
    }

    fun delete(rule: ConstitutionRuleEntity) {
        viewModelScope.launch {
            constitutionRepository.delete(rule)
            refresh.value++
        }
    }
}
