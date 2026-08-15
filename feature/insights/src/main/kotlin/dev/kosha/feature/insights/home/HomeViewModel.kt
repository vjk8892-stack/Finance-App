package dev.kosha.feature.insights.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.CategoryType
import dev.kosha.core.database.repo.CategoryRepository
import dev.kosha.core.database.repo.ForecastRepository
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.repo.TransactionRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.forecast.ForecastEngine
import dev.kosha.core.engine.period.BudgetMath
import dev.kosha.core.engine.period.PeriodMath
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HomeBudgetRing(
    val progress: BudgetMath.BudgetProgress,
    val label: String,
    val icon: String?,
)

data class HomeUiState(
    val loaded: Boolean = false,
    val period: Period? = null,
    val income: Money = Money.ZERO,
    val expense: Money = Money.ZERO,
    val savingsGap: Money = Money.ZERO,
    val expectedIncome: Money = Money.ZERO,
    val tone: PeriodMath.WeatherTone = PeriodMath.WeatherTone.ON_TRACK,
    val hasData: Boolean = false,
    val reviewCount: Int = 0,
    val oldestReviewAgeDays: Int = 0,
    val budgetRings: List<HomeBudgetRing> = emptyList(),
    /** Most-used categories for the quick-add row (spec C2.3). */
    val quickCategories: List<CategoryEntity> = emptyList(),
    val forecast: ForecastEngine.Forecast? = null,
) {
    /**
     * Pulse ring fill: savings gap as a fraction of income. Negative gap
     * shows an empty ring — the amber weather line carries that message.
     */
    val pulseFraction: Float
        get() {
            val reference = maxOf(expectedIncome.paise, income.paise)
            if (reference <= 0 || savingsGap.paise <= 0) return 0f
            return (savingsGap.paise.toFloat() / reference).coerceIn(0f, 1f)
        }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val periodRepository: PeriodRepository,
    private val forecastRepository: ForecastRepository,
    private val planningDao: PlanningDao,
    transactionDao: TransactionDao,
    transactionRepository: TransactionRepository,
    categoryRepository: CategoryRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        transactionDao.observeTransactionCount(),
        planningDao.observeBudgets(),
        categoryRepository.observeAll(),
        transactionRepository.observeReviewQueue(),
        settingsRepository.settings,
    ) { txnCount, budgets, categories, reviewRows, settings ->
        val period = periodRepository.currentPeriod(settings.periodAnchorDay)
        val snapshot = periodRepository.snapshot(period)
        val byId = categories.associateBy { it.id }

        val rings = BudgetMath.progress(
            budgets.map { BudgetMath.Budget(it.id, it.categoryId, it.limitPaise, it.alertThresholdPct) },
            snapshot.spendByCategory,
        ).map { p ->
            val category = p.categoryId?.let { byId[it] }
            HomeBudgetRing(p, category?.name ?: "", category?.icon)
        }

        val oldestAgeDays = reviewRows.minOfOrNull { it.txn.timestampMillis }?.let { oldest ->
            ((System.currentTimeMillis() - oldest) / 86_400_000L).toInt()
        } ?: 0

        // Quick-add: categories with the most spend this period, then seeds.
        val ranked = snapshot.spendByCategory.entries
            .sortedByDescending { it.value.paise }
            .mapNotNull { it.key?.let { id -> byId[id] } }
        val fallback = categories.filter { !it.isSystem && it.type == CategoryType.EXPENSE }
        val quick = (ranked + fallback).distinctBy { it.id }.take(5)

        HomeUiState(
            loaded = true,
            period = period,
            income = snapshot.totals.actualIncome,
            expense = snapshot.totals.totalExpense,
            savingsGap = snapshot.totals.savingsGap,
            expectedIncome = snapshot.expectedIncome,
            tone = snapshot.tone,
            hasData = txnCount > 0,
            reviewCount = reviewRows.size,
            oldestReviewAgeDays = oldestAgeDays,
            budgetRings = rings,
            quickCategories = quick,
            forecast = runCatching { forecastRepository.forecast() }.getOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
