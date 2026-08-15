package dev.kosha.core.engine.ocr

import dev.kosha.core.engine.pipeline.ParsedTransaction.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real PhonePe receipt for ₹50 that Kosha read as ₹750.
 *
 * The rupee glyph is frequently recognised as a leading DIGIT — "₹50" comes
 * back as "750" — and nothing in the text distinguishes that from a genuine
 * ₹750. Reading an unmarked number as the amount is still worth doing, since
 * the alternative is reading nothing at all, but it is a GUESS and the only
 * honest thing to do with a guess that can be an order of magnitude out is to
 * say so. It arrives below the review threshold so the preview flags the field
 * and the row cannot pass unexamined.
 *
 * The payee on the same receipt was read as "MG" — the avatar monogram drawn
 * beside the name, which sits on the line above it.
 */
class GlyphAmbiguityTest {

    private val extractor = OcrExtractor()

    private fun receipt(amountLine: String) = """
        Transaction Successful
        07:26 pm on 08 Aug 2026
        Paid to
        MG
        Mani Gopalgowda
        paytmqr596xbh@paytm
        $amountLine
        Transfer Details
        PhonePe Transaction ID
        T2608081926247196831405
        Debited from
        XXXXX6056
        UTR: 439400122362
        Powered by
        UPI
        YES BANK
    """.trimIndent()

    @Test
    fun `the payee is the name, not the avatar monogram beside it`() {
        val result = extractor.extract(receipt("₹50"), NOW, liveCapture = false)!!
        assertEquals("Mani Gopalgowda", result.txn.merchantRaw)
    }

    @Test
    fun `a genuinely short payee name is not thrown away`() {
        // The monogram rule must only fire when something fuller follows it.
        val text = """
            Paid to
            KFC
            paytmqr1@paytm
            ₹250
            UTR: 439400122362
        """.trimIndent()
        val result = extractor.extract(text, NOW, liveCapture = false)!!
        assertEquals("KFC", result.txn.merchantRaw)
    }

    @Test
    fun `an amount read without a currency marker is flagged as uncertain`() {
        val result = extractor.extract(receipt("750"), NOW, liveCapture = false)!!
        val confidence = result.txn.fieldConfidence[Field.AMOUNT]!!
        assertTrue(
            "a guessed amount must be flagged, was $confidence",
            confidence < LOW_CONFIDENCE_THRESHOLD,
        )
    }

    @Test
    fun `an amount with a real currency marker keeps full confidence`() {
        val result = extractor.extract(receipt("₹50"), NOW, liveCapture = false)!!
        assertEquals(5_000L, result.txn.amount?.paise)
        val confidence = result.txn.fieldConfidence[Field.AMOUNT]!!
        assertFalse(
            "a marked amount must not be flagged, was $confidence",
            confidence < LOW_CONFIDENCE_THRESHOLD,
        )
    }

    @Test
    fun `the rest of the receipt is still read correctly around a bad glyph`() {
        // The amount being uncertain must not cost the fields that ARE certain.
        val result = extractor.extract(receipt("750"), NOW, liveCapture = false)!!
        assertEquals("Mani Gopalgowda", result.txn.merchantRaw)
        assertEquals("439400122362", result.txn.reference)
        assertEquals("6056", result.txn.accountLast4)
        assertEquals(AUG_8_2026_MIDDAY, result.txn.timestampMillis)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        /** The preview highlights any field under this. */
        const val LOW_CONFIDENCE_THRESHOLD = 0.8
        /** 8 Aug 2026, midday UTC. */
        const val AUG_8_2026_MIDDAY = 1_786_190_400_000L
    }
}
