package dev.kosha.core.engine.pipeline

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.IngestionPipeline.Outcome
import dev.kosha.core.engine.pipeline.ParsedTransaction.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {

    private val pipeline = IngestionPipeline()
    private val t0 = 1_755_000_000_000L

    @Test
    fun `confidence scorer - weighted min behaves per spec`() {
        val strong = ParsedTransaction(
            amount = Money(10_000), type = TxnType.DEBIT,
            fieldConfidence = mapOf(
                Field.AMOUNT to 0.95, Field.TYPE to 0.95, Field.ACCOUNT to 0.95,
                Field.MERCHANT to 0.95, Field.TIMESTAMP to 0.95, Field.REFERENCE to 1.0,
            ),
        )
        assertTrue(ConfidenceScorer.score(strong) >= 0.9)

        // Weak optional field (reference 0.5, weight 0.2) barely dents the score
        val weakRef = strong.copy(
            fieldConfidence = strong.fieldConfidence + (Field.REFERENCE to 0.5),
        )
        assertTrue(ConfidenceScorer.score(weakRef) >= 0.85)

        // Weak critical field (amount 0.6, weight 1.0) drags it into review
        val weakAmount = strong.copy(
            fieldConfidence = strong.fieldConfidence + (Field.AMOUNT to 0.6),
        )
        val score = ConfidenceScorer.score(weakAmount)
        assertTrue(score < 0.9 && score >= 0.5)

        // Missing amount → 0
        assertEquals(0.0, ConfidenceScorer.score(strong.copy(amount = null)), 1e-9)
    }

    @Test
    fun `sms to auto-commit end to end`() {
        val outcome = pipeline.processSms(
            "VM-HDFCBK-S",
            "Sent Rs.545.00 From HDFC Bank A/C x4321 To SWIGGY On 12/08/26 Ref 421234567890",
            t0,
            candidateAccountId = 10,
            existing = emptyList(),
        )
        assertTrue("got $outcome", outcome is Outcome.Commit)
        val commit = outcome as Outcome.Commit
        assertEquals("SWIGGY", commit.merchantNormalized)
        assertTrue(commit.score >= ConfidenceThresholds.AUTO_COMMIT)
    }

    @Test
    fun `same sms twice merges by utr - historical reimport is idempotent`() {
        val body = "Sent Rs.545.00 From HDFC Bank A/C x4321 To SWIGGY On 12/08/26 Ref 421234567890"
        val existing = listOf(
            DedupEngine.ExistingTxn(
                id = 5, amountPaise = 54_500, type = TxnType.DEBIT, accountId = 10,
                accountLast4 = "4321", merchantNormalized = "SWIGGY",
                timestampMillis = t0, reference = "421234567890",
            ),
        )
        val outcome = pipeline.processSms("VM-HDFCBK-S", body, t0 + 1000, 10, existing)
        assertEquals(Outcome.MergeEvidence(5, "utr"), outcome)
    }

    @Test
    fun `personal sender is ignored, otp is discarded`() {
        // The privacy gate is sender SHAPE, not a bank allowlist: a numeric
        // sender is a person and is never read. An unlisted bank header is.
        assertEquals(
            Outcome.Ignore,
            pipeline.processSms("+919812345678", "Rs.500 debited Ref 99887766", t0, null, emptyList()),
        )
        val otp = pipeline.processSms(
            "VM-HDFCBK",
            "635241 is the OTP for txn of Rs.4500.00 at AMAZON. Do not share OTP.",
            t0, null, emptyList(),
        )
        assertTrue(otp is Outcome.Discard)
    }

    @Test
    fun `possible duplicate routes to review with linkage`() {
        val existing = listOf(
            DedupEngine.ExistingTxn(
                id = 9, amountPaise = 54_500, type = TxnType.DEBIT, accountId = 10,
                accountLast4 = "4321", merchantNormalized = null,
                timestampMillis = t0, reference = null,
            ),
        )
        val outcome = pipeline.settle(
            txn = ParsedTransaction(
                amount = Money(54_500), type = TxnType.DEBIT, accountLast4 = "4321",
                merchantRaw = null, timestampMillis = t0 + 60_000, reference = null,
                fieldConfidence = mapOf(Field.AMOUNT to 0.95, Field.TYPE to 0.95),
            ),
            candidateAccountId = 10,
            existing = existing,
        )
        assertTrue(outcome is Outcome.Review)
        assertEquals(9L, (outcome as Outcome.Review).possibleDuplicateOfId)
    }
}
