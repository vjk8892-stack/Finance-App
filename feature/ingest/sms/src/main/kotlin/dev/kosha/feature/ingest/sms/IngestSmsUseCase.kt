package dev.kosha.feature.ingest.sms

import android.util.Log
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.repo.PipelineCommitter
import dev.kosha.core.engine.pipeline.IngestionPipeline
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One SMS in → pipeline → committed rows out. The single entry point used by
 * the live receiver, the historical importer, and the reconcile worker, so
 * dedup/confidence logic can never be bypassed (spec B3).
 */
@Singleton
class IngestSmsUseCase @Inject constructor(
    private val committer: PipelineCommitter,
) {
    private val pipeline = IngestionPipeline()

    suspend fun ingest(sender: String, body: String, receivedAtMillis: Long): PipelineCommitter.CommitResult {
        val existing = committer.dedupWindow(receivedAtMillis)
        val outcome = pipeline.processSms(
            sender = sender,
            body = body,
            receivedAtMillis = receivedAtMillis,
            candidateAccountId = null,
            existing = existing,
        )
        val result = committer.commit(
            outcome = outcome,
            source = TxnSource.SMS,
            rawEvidence = body,
            retainRawBody = false, // spec B4 default: raw SMS not stored
        )
        if (result is PipelineCommitter.CommitResult.Dropped && result.reason != "not-bank-sender") {
            // Discard-with-log (spec B3) — logcat until the debug screen lands.
            Log.i(TAG, "SMS discarded: ${result.reason}")
        }
        return result
    }

    private companion object {
        const val TAG = "KoshaSmsIngest"
    }
}
