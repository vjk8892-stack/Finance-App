package dev.kosha.feature.widgets

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.period.BudgetMath
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.engine.period.PeriodMath
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Widget refresh (spec G11): a 30-minute periodic window, plus an immediate
 * one-shot after any app-driven data change.
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val periodRepository: PeriodRepository,
    private val settingsRepository: SettingsRepository,
    private val planningDao: PlanningDao,
    private val categoryDao: CategoryDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val settings = settingsRepository.settings.first()
        val period = periodRepository.currentPeriod(settings.periodAnchorDay)
        val snapshot = periodRepository.snapshot(period)

        val budgets = planningDao.budgetsOnce()
        val rings = BudgetMath.progress(
            budgets.map { BudgetMath.Budget(it.id, it.categoryId, it.limitPaise, it.alertThresholdPct) },
            snapshot.spendByCategory,
        ).sortedByDescending { it.pct }.take(2)

        val ringLabels = rings.map { ring ->
            val name = ring.categoryId?.let { categoryDao.byId(it)?.name } ?: "Overall"
            "$name ${ring.pct}%"
        }

        val weather = when (snapshot.tone) {
            PeriodMath.WeatherTone.AHEAD -> appContext.getString(R.string.widget_weather_ahead)
            PeriodMath.WeatherTone.ON_TRACK -> appContext.getString(R.string.widget_weather_on_track)
            PeriodMath.WeatherTone.HEADS_UP -> appContext.getString(R.string.widget_weather_heads_up)
        }

        val manager = GlanceAppWidgetManager(appContext)
        manager.getGlanceIds(KoshaDashboardWidget::class.java).forEach { glanceId ->
            updateAppWidgetState(appContext, glanceId) { prefs ->
                prefs[KoshaDashboardWidget.KEY_WEATHER] = weather
                prefs[KoshaDashboardWidget.KEY_PULSE] =
                    snapshot.totals.savingsGap.format(withPaise = false)
                ringLabels.getOrNull(0)?.let { prefs[KoshaDashboardWidget.KEY_BUDGET_1] = it }
                ringLabels.getOrNull(1)?.let { prefs[KoshaDashboardWidget.KEY_BUDGET_2] = it }
            }
        }
        KoshaDashboardWidget().updateAll(appContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        private const val PERIODIC_WORK = "kosha-widget-refresh"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build(),
            )
        }

        /** Called after a commit so the widget does not lag the ledger. */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
        }
    }
}
