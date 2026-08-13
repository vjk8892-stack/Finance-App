package dev.kosha.core.engine.pipeline

import dev.kosha.core.engine.merchant.MerchantMatcher
import dev.kosha.core.engine.sms.SmsParser
import dev.kosha.core.engine.sms.SmsPatternLibrary

/**
 * The unified ingestion pipeline (spec B3) — the trust-critical core.
 * Pure Kotlin: callers (Android ingest features) feed it captures plus a
 * dedup window and persist the returned outcome. Ingest modules NEVER write
 * the transaction table directly.
 */
class IngestionPipeline(
    library: SmsPatternLibrary = SmsPatternLibrary.bundled(),
) {
    private val smsParser = SmsParser(library)

    sealed interface Outcome {
        /** Commit as a normal transaction (auto-commit or reviewed-in). */
        data class Commit(
            val txn: ParsedTransaction,
            val score: Double,
            val merchantNormalized: String?,
            val isAtmWithdrawal: Boolean,
            val bank: String?,
            val patternId: String?,
        ) : Outcome

        /** Store as pending review (confidence 0.5–0.9, or unprovable duplicate). */
        data class Review(
            val txn: ParsedTransaction,
            val score: Double,
            val reason: String,
            val possibleDuplicateOfId: Long? = null,
            val merchantNormalized: String?,
        ) : Outcome

        /** Same transaction already recorded — attach evidence only. */
        data class MergeEvidence(val existingId: Long, val rule: String) : Outcome

        /** Recurring instance detected in its window — link, don't double-count. */
        data class LinkRecurring(val ruleId: Long, val txn: ParsedTransaction, val score: Double, val merchantNormalized: String?) : Outcome

        /** Opposite leg of a transfer — commit + propose transfer link in review. */
        data class TransferCandidate(val existingId: Long, val txn: ParsedTransaction, val score: Double) : Outcome

        /** Below the review floor, or non-transactional — log-visible, never a txn. */
        data class Discard(val reason: String) : Outcome

        /** Not a bank sender — privacy allowlist says don't even log content. */
        data object Ignore : Outcome
    }

    fun processSms(
        sender: String,
        body: String,
        receivedAtMillis: Long,
        candidateAccountId: Long?,
        existing: List<DedupEngine.ExistingTxn>,
        expectedRecurring: List<DedupEngine.ExpectedRecurring> = emptyList(),
    ): Outcome {
        return when (val parsed = smsParser.parse(sender, body, receivedAtMillis)) {
            SmsParser.Result.NotBankSender -> Outcome.Ignore
            is SmsParser.Result.NotTransactional -> Outcome.Discard(parsed.reason)
            is SmsParser.Result.Parsed -> settle(
                txn = parsed.txn,
                candidateAccountId = candidateAccountId,
                existing = existing,
                expectedRecurring = expectedRecurring,
                isAtmWithdrawal = parsed.isAtmWithdrawal,
                bank = parsed.bank,
                patternId = parsed.patternId,
            )
        }
    }

    /** Shared by SMS and (Phase 4) OCR captures. */
    fun settle(
        txn: ParsedTransaction,
        candidateAccountId: Long?,
        existing: List<DedupEngine.ExistingTxn>,
        expectedRecurring: List<DedupEngine.ExpectedRecurring> = emptyList(),
        isAtmWithdrawal: Boolean = false,
        bank: String? = null,
        patternId: String? = null,
    ): Outcome {
        val merchantNormalized = txn.merchantRaw
            ?.let { MerchantMatcher.normalize(it) }
            ?.takeIf { it.isNotEmpty() }

        when (val dedup = DedupEngine.check(txn, candidateAccountId, existing, expectedRecurring)) {
            is DedupEngine.DedupResult.Merge ->
                return Outcome.MergeEvidence(dedup.existingId, dedup.rule)

            is DedupEngine.DedupResult.PossibleDuplicate ->
                return Outcome.Review(
                    txn = txn,
                    score = ConfidenceScorer.score(txn),
                    reason = "possible-duplicate",
                    possibleDuplicateOfId = dedup.existingId,
                    merchantNormalized = merchantNormalized,
                )

            is DedupEngine.DedupResult.LinkRecurring ->
                return Outcome.LinkRecurring(
                    ruleId = dedup.ruleId,
                    txn = txn,
                    score = ConfidenceScorer.score(txn),
                    merchantNormalized = merchantNormalized,
                )

            is DedupEngine.DedupResult.TransferCandidate ->
                return Outcome.TransferCandidate(dedup.existingId, txn, ConfidenceScorer.score(txn))

            DedupEngine.DedupResult.Unique -> Unit
        }

        return when (val decision = ConfidenceScorer.decide(txn)) {
            is PipelineDecision.AutoCommit -> Outcome.Commit(
                txn = txn,
                score = decision.score,
                merchantNormalized = merchantNormalized,
                isAtmWithdrawal = isAtmWithdrawal,
                bank = bank,
                patternId = patternId,
            )

            is PipelineDecision.Review -> Outcome.Review(
                txn = txn,
                score = decision.score,
                reason = decision.reason,
                merchantNormalized = merchantNormalized,
            )

            is PipelineDecision.Discard -> Outcome.Discard(decision.reason)
        }
    }
}
