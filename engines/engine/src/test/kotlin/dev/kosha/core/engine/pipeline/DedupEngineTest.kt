package dev.kosha.core.engine.pipeline

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.DedupEngine.DedupResult
import dev.kosha.core.engine.pipeline.DedupEngine.ExistingTxn
import dev.kosha.core.engine.pipeline.DedupEngine.ExpectedRecurring
import dev.kosha.core.engine.pipeline.ParsedTransaction.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupEngineTest {

    private val t0 = 1_755_000_000_000L
    private val min = 60_000L

    private fun candidate(
        amountPaise: Long = 54_500,
        type: TxnType = TxnType.DEBIT,
        last4: String? = "4321",
        merchant: String? = "SWIGGY",
        ts: Long = t0,
        ref: String? = null,
    ) = ParsedTransaction(
        amount = Money(amountPaise),
        type = type,
        accountLast4 = last4,
        merchantRaw = merchant,
        timestampMillis = ts,
        reference = ref,
        fieldConfidence = mapOf(Field.AMOUNT to 0.95, Field.TYPE to 0.95),
    )

    private fun existing(
        id: Long = 1,
        amountPaise: Long = 54_500,
        type: TxnType = TxnType.DEBIT,
        accountId: Long = 10,
        last4: String? = "4321",
        merchant: String? = "SWIGGY",
        ts: Long = t0,
        ref: String? = null,
    ) = ExistingTxn(id, amountPaise, type, accountId, last4, merchant, ts, ref)

    @Test
    fun `rule 1 - utr match merges unconditionally even outside window`() {
        val result = DedupEngine.check(
            candidate(ref = "421234567890", ts = t0 + 3 * 60 * min, merchant = "COMPLETELY DIFFERENT"),
            candidateAccountId = null,
            existing = listOf(existing(ref = "421234567890")),
        )
        assertEquals(DedupResult.Merge(1, "utr"), result)
    }

    @Test
    fun `rule 2 - same amount account window with matching merchant merges`() {
        // Photo of a PhonePe screenshot 4 minutes after the SMS committed
        val result = DedupEngine.check(
            candidate(merchant = "swiggy@icici", ts = t0 + 4 * min),
            candidateAccountId = 10,
            existing = listOf(existing(merchant = "SWIGGY")),
        )
        assertTrue("got $result", result is DedupResult.Merge)
        assertEquals("amount-account-time-merchant", (result as DedupResult.Merge).rule)
    }

    @Test
    fun `rule 2 - same amount account window without merchant proof is possible duplicate`() {
        val result = DedupEngine.check(
            candidate(merchant = null, ts = t0 + 2 * min),
            candidateAccountId = 10,
            existing = listOf(existing(merchant = "SWIGGY")),
        )
        assertEquals(DedupResult.PossibleDuplicate(1), result)
    }

    @Test
    fun `rule 2 - outside ten minute window is unique`() {
        val result = DedupEngine.check(
            candidate(merchant = "SWIGGY", ts = t0 + 11 * min),
            candidateAccountId = 10,
            existing = listOf(existing(merchant = "SWIGGY")),
        )
        assertEquals(DedupResult.Unique, result)
    }

    @Test
    fun `rule 2 - different amount is unique`() {
        val result = DedupEngine.check(
            candidate(amountPaise = 54_600),
            candidateAccountId = 10,
            existing = listOf(existing()),
        )
        assertEquals(DedupResult.Unique, result)
    }

    @Test
    fun `rule 3 - expected recurring emi links instead of duplicating`() {
        val result = DedupEngine.check(
            candidate(amountPaise = 1_500_000, merchant = "BAJAJ FINANCE"),
            candidateAccountId = 10,
            existing = emptyList(),
            expectedRecurring = listOf(
                ExpectedRecurring(
                    ruleId = 77,
                    amountPaise = 1_500_000,
                    accountId = 10,
                    merchantPattern = "BAJAJ",
                    dueWindowStartMillis = t0 - 86_400_000,
                    dueWindowEndMillis = t0 + 86_400_000,
                ),
            ),
        )
        assertEquals(DedupResult.LinkRecurring(77), result)
    }

    @Test
    fun `rule 4 - opposite legs across accounts propose transfer`() {
        // Debit from bank + credit into wallet, same amount, 1 min apart
        val result = DedupEngine.check(
            candidate(type = TxnType.CREDIT, last4 = "8899", merchant = null, ts = t0 + min),
            candidateAccountId = 20,
            existing = listOf(existing(type = TxnType.DEBIT, accountId = 10)),
        )
        assertEquals(DedupResult.TransferCandidate(1), result)
    }

    @Test
    fun `unknown candidate account is conservative - flags possible duplicate`() {
        val result = DedupEngine.check(
            candidate(last4 = null, merchant = null),
            candidateAccountId = null,
            existing = listOf(existing()),
        )
        assertEquals(DedupResult.PossibleDuplicate(1), result)
    }
}
