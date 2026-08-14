package dev.kosha.core.engine.sms

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.ConfidenceScorer
import dev.kosha.core.engine.pipeline.ConfidenceThresholds
import dev.kosha.core.engine.pipeline.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the shapes real bank SMS actually take.
 *
 * The original corpus was written as single-line strings, which hid a bug
 * that made the whole pattern library useless on a real phone: bank messages
 * are MULTI-LINE, and Kotlin's `.` does not cross a newline, so every
 * pattern using `.+?` failed and every message fell through to the
 * low-confidence fallback. Amounts survived; merchants did not.
 *
 * Every case here keeps its real line breaks. Account numbers, references
 * and names are fabricated.
 */
class RealWorldSmsTest {

    private val parser = SmsParser(SmsPatternLibrary.bundled())
    private val t0 = 1_755_000_000_000L

    private fun parsed(sender: String, body: String): SmsParser.Result.Parsed {
        val result = parser.parse(sender, body, t0)
        assertTrue("expected Parsed, got $result for:\n$body", result is SmsParser.Result.Parsed)
        return result as SmsParser.Result.Parsed
    }

    @Test
    fun `multi-line hdfc upi debit parses merchant, not just the amount`() {
        val p = parsed(
            "VM-HDFCBK-S",
            """
            Sent Rs.40.00
            From HDFC Bank A/C x1234
            To ZOMATO
            On 15/07/25
            Ref 519612345678
            Not You? Call 18002586161
            """.trimIndent(),
        )
        assertEquals(Money(4_000), p.txn.amount)
        assertEquals(TxnType.DEBIT, p.txn.type)
        assertEquals("1234", p.txn.accountLast4)
        assertEquals("ZOMATO", p.txn.merchantRaw)
        assertEquals("519612345678", p.txn.reference)
        assertTrue(
            "a fully parsed message should auto-commit",
            ConfidenceScorer.score(p.txn) >= ConfidenceThresholds.AUTO_COMMIT,
        )
    }

    @Test
    fun `multi-line hdfc large transfer to a person`() {
        val p = parsed(
            "VM-HDFCBK-S",
            """
            Sent Rs.12000.00
            From HDFC Bank A/C x1234
            To JOHN DOE
            On 16/07/25
            Ref 519812345678
            """.trimIndent(),
        )
        assertEquals(Money(1_200_000), p.txn.amount)
        assertEquals("JOHN DOE", p.txn.merchantRaw)
    }

    @Test
    fun `hdfc credit with vpa`() {
        val p = parsed(
            "VM-HDFCBK",
            "Rs.503.00 credited to HDFC Bank A/C xx1234 on 16-07-25 " +
                "by a/c linked to VPA payer@ybl (UPI 519712345678)",
        )
        assertEquals(Money(50_300), p.txn.amount)
        assertEquals(TxnType.CREDIT, p.txn.type)
        assertEquals("1234", p.txn.accountLast4)
    }

    @Test
    fun `debited to vpa with reference number phrasing`() {
        val p = parsed(
            "VM-HDFCBK",
            "Dear Customer, Rs.150.00 has been debited from account **1234 " +
                "to VPA merchant@paytm on 16-07-25. Your UPI transaction " +
                "reference number is 519912345678.",
        )
        assertEquals(Money(15_000), p.txn.amount)
        assertEquals(TxnType.DEBIT, p.txn.type)
        assertEquals("merchant@paytm", p.txn.merchantRaw)
        assertEquals("519912345678", p.txn.reference)
    }

    @Test
    fun `card spend ending in an available balance is still a transaction`() {
        // The balance filter must not swallow real spends: most debit alerts
        // end with "Avl bal".
        val p = parsed(
            "VM-HDFCBK",
            "Rs.25.00 spent on HDFC Bank Card x1234 at PAYTM on 19-07-25. " +
                "Avl bal Rs.5,000.00",
        )
        assertEquals(Money(2_500), p.txn.amount)
        assertEquals("PAYTM", p.txn.merchantRaw)
    }

    @Test
    fun `a pure balance alert is still rejected`() {
        val result = parser.parse(
            "VM-HDFCBK",
            "Avl bal in A/C xx1234 is Rs.5,230.55 as on 19-07-25",
            t0,
        )
        assertTrue("got $result", result is SmsParser.Result.NotTransactional)
    }

    @Test
    fun `sbi multi-line debit`() {
        val p = parsed(
            "CP-SBIUPI-S",
            """
            Dear UPI user A/C X9876 debited by 120.0
            on date 12Aug25 trf to ZOMATO Refno 519876543210.
            If not u? call 1800111109. -SBI
            """.trimIndent(),
        )
        assertEquals(Money(12_000), p.txn.amount)
        assertEquals("ZOMATO", p.txn.merchantRaw)
        assertEquals("519876543210", p.txn.reference)
    }

    @Test
    fun `unknown phrasing yields full detail and is trusted on its own merits`() {
        // No library pattern for this one. What decides the outcome is how
        // much of the transaction skeleton came back, not whether anyone
        // happened to write a regex for this bank.
        val p = parsed(
            "VM-FEDBNK",
            """
            Update! INR 1,00,000.00 deducted from Federal Bank XX1234
            on 19-JUL-25 to VPA landlord@okhdfcbank.
            Ref No 519012345678
            """.trimIndent(),
        )
        assertEquals(Money(10_000_000), p.txn.amount)
        assertEquals("landlord@okhdfcbank", p.txn.merchantRaw)
        assertEquals("519012345678", p.txn.reference)
        assertEquals("1234", p.txn.accountLast4)
        assertEquals(null, p.patternId)
        val score = ConfidenceScorer.score(p.txn)
        assertTrue(
            "a complete extraction should auto-commit, score=$score",
            score >= ConfidenceThresholds.AUTO_COMMIT,
        )
    }

    @Test
    fun `a transaction with no available balance is unaffected`() {
        // Many alerts never quote a balance. Direction comes from the verb,
        // so their absence changes nothing.
        val p = parsed(
            "VM-IDFCFB",
            """
            INR 2,499.00 debited from IDFC FIRST Bank A/c XX3344
            towards BIGBASKET on 04-08-26.
            UPI Ref No 601234567890
            """.trimIndent(),
        )
        assertEquals(Money(249_900), p.txn.amount)
        assertEquals(TxnType.DEBIT, p.txn.type)
        assertEquals("3344", p.txn.accountLast4)
        assertEquals("601234567890", p.txn.reference)
    }

    @Test
    fun `two accounts at two banks keep their own tails`() {
        val a = parsed(
            "VM-HDFCBK-S",
            "Sent Rs.40.00\nFrom HDFC Bank A/C x1234\nTo ZOMATO\nOn 15/07/25\nRef 519612345678",
        )
        val b = parsed(
            "VM-ICICIB",
            "ICICI Bank Acct XX7788 debited for Rs 900.00 on 15-Jul-25; " +
                "SWIGGY credited. UPI:519612345679.",
        )
        assertEquals("1234", a.txn.accountLast4)
        assertEquals("7788", b.txn.accountLast4)
    }

    @Test
    fun `money that has not moved yet is not a transaction`() {
        listOf(
            "Your EMI of Rs.4,500.00 is due on 05-08-26 for loan a/c XX1234.",
            "Rs.1,200.00 will be debited from a/c XX1234 on 07-08-26 towards NETFLIX autopay.",
            "JOHN DOE has requested Rs.500.00 via UPI. Pay by 19:00 today.",
        ).forEach { body ->
            val result = parser.parse("VM-HDFCBK", body, t0)
            assertTrue("future/request leaked through: $body → $result", result is SmsParser.Result.NotTransactional)
        }
    }

    @Test
    fun `a failed payment is not a transaction`() {
        val result = parser.parse(
            "VM-HDFCBK",
            "Your payment of Rs.2,000.00 to AMAZON from a/c XX1234 has failed. Ref 519912345678.",
            t0,
        )
        assertTrue("got $result", result is SmsParser.Result.NotTransactional)
    }

    @Test
    fun `otp is still rejected even when it mentions a debit`() {
        val result = parser.parse(
            "VM-HDFCBK",
            "635241 is the OTP to authorise Rs.4,500.00 debited from a/c x1234. Do not share this.",
            t0,
        )
        assertTrue("got $result", result is SmsParser.Result.NotTransactional)
    }

    @Test
    fun `merchant capture never returns a date or a bare number`() {
        val p = parsed(
            "VM-HDFCBK",
            "Rs.1.00 debited from a/c **1234 to VPA test@upi on 19-07-25. Ref 519112345678",
        )
        val merchant = p.txn.merchantRaw
        assertNotNull(merchant)
        assertTrue("merchant looked like a date: $merchant", !merchant!!.matches(Regex("\\d{1,2}[-/].*")))
        assertTrue("merchant was all digits: $merchant", !merchant.all { it.isDigit() })
    }
}
