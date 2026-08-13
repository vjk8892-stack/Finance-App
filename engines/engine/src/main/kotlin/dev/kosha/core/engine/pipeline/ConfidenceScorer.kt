package dev.kosha.core.engine.pipeline

import dev.kosha.core.engine.pipeline.ParsedTransaction.Field

/**
 * Spec B3: each extracted field gets a score; the transaction score is the
 * WEIGHTED MIN — a critical field (amount, type) at low confidence drags the
 * whole transaction down; a weak optional field (reference) barely matters.
 *
 * effective(field) = 1 − weight × (1 − confidence); score = min over fields.
 * A missing critical field scores 0.
 */
object ConfidenceScorer {

    private val weights = mapOf(
        Field.AMOUNT to 1.0,
        Field.TYPE to 1.0,
        Field.ACCOUNT to 0.6,
        Field.MERCHANT to 0.5,
        Field.TIMESTAMP to 0.4,
        Field.REFERENCE to 0.2,
    )

    fun score(txn: ParsedTransaction): Double {
        if (txn.amount == null || txn.amount.paise <= 0 || txn.type == null) return 0.0
        var minEffective = 1.0
        for ((field, weight) in weights) {
            val confidence = txn.fieldConfidence[field] ?: 0.5
            val effective = 1.0 - weight * (1.0 - confidence)
            if (effective < minEffective) minEffective = effective
        }
        return minEffective.coerceIn(0.0, 1.0)
    }

    fun decide(txn: ParsedTransaction): PipelineDecision {
        val s = score(txn)
        return when {
            s >= ConfidenceThresholds.AUTO_COMMIT -> PipelineDecision.AutoCommit(txn, s)
            s >= ConfidenceThresholds.REVIEW -> PipelineDecision.Review(txn, s, "confidence $s")
            else -> PipelineDecision.Discard(s, "confidence $s below discard threshold")
        }
    }
}
