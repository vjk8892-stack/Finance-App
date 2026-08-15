package dev.kosha.core.engine.ocr

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.ConfidenceScorer
import dev.kosha.core.engine.pipeline.ConfidenceThresholds
import dev.kosha.core.engine.pipeline.DedupEngine
import dev.kosha.core.engine.pipeline.IngestionPipeline
import dev.kosha.core.engine.pipeline.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-4 exit gates:
 *  - screenshot → committed transaction with no edits needed;
 *  - a photo of a transaction already captured via SMS NEVER duplicates.
 */
class OcrExtractorTest {

    private val extractor = OcrExtractor()
    private val t0 = 1_755_000_000_000L

    private val phonePeScreenshot = """
        PhonePe
        ₹545
        Paid to SWIGGY
        12 Aug 2026, 8:42 pm
        Transaction ID T2608121942099211234567
        UTR 421234567890
        Debited from HDFC Bank XX4321
    """.trimIndent()

    private val gpayScreenshot = """
        Google Pay
        ₹1,299.00
        To BIG BAZAAR
        Completed
        12 Aug 2026
        UPI transaction ID 522233445566
        From Axis Bank ****5566
    """.trimIndent()

    private val paytmScreenshot = """
        Paytm
        Paid Successfully
        ₹250
        Paid to LOCAL KIRANA STORE
        UPI Ref 523344556677
    """.trimIndent()

    private val printedBill = """
        RELIANCE DIGITAL
        Tax Invoice
        GST 27AAACR1234M1ZP
        HDMI Cable Rs 499
        Bluetooth Speaker Rs 2,499
        Sub Total Rs 2,998
        Grand Total Rs 3,538
    """.trimIndent()

    @Test
    fun `phonepe screenshot extracts amount, merchant and utr`() {
        val result = extractor.extract(phonePeScreenshot, t0)
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(Money(54_500), result.txn.amount)
        assertEquals(TxnType.DEBIT, result.txn.type)
        assertEquals("SWIGGY", result.txn.merchantRaw)
        assertEquals("421234567890", result.txn.reference)
        assertEquals("phonepe", result.appLabel)
    }

    @Test
    fun `gpay screenshot extracts and records the account tail`() {
        val result = extractor.extract(gpayScreenshot, t0)!!
        assertEquals(Money(129_900), result.txn.amount)
        assertEquals("BIG BAZAAR", result.txn.merchantRaw)
        assertEquals("522233445566", result.txn.reference)
        assertEquals("5566", result.txn.accountLast4)
    }

    @Test
    fun `paytm screenshot extracts`() {
        val result = extractor.extract(paytmScreenshot, t0)!!
        assertEquals(Money(25_000), result.txn.amount)
        assertEquals("LOCAL KIRANA STORE", result.txn.merchantRaw)
        assertEquals("paytm", result.appLabel)
    }

    @Test
    fun `failed payment screenshots are never extracted`() {
        val failed = phonePeScreenshot.replace("Paid to SWIGGY", "Payment Failed\nPaid to SWIGGY")
        assertNull(extractor.extract(failed, t0))
    }

    @Test
    fun `printed bill takes the grand total, not the sub total or line items`() {
        val result = extractor.extract(printedBill, t0)!!
        assertEquals(Money(353_800), result.txn.amount)
        assertEquals("generic-bill", result.appLabel)
        assertEquals("RELIANCE DIGITAL", result.txn.merchantRaw)
    }

    @Test
    fun `bill line items are extracted for splits and warranty capture`() {
        val result = extractor.extract(printedBill, t0)!!
        val names = result.lineItems.map { it.name }
        assertTrue("got $names", names.any { it.contains("HDMI", true) })
        assertTrue("got $names", names.any { it.contains("Speaker", true) })
        // Warranty prompt targets the priciest item on the bill.
        assertTrue(result.warrantyCandidate!!.contains("Speaker", true))
    }

    @Test
    fun `upi screenshots auto-commit, bills go to review`() {
        val upiScore = ConfidenceScorer.score(extractor.extract(phonePeScreenshot, t0)!!.txn)
        assertTrue("upi score $upiScore", upiScore >= ConfidenceThresholds.AUTO_COMMIT)

        val billScore = ConfidenceScorer.score(extractor.extract(printedBill, t0)!!.txn)
        assertTrue(
            "bill score $billScore should land in review",
            billScore < ConfidenceThresholds.AUTO_COMMIT && billScore >= ConfidenceThresholds.REVIEW,
        )
    }

    @Test
    fun `bank utr wins over the app's own transaction id`() {
        // PhonePe prints both; only the bank UTR appears in the bank's SMS,
        // so it is the key the dedup engine must key on.
        val result = extractor.extract(phonePeScreenshot, t0)!!
        assertEquals("421234567890", result.txn.reference)
    }

    @Test
    fun `an undated gallery import scores lower on time and lands in review`() {
        // A screenshot picked from the gallery can be months old, so CAPTURE
        // time is not transaction time and must not auto-commit.
        //
        // Narrowed deliberately: the extractor now reads the date printed on
        // the receipt itself, and where it finds one this rule's reason no
        // longer applies — the timestamp is the receipt's own statement, not a
        // guess from when the file was picked. The protection still holds for
        // a receipt that states no date, which is the case it was written for.
        val undated = phonePeScreenshot.lines()
            .filterNot { it.contains("2026") }
            .joinToString("\n")
        val imported = extractor.extract(undated, t0, liveCapture = false)!!
        val score = ConfidenceScorer.score(imported.txn)
        assertTrue(
            "imported score $score should be review-bound",
            score < ConfidenceThresholds.AUTO_COMMIT && score >= ConfidenceThresholds.REVIEW,
        )
    }

    @Test
    fun `photo of an sms-captured transaction never duplicates`() {
        val pipeline = IngestionPipeline()
        // The SMS committed first, with the same UTR.
        val existing = listOf(
            DedupEngine.ExistingTxn(
                id = 42, amountPaise = 54_500, type = TxnType.DEBIT, accountId = 10,
                accountLast4 = "4321", merchantNormalized = "SWIGGY",
                timestampMillis = t0, reference = "421234567890",
            ),
        )
        val extraction = extractor.extract(phonePeScreenshot, t0 + 5 * 60_000)!!
        val outcome = pipeline.settle(extraction.txn, candidateAccountId = 10, existing = existing)
        assertEquals(IngestionPipeline.Outcome.MergeEvidence(42, "utr"), outcome)
    }

    @Test
    fun `photo without a utr still merges on amount, account, window and merchant`() {
        val pipeline = IngestionPipeline()
        val existing = listOf(
            DedupEngine.ExistingTxn(
                id = 7, amountPaise = 25_000, type = TxnType.DEBIT, accountId = 10,
                accountLast4 = "4321", merchantNormalized = "LOCAL KIRANA STORE",
                timestampMillis = t0, reference = null,
            ),
        )
        val noUtr = paytmScreenshot.replace("UPI Ref 523344556677", "")
        val extraction = extractor.extract(noUtr, t0 + 3 * 60_000)!!
        val outcome = pipeline.settle(extraction.txn, candidateAccountId = 10, existing = existing)
        assertTrue("got $outcome", outcome is IngestionPipeline.Outcome.MergeEvidence)
    }

    @Test
    fun `empty or unreadable text yields nothing`() {
        assertNull(extractor.extract("", t0))
        assertNull(extractor.extract("blurry\nnothing here", t0))
    }
}
