package dev.kosha.feature.ingest.ocr

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
import dev.kosha.core.database.dao.MetaDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Warranty expiry reminders (spec G12): a notification at 30 days and at 7
 * days before an item's tracked warranty runs out. Checked once a day —
 * exact enough for a warranty, and inexact periodic work needs no
 * SCHEDULE_EXACT_ALARM justification (spec G9).
 *
 * Fires once per milestone because the check is `==`, not `<=`: a rule that
 * matched "within 7 days" would re-notify on every one of those 7 days.
 * Missing a day (Doze, no connectivity to run) means that one reminder is
 * silently skipped rather than repeated — an acceptable trade for a
 * heads-up, not a bill due-date.
 */
@HiltWorker
class WarrantyReminderWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val metaDao: MetaDao,
) : CoroutineWorker(appContext, params) {

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = try {
        if (canNotify()) {
            ensureChannel()
            val today = LocalDate.now(ZoneId.systemDefault())
            val manager = NotificationManagerCompat.from(appContext)
            metaDao.observeWarranties().first().forEach { item ->
                val expiry = Instant.ofEpochMilli(item.expiryDateMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                val daysAway = ChronoUnit.DAYS.between(today, expiry).toInt()
                if (daysAway == LEAD_DAYS_FAR || daysAway == LEAD_DAYS_NEAR) {
                    manager.notify(
                        NOTIFICATION_BASE + item.id.toInt(),
                        NotificationCompat.Builder(appContext, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(appContext.getString(R.string.warranty_reminder_title, item.itemName))
                            .setContentText(appContext.getString(R.string.warranty_reminder_body, daysAway))
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
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
                appContext.getString(R.string.warranty_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.warranty_channel_desc)
                enableVibration(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ID = "kosha_warranty"
        private const val WORK_NAME = "kosha-warranty-reminders"
        private const val NOTIFICATION_BASE = 6000
        private const val LEAD_DAYS_FAR = 30
        private const val LEAD_DAYS_NEAR = 7

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WarrantyReminderWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}
