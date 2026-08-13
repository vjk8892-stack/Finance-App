package dev.kosha.feature.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.BudgetEntity
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.period.BudgetMath
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BudgetRow(
    val progress: BudgetMath.BudgetProgress,
    val categoryName: String,
    val categoryIcon: String?,
)

data class BudgetUiState(
    val rows: List<BudgetRow> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
)

@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val planningDao: PlanningDao,
    private val periodRepository: PeriodRepository,
    settingsRepository: SettingsRepository,
    transactionDao: TransactionDao,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val uiState: StateFlow<BudgetUiState> = combine(
        planningDao.observeBudgets(),
        categoryRepository.observeAll(),
        transactionDao.observeTransactionCount(),
        settingsRepository.settings,
    ) { budgets, categories, _, settings ->
        val snapshot = periodRepository.snapshot(
            periodRepository.currentPeriod(settings.periodAnchorDay),
        )
        val progress = BudgetMath.progress(
            budgets.map {
                BudgetMath.Budget(it.id, it.categoryId, it.limitPaise, it.alertThresholdPct)
            },
            snapshot.spendByCategory,
        )
        val byId = categories.associateBy { it.id }
        BudgetUiState(
            rows = progress.map { p ->
                val category = p.categoryId?.let { byId[it] }
                BudgetRow(
                    progress = p,
                    categoryName = category?.name ?: "",
                    categoryIcon = category?.icon,
                )
            },
            categories = categories.filter { !it.isSystem },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    fun addBudget(categoryId: Long?, limitRupees: String, thresholdPct: Int) {
        val limit = Money.parseOrNull(limitRupees) ?: return
        viewModelScope.launch {
            planningDao.insertBudget(
                BudgetEntity(
                    categoryId = categoryId,
                    limitPaise = limit.paise,
                    alertThresholdPct = thresholdPct,
                    startDateMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun removeBudget(budgetId: Long) {
        viewModelScope.launch { planningDao.deactivateBudget(budgetId) }
    }
}
