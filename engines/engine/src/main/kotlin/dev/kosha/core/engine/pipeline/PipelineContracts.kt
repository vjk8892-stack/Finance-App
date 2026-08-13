package dev.kosha.core.engine.pipeline

import dev.kosha.core.common.Money

/**
 * Contracts for the unified ingestion pipeline (spec B3).
 *
 * INVARIANT: ingest modules (:feature:ingest:*) never write to the
 * transaction table directly — every capture source produces a
 * [RawCapture], and only the pipeline decides commit / review / discard.
 */
enum class CaptureSource { SMS, OCR, MANUAL, RECURRING }

enum class TxnType { DEBIT, CREDIT }

/** Raw material entering the pipeline, before normalization. */
data class RawCapture(
    val source: CaptureSource,
    /** Raw text: SMS body, OCR text block, or empty for manual entry. */
    val rawText: String,
    val senderId: String? = null,
    val capturedAtMillis: Long,
    /** Manual entries arrive pre-parsed with full confidence. */
    val prefilled: ParsedTransaction? = null,
)

/**
 * Normalized extraction result. Every field carries its own confidence
 * (0.0–1.0); the transaction score is the weighted minimum (spec B3).
 */
data class ParsedTransaction(
    val amount: Money?,
    val type: TxnType?,
    val accountLast4: String? = null,
    val merchantRaw: String? = null,
    val timestampMillis: Long? = null,
    /** Bank reference / UPI UTR — the strongest dedup key. */
    val reference: String? = null,
    val fieldConfidence: Map<Field, Double> = emptyMap(),
) {
    enum class Field { AMOUNT, TYPE, ACCOUNT, MERCHANT, TIMESTAMP, REFERENCE }
}

/** Spec B3 thresholds: ≥0.9 auto-commit, 0.5–0.9 review queue, <0.5 discard-with-log. */
object ConfidenceThresholds {
    const val AUTO_COMMIT = 0.9
    const val REVIEW = 0.5
}

sealed interface PipelineDecision {
    data class AutoCommit(val txn: ParsedTransaction, val score: Double) : PipelineDecision
    data class Review(val txn: ParsedTransaction, val score: Double, val reason: String) : PipelineDecision
    data class Discard(val score: Double, val reason: String) : PipelineDecision
}
