package dev.kosha.core.engine.pipeline

import dev.kosha.core.engine.merchant.MerchantMatcher
import kotlin.math.abs

/**
 * Dedup engine, spec B3 (elevated to critical — Axio's top complaint is
 * duplicate spends). Pure function over the candidate + a window of existing
 * transactions:
 *
 *  1. Exact UTR/reference match → merge unconditionally.
 *  2. Same amount + same account + within ±10 min → auto-merge if merchant
 *     fuzzy-matches, else "possible duplicate" for the review queue.
 *  3. Recurring-rule instance in the expected window → link, never double-count.
 *  4. (From B5) equal amount, DIFFERENT accounts, opposite direction within
 *     ±10 min → propose a Transfer link.
 */
object DedupEngine {

    const val WINDOW_MILLIS: Long = 10 * 60 * 1000

    /** Minimal view of an existing committed/pending transaction. */
    data class ExistingTxn(
        val id: Long,
        val amountPaise: Long,
        val type: TxnType,
        val accountId: Long,
        val accountLast4: String?,
        val merchantNormalized: String?,
        val timestampMillis: Long,
        val reference: String?,
        val recurringRuleId: Long? = null,
    )

    /** A recurring instance expected around now (arrives Phase 5). */
    data class ExpectedRecurring(
        val ruleId: Long,
        val amountPaise: Long?,
        val accountId: Long?,
        val merchantPattern: String?,
        val dueWindowStartMillis: Long,
        val dueWindowEndMillis: Long,
    )

    sealed interface DedupResult {
        /** Same real-world transaction — attach evidence to [existingId], insert nothing. */
        data class Merge(val existingId: Long, val rule: String) : DedupResult

        /** Probably a duplicate, not provable — surface in review queue. */
        data class PossibleDuplicate(val existingId: Long) : DedupResult

        /** Matches an expected recurring instance — link to the rule. */
        data class LinkRecurring(val ruleId: Long) : DedupResult

        /** Opposite leg of a self-transfer — propose a Transfer link. */
        data class TransferCandidate(val existingId: Long) : DedupResult

        data object Unique : DedupResult
    }

    fun check(
        candidate: ParsedTransaction,
        candidateAccountId: Long?,
        existing: List<ExistingTxn>,
        expectedRecurring: List<ExpectedRecurring> = emptyList(),
    ): DedupResult {
        val amount = candidate.amount?.paise ?: return DedupResult.Unique
        val ts = candidate.timestampMillis ?: return DedupResult.Unique

        // Rule 1: reference/UTR — unconditional merge.
        val ref = candidate.reference
        if (!ref.isNullOrBlank()) {
            existing.firstOrNull { it.reference == ref }?.let {
                return DedupResult.Merge(it.id, "utr")
            }
        }

        val candidateMerchant = candidate.merchantRaw?.let { MerchantMatcher.normalize(it) }.orEmpty()

        // Rule 2: amount + account + time window.
        val sameAccountMatches = existing.filter { txn ->
            txn.amountPaise == amount &&
                txn.type == candidate.type &&
                abs(txn.timestampMillis - ts) <= WINDOW_MILLIS &&
                sameAccount(candidate, candidateAccountId, txn)
        }
        for (txn in sameAccountMatches) {
            val existingMerchant = txn.merchantNormalized.orEmpty()
            if (candidateMerchant.isNotEmpty() && existingMerchant.isNotEmpty() &&
                MerchantMatcher.sameMerchant(candidateMerchant, existingMerchant)
            ) {
                return DedupResult.Merge(txn.id, "amount-account-time-merchant")
            }
        }
        // No merchant proof on either side — can't auto-merge safely.
        sameAccountMatches.firstOrNull()?.let { return DedupResult.PossibleDuplicate(it.id) }

        // Rule 3: expected recurring instance window.
        for (expected in expectedRecurring) {
            val amountOk = expected.amountPaise == null || expected.amountPaise == amount
            val accountOk = expected.accountId == null || candidateAccountId == null ||
                expected.accountId == candidateAccountId
            val timeOk = ts in expected.dueWindowStartMillis..expected.dueWindowEndMillis
            val merchantOk = expected.merchantPattern.isNullOrBlank() ||
                candidateMerchant.contains(MerchantMatcher.normalize(expected.merchantPattern)) ||
                MerchantMatcher.sameMerchant(candidateMerchant, MerchantMatcher.normalize(expected.merchantPattern))
            if (amountOk && accountOk && timeOk && merchantOk) {
                return DedupResult.LinkRecurring(expected.ruleId)
            }
        }

        // Rule 4 (B5): transfer proposal — same amount, different account,
        // opposite direction, inside the window.
        val opposite = if (candidate.type == TxnType.DEBIT) TxnType.CREDIT else TxnType.DEBIT
        existing.firstOrNull { txn ->
            txn.amountPaise == amount &&
                txn.type == opposite &&
                abs(txn.timestampMillis - ts) <= WINDOW_MILLIS &&
                !sameAccount(candidate, candidateAccountId, txn)
        }?.let { return DedupResult.TransferCandidate(it.id) }

        return DedupResult.Unique
    }

    private fun sameAccount(
        candidate: ParsedTransaction,
        candidateAccountId: Long?,
        existing: ExistingTxn,
    ): Boolean {
        if (candidateAccountId != null) return candidateAccountId == existing.accountId
        val last4 = candidate.accountLast4
        // Unknown account on the candidate: be conservative — treat as the
        // same account so near-identical amounts surface as possible dupes
        // rather than committing twice.
        if (last4.isNullOrBlank() || existing.accountLast4.isNullOrBlank()) return true
        return last4.takeLast(3) == existing.accountLast4!!.takeLast(3)
    }
}
