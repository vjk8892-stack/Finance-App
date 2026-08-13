package dev.kosha.feature.ingest.ocr

import android.net.Uri
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.repo.PipelineCommitter
import dev.kosha.core.engine.ocr.OcrExtractor
import dev.kosha.core.engine.pipeline.IngestionPipeline
import dev.kosha.core.engine.pipeline.ParsedTransaction
import dev.kosha.core.engine.pipeline.TxnType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photo → OCR → the SAME unified pipeline as SMS (spec B3). Extraction is
 * previewed and editable before commit, so the user is always the final
 * word on a low-confidence parse.
 */
@Singleton
class IngestPhotoUseCase @Inject constructor(
    private val recognizer: OcrTextRecognizer,
    private val committer: PipelineCommitter,
) {
    private val extractor = OcrExtractor()
    private val pipeline = IngestionPipeline()

    data class Preview(
        val uri: Uri,
        val amount: Money?,
        val type: TxnType,
        val merchant: String?,
        val reference: String?,
        val accountLast4: String?,
        val capturedAtMillis: Long,
        val liveCapture: Boolean,
        val lineItems: List<OcrExtractor.LineItem>,
        val warrantyCandidate: String?,
        val appLabel: String,
        /** Field-level flags drive the confidence highlights in the preview UI. */
        val lowConfidenceFields: Set<ParsedTransaction.Field>,
    )

    /** Step 1: recognize + extract, for the editable preview screen (spec C4). */
    suspend fun preview(uri: Uri, liveCapture: Boolean): Preview? {
        val text = runCatching { recognizer.recognize(uri) }.getOrNull() ?: return null
        val capturedAt = System.currentTimeMillis()
        val extraction = extractor.extract(text, capturedAt, liveCapture) ?: return null
        val lowConfidence = extraction.txn.fieldConfidence
            .filterValues { it < 0.8 }
            .keys
        return Preview(
            uri = uri,
            amount = extraction.txn.amount,
            type = extraction.txn.type ?: TxnType.DEBIT,
            merchant = extraction.txn.merchantRaw,
            reference = extraction.txn.reference,
            accountLast4 = extraction.txn.accountLast4,
            capturedAtMillis = capturedAt,
            liveCapture = liveCapture,
            lineItems = extraction.lineItems,
            warrantyCandidate = extraction.warrantyCandidate,
            appLabel = extraction.appLabel,
            lowConfidenceFields = lowConfidence,
        )
    }

    /**
     * Step 2: commit the (possibly user-edited) preview through the pipeline.
     * Dedup still runs — confirming a photo of an SMS-captured spend attaches
     * evidence instead of creating a second transaction (Phase-4 exit gate).
     */
    suspend fun commit(preview: Preview, confirmedByUser: Boolean): PipelineCommitter.CommitResult {
        val amount = preview.amount
            ?: return PipelineCommitter.CommitResult.Dropped("no-amount")

        val txn = ParsedTransaction(
            amount = amount,
            type = preview.type,
            accountLast4 = preview.accountLast4,
            merchantRaw = preview.merchant,
            timestampMillis = preview.capturedAtMillis,
            reference = preview.reference,
            fieldConfidence = if (confirmedByUser) {
                // The user reviewed every field on screen — that IS the
                // confirmation the review queue would otherwise ask for.
                ParsedTransaction.Field.entries.associateWith { 1.0 }
            } else {
                emptyMap()
            },
        )
        val existing = committer.dedupWindow(preview.capturedAtMillis)
        val outcome = pipeline.settle(
            txn = txn,
            candidateAccountId = null,
            existing = existing,
        )
        return committer.commit(
            outcome = outcome,
            source = TxnSource.OCR,
            rawEvidence = preview.uri.toString(),
            retainRawBody = true, // photo URIs are always kept as evidence
        )
    }
}
