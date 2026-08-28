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
import dev.kosha.core.database.repo.InsightsRepository
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.repo.TransactionRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.forecast.ForecastEngine
import dev.kosha.core.engine.insight.AnomalyEngine
import dev.kosha.core.engine.insight.LeakDetector
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

/**
 * Home's rotating insight card (spec C2.7). The spec's third slot is
 * opportunity-cost, but that simulator needs a user-entered benchmark rate
 * (spec C5.9) — nothing to rotate to automatically — so the advisor's own
 * reasoning fills that slot instead: it's the other thing the Insights hub
 * computes with no extra input needed.
 */
sealed interface HomeInsightCard {
    data class LeakCard(val leak: LeakDetector.Leak) : HomeInsightCard
    data class AnomalyCard(val flag: AnomalyEngine.Flag) : HomeInsightCard
    data class AdvisorCard(val reasoning: String) : HomeInsightCard
}

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
    /** Renders only when non-empty — no placeholder card for nothing to say. */
    val insightCards: List<HomeInsightCard> = emptyList(),
) {
    /**
     * Pulse ring fill: money spent as a fraction of income — how much of
     * this period's income is gone. Clamped to 1 once spend passes income;
     * the hero figure turning amber is what carries "you've gone past it"
     * from there, not a ring that keeps sweeping around again.
     */
    val spendFraction: Float
        get() {
            val reference = maxOf(expectedIncome.paise, income.paise, 1L)
            if (expense.paise <= 0) return 0f
            return (expense.paise.toFloat() / reference).coerceIn(0f, 1f)
        }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val periodRepository: PeriodRepository,
    private val forecastRepository: ForecastRepository,
    private val insightsRepository: InsightsRepository,
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

        // Best-effort: a heavier read of the same engines the Insights hub
        // uses. A failure here (e.g. too little history for the anomaly
        // engine) should never take Home down with it — it just means no
        // rotating card this visit, same as any other empty state.
        val insightCards = runCatching {
            val insights = insightsRepository.load(settings.periodAnchorDay, settings.emergencyFundMonths)
            buildList {
                insights.leaks.firstOrNull()?.let { add(HomeInsightCard.LeakCard(it)) }
                insights.anomalies.firstOrNull()?.let { add(HomeInsightCard.AnomalyCard(it)) }
                if (insights.advice.allocations.isNotEmpty()) {
                    add(HomeInsightCard.AdvisorCard(insights.advice.reasoning))
                }
            }
        }.getOrDefault(emptyList())

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
            insightCards = insightCards,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
