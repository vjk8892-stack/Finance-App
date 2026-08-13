package dev.kosha.feature.ingest.sms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Safety net for OEM battery managers killing the live receiver (risk
 * register, Part F): a periodic inbox scan of the trailing window through
 * the same idempotent pipeline — anything the receiver missed is picked up;
 * anything it didn't miss merges by UTR/time.
 */
@HiltWorker
class SmsReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val importer: HistoricalSmsImporter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            importer.import(monthsBack = 1)
            Result.success()
        } catch (e: SecurityException) {
            // SMS permission revoked — nothing to reconcile.
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "kosha-sms-reconcile"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SmsReconcileWorker>(12, TimeUnit.HOURS).build(),
            )
        }
    }
}
