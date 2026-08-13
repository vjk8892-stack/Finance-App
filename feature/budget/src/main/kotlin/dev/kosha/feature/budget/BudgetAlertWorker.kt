package dev.kosha.feature.budget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.repo.PeriodRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.engine.period.BudgetMath
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Pre-exceed budget heads-ups (spec Phase 3 / C7). Copy is deliberately calm
 * — "Dining is at 80% with 9 days left" — never alarmist, never red.
 *
 * Inexact periodic work (spec G9: no SCHEDULE_EXACT_ALARM). Silently does
 * nothing when POST_NOTIFICATIONS was denied — every feature still works.
 */
@HiltWorker
class BudgetAlertWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val planningDao: PlanningDao,
    private val categoryDao: CategoryDao,
    private val periodRepository: PeriodRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    // Permission is checked by canNotify() below; lint can't follow that.
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        if (!canNotify()) return Result.success()

        val settings = settingsRepository.settings.first()
        val period = periodRepository.currentPeriod(settings.periodAnchorDay)
        val snapshot = periodRepository.snapshot(period)
        val budgets = planningDao.budgetsOnce()
        if (budgets.isEmpty()) return Result.success()

        val progress = BudgetMath.progress(
            budgets.map { BudgetMath.Budget(it.id, it.categoryId, it.limitPaise, it.alertThresholdPct) },
            snapshot.spendByCategory,
        )
        val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.now(ZoneId.systemDefault()),
            period.endInclusive,
        ).toInt()

        ensureChannel()
        val manager = NotificationManagerCompat.from(appContext)
        progress.filter { it.isAtThreshold }.forEach { p ->
            val name = p.categoryId?.let { categoryDao.byId(it)?.name }
                ?: appContext.getString(R.string.budget_overall)
            val body = if (daysLeft <= 0) {
                appContext.getString(R.string.budget_alert_body_today)
            } else {
                appContext.getString(R.string.budget_alert_body_days, daysLeft)
            }
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(appContext.getString(R.string.budget_alert_title, name, p.pct))
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_LOW) // quiet by design
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_BASE + p.budgetId.toInt(), notification)
        }
        return Result.success()
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.budget_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.budget_channel_desc)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "kosha_budget_alerts"
        private const val WORK_NAME = "kosha-budget-alerts"
        private const val NOTIFICATION_BASE = 4000

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BudgetAlertWorker>(12, TimeUnit.HOURS).build(),
            )
        }
    }
}
