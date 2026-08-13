package dev.kosha.feature.budget.recurring

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
import dev.kosha.core.common.Money
import dev.kosha.core.database.repo.RecurringRepository
import dev.kosha.feature.budget.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Recurring engine tick (spec Phase 5): posts due auto-log instances that no
 * real transaction covers, and raises calm reminders for remind-only rules
 * and credit-card due dates.
 *
 * Inexact periodic work — no SCHEDULE_EXACT_ALARM (spec G9).
 */
@HiltWorker
class RecurringWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val recurringRepository: RecurringRepository,
) : CoroutineWorker(appContext, params) {

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = try {
        recurringRepository.postDueAutoLogs()

        if (canNotify()) {
            ensureChannel()
            val today = LocalDate.now(ZoneId.systemDefault())
            val manager = NotificationManagerCompat.from(appContext)
            recurringRepository.dueRules(today.plusDays(REMINDER_LEAD_DAYS))
                .filter { !it.rule.autoLog && !it.alreadyCovered }
                .forEach { due ->
                    val dueDate = Instant.ofEpochMilli(due.rule.nextDueDateMillis)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    val daysAway = ChronoUnit.DAYS.between(today, dueDate).toInt()
                    val body = when {
                        daysAway <= 0 -> appContext.getString(R.string.recurring_due_today)
                        else -> appContext.getString(R.string.recurring_due_days, daysAway)
                    }
                    val title = if (due.rule.isCreditCardDue) {
                        appContext.getString(R.string.recurring_card_due_title, due.rule.label)
                    } else {
                        due.rule.amountPaise?.let {
                            appContext.getString(
                                R.string.recurring_due_title_amount,
                                due.rule.label,
                                Money(it).format(withPaise = false),
                            )
                        } ?: appContext.getString(R.string.recurring_due_title, due.rule.label)
                    }
                    manager.notify(
                        NOTIFICATION_BASE + due.rule.id.toInt(),
                        NotificationCompat.Builder(appContext, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
        }
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.recurring_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.recurring_channel_desc)
                enableVibration(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "kosha_recurring"
        private const val WORK_NAME = "kosha-recurring"
        private const val NOTIFICATION_BASE = 5000
        private const val REMINDER_LEAD_DAYS = 3L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecurringWorker>(12, TimeUnit.HOURS).build(),
            )
        }
    }
}
