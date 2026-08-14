package dev.kosha.feature.ingest.sms

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.database.repo.PipelineCommitter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Historical inbox import (spec C8 step 5). Runs through the SAME pipeline
 * as live capture; a re-import is idempotent because UTR/amount+time dedup
 * merges anything already recorded (spec G12).
 */
@Singleton
class HistoricalSmsImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ingest: IngestSmsUseCase,
) {

    data class ImportSummary(
        val scanned: Int,
        val committed: Int,
        val queuedForReview: Int,
        val merged: Int,
        val ignored: Int,
    )

    /** Convenience for the "last N months" chips. */
    suspend fun import(
        monthsBack: Int,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportSummary = importSince(
        sinceMillis = System.currentTimeMillis() - monthsBack * 30L * 24 * 60 * 60 * 1000,
        onProgress = onProgress,
    )

    /**
     * Scan everything received at or after [sinceMillis]. Exposed separately
     * because "the last N months" is the wrong frame when you know the date
     * that matters — the day you opened the account, or the day you started
     * using Kosha. Re-running over an overlapping range is safe: dedup merges
     * anything already recorded rather than duplicating it.
     */
    suspend fun importSince(
        sinceMillis: Long,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportSummary = withContext(Dispatchers.IO) {
        val since = sinceMillis
        var scanned = 0
        var committed = 0
        var queued = 0
        var merged = 0
        var ignored = 0

        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return@withContext ImportSummary(0, 0, 0, 0, 0)

        cursor.use { c ->
            val total = c.count
            val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                scanned++
                val sender = c.getString(addressIdx) ?: continue
                val body = c.getString(bodyIdx) ?: continue
                val date = c.getLong(dateIdx)
                when (ingest.ingest(sender, body, date)) {
                    is PipelineCommitter.CommitResult.Committed -> committed++
                    is PipelineCommitter.CommitResult.QueuedForReview -> queued++
                    is PipelineCommitter.CommitResult.MergedEvidence -> merged++
                    is PipelineCommitter.CommitResult.Dropped -> ignored++
                }
                if (scanned % 25 == 0) onProgress(scanned, total)
            }
            onProgress(scanned, total)
        }
        ImportSummary(scanned, committed, queued, merged, ignored)
    }
}
