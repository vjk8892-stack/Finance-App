package dev.kosha.core.engine.sms

import dev.kosha.core.engine.pipeline.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real Canara Bank alert that Kosha failed to file, and the two defects
 * behind it.
 *
 * The message parsed fine — right amount, right direction, right payee — but
 * its account tail is FIVE digits ("Acct XXXXX07683") and the capture only
 * accepted three or four. Two things followed, and neither looked like the
 * cause:
 *
 *  1. No account could be matched, so the transaction went to the review queue
 *     rather than the ledger. From the outside that is indistinguishable from
 *     "the message was never captured".
 *  2. Worse, the scan did not stop at the user's own account — it carried on
 *     and matched the NEXT tail in the message, which in a transfer is the
 *     payee's account. A transaction silently filed against somebody else's
 *     account number is a much quieter failure than a missing one.
 */
class CanaraBankTest {

    private fun flat(raw: String) = TransactionClassifier.normalize(raw)

    private val debit = flat(
        "Dear Customer, Acct XXXXX07683 Dr. INR 1,500.00 on 14/08/26 to ANANDAN  E; " +
            "UPI: 299784987341; Bal INR 21,129.40.Not you?SMS BLOCKUPI to 9901771222-CanaraBank",
    )

    @Test
    fun `the alert that went missing is read completely`() {
        val outcome = TransactionClassifier.classifyBody(debit)
        assertTrue("should be a transaction: $outcome", outcome is TransactionClassifier.Outcome.Transaction)
        val extraction = (outcome as TransactionClassifier.Outcome.Transaction).extraction

        assertEquals(150_000L, extraction.amount.paise)
        assertEquals(TxnType.DEBIT, extraction.direction)
        assertEquals("ANANDAN E", extraction.merchant)
        assertEquals("299784987341", extraction.reference)
        // The whole point: without this the row cannot be attributed and never
        // reaches the ledger.
        assertEquals("7683", extraction.accountLast4)
    }

    @Test
    fun `a five digit mask does not push the tail onto the payee's account`() {
        // "from <mine> to <theirs>" — the user's account is quoted first, and
        // that is the one the transaction belongs to.
        val transfer = flat(
            "Rs.20000.00 transferred from a/c XXXXX07683 to a/c XXXXX1234 on 14-08-26. Bal Rs.21129.40",
        )
        assertEquals("7683", TransactionClassifier.extractLast4(transfer))
    }

    @Test
    fun `masks of every length land on the last four digits`() {
        mapOf(
            "Acct XXXXX07683 Dr. INR 10.00" to "7683",
            "A/c XX1234 debited INR 10.00" to "1234",
            "Account no. XXXXXX123456 debited INR 10.00" to "3456",
            "a/c 567 debited INR 10.00" to "567",
        ).forEach { (body, expected) ->
            assertEquals(body, expected, TransactionClassifier.extractLast4(flat(body)))
        }
    }

    @Test
    fun `money received from a person is named`() {
        // Ended by a semicolon rather than the word "on", which is why this
        // arrived with no payer at all.
        val credit = flat(
            "Dear Customer, Acct XXXXX07683 Cr. INR 5,000.00 on 12/08/26 from RAMESH K; " +
                "UPI: 299784987342; Bal INR 26,129.40-CanaraBank",
        )
        assertEquals("RAMESH K", TransactionClassifier.extractMerchant(credit, TxnType.CREDIT))
        assertEquals("7683", TransactionClassifier.extractLast4(credit))
    }

    @Test
    fun `structure is still never mistaken for a payee`() {
        // The widened tail capture must not widen what counts as a NAME.
        listOf("a/c", "A/C XXXXX1234", "Acct", "HDFC Bank XX0773", "INR", "14/08/26")
            .forEach { assertTrue("should be rejected: $it", TransactionClassifier.isNotAName(it)) }
        listOf("ANANDAN E", "RAMESH K", "SWIGGY", "S_V_PETROLEUMS_HPCL_")
            .forEach { assertTrue("should be a name: $it", !TransactionClassifier.isNotAName(it)) }
    }

    @Test
    fun `the parser keeps the same answers end to end`() {
        val parsed = SmsParser(SmsPatternLibrary.bundled())
            .parse("VM-CANBNK-S", debit, receivedAtMillis = 1_800_000_000_000L)
        assertTrue("expected a parse, got $parsed", parsed is SmsParser.Result.Parsed)
        val txn = (parsed as SmsParser.Result.Parsed).txn
        assertEquals(150_000L, txn.amount?.paise)
        assertEquals(TxnType.DEBIT, txn.type)
        assertEquals("7683", txn.accountLast4)
        assertNotNull(txn.merchantRaw)
        assertEquals("ANANDAN E", txn.merchantRaw)
    }
}
