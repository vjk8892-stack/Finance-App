package dev.kosha.feature.ingest.sms

import android.util.Log
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.repo.PipelineCommitter
import dev.kosha.core.database.repo.RecurringRepository
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.database.settings.TrackingWindow
import dev.kosha.core.engine.pipeline.IngestionPipeline
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * One SMS in → pipeline → committed rows out. The single entry point used by
 * the live receiver, the historical importer, and the reconcile worker, so
 * dedup/confidence logic can never be bypassed (spec B3).
 */
@Singleton
class IngestSmsUseCase @Inject constructor(
    private val trackingWindow: TrackingWindow,
    private val committer: PipelineCommitter,
    private val recurringRepository: RecurringRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val pipeline = IngestionPipeline()

    suspend fun ingest(sender: String, body: String, receivedAtMillis: Long): PipelineCommitter.CommitResult {
        // Live capture respects the boundary too. Without this, a message
        // arriving now with an older receipt time would be committed into a
        // window the user has chosen not to track and simply never appear.
        val trackingStart = trackingWindow.startMillisNow()
        if (trackingStart > 0 && receivedAtMillis < trackingStart) {
            return PipelineCommitter.CommitResult.Dropped("before-tracking-start")
        }
        val existing = committer.dedupWindow(receivedAtMillis)
        val outcome = pipeline.processSms(
            sender = sender,
            body = body,
            receivedAtMillis = receivedAtMillis,
            candidateAccountId = null,
            existing = existing,
            // A detected EMI/bill inside a rule's due window links to the
            // rule instead of double-counting (spec B3 rule 3).
            expectedRecurring = recurringRepository.expectedRecurringWindows(),
        )
        val result = committer.commit(
            outcome = outcome,
            source = TxnSource.SMS,
            rawEvidence = body,
            // Spec B4: raw SMS is not stored unless the user opts in. When
            // they do, the original text rides along so a bad parse can be
            // seen and reported rather than just distrusted.
            retainRawBody = settingsRepository.settings.first().retainRawSms,
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
