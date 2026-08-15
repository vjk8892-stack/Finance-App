package dev.kosha.core.engine.ocr

import dev.kosha.core.engine.pipeline.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Receipts are read by their SHAPE, not by whose logo is on them.
 *
 * A real payment receipt Kosha could not read is the fixture here. Every app
 * lays a receipt out the same way — a hero amount, a "to" label, a reference,
 * a date — but the wording differs, and crucially the VALUE usually sits on
 * the line BELOW its label. Reading only the label's own line returned the
 * screen heading ("payment details") as the merchant, no reference, no card,
 * and today's date on a payment made weeks ago.
 */
class ReceiptStructureTest {

    private val extractor = OcrExtractor()

    private val receipt = """
        payment details
        ₹175
        15 aug 2026 • 6:07 pm
        to:
        LJ IYENGARS PASTRY PALACE
        paytm.s1rj2je@pty
        from:
        CITY UNION BANK RUPAY CREDIT CARD XXXXXX76
        9845948557@seyes
        transaction ID:
        659307666484
        you earned a total of 5
        salaryse
        POWERED BY UPI
        YES BANK
    """.trimIndent()

    @Test
    fun `an unbranded payment receipt is read completely`() {
        val result = extractor.extract(receipt, capturedAtMillis = NOW, liveCapture = false)
        assertNotNull("nothing extracted at all", result)
        val txn = result!!.txn

        assertEquals(17_500L, txn.amount?.paise)
        assertEquals(TxnType.DEBIT, txn.type)
        // The payee sits on the line after "to:", and the VPA under it is an
        // address rather than a name.
        assertEquals("LJ IYENGARS PASTRY PALACE", txn.merchantRaw)
        // The reference is all digits, on the line after its label.
        assertEquals("659307666484", txn.reference)
        // Two visible digits is a normal card mask.
        assertEquals("76", txn.accountLast4)
    }

    @Test
    fun `the date printed on the receipt beats the moment it was imported`() {
        val result = extractor.extract(receipt, capturedAtMillis = NOW, liveCapture = false)!!
        // 15 Aug 2026, not the import time. A screenshot picked from the
        // gallery weeks later is otherwise filed on the wrong day, which is
        // the single thing that makes an imported receipt useless.
        assertEquals(AUG_15_2026_MIDDAY, result.txn.timestampMillis)
    }

    @Test
    fun `the from block is the user's own card, never the payee`() {
        // Reading it would both misname the payee and flip a payment into
        // income — the same class of error as a card bill counted as salary.
        val result = extractor.extract(receipt, capturedAtMillis = NOW, liveCapture = false)!!
        assertEquals(TxnType.DEBIT, result.txn.type)
    }

    @Test
    fun `money genuinely received is still a credit`() {
        val incoming = """
            payment received
            ₹2,500
            12 aug 2026 • 9:15 am
            from:
            RAMESH KUMAR
            ramesh@okaxis
            UTR: 522233445566
        """.trimIndent()
        val result = extractor.extract(incoming, capturedAtMillis = NOW, liveCapture = false)!!
        assertEquals(TxnType.CREDIT, result.txn.type)
        assertEquals("RAMESH KUMAR", result.txn.merchantRaw)
        assertEquals(250_000L, result.txn.amount?.paise)
    }

    @Test
    fun `screen furniture is never a merchant name`() {
        listOf(
            "payment details", "POWERED BY UPI", "you earned a total of 5",
            "paytm.s1rj2je@pty", "XXXXXX76", "659307666484", "₹175", "credit card",
        ).forEach { assertEquals("should be chrome: $it", true, extractor.isChrome(it)) }

        listOf("LJ IYENGARS PASTRY PALACE", "RAMESH KUMAR", "Big Bazaar")
            .forEach { assertEquals("should be a name: $it", false, extractor.isChrome(it)) }
    }

    @Test
    fun `a printed bill with no payee label still works`() {
        // The generic-bill path must keep working — this receipt has no "to"
        // label at all, so it must not be routed as a payment receipt.
        val bill = """
            SPICE GARDEN RESTAURANT
            Paneer Tikka Rs.240
            Butter Naan Rs.60
            Grand Total Rs.300
        """.trimIndent()
        val result = extractor.extract(bill, capturedAtMillis = NOW, liveCapture = true)!!
        assertEquals(30_000L, result.txn.amount?.paise)
        assertEquals("SPICE GARDEN RESTAURANT", result.txn.merchantRaw)
        assertNull("no reference on a printed bill", result.txn.reference)
    }

    @Test
    fun `receipt dates parse in the forms apps actually print`() {
        mapOf(
            "15 aug 2026 • 6:07 pm" to AUG_15_2026_MIDDAY,
            "15-Aug-26" to AUG_15_2026_MIDDAY,
            "Aug 15, 2026" to AUG_15_2026_MIDDAY,
            "15/08/2026" to AUG_15_2026_MIDDAY,
        ).forEach { (line, expected) ->
            assertEquals(line, expected, extractor.receiptDate(listOf(line)))
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        /** 15 Aug 2026, midday UTC. */
        const val AUG_15_2026_MIDDAY = 1_786_795_200_000L
    }
}
