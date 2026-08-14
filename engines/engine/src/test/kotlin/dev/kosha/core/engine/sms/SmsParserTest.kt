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
 * Anonymized SMS corpus (spec Part E: real formats, fake numbers/names).
 * Phase-2 exit gate: bank formats parse; OTP/promo false-positives are ZERO;
 * unrecognized bank formats land in review (low confidence), never dropped.
 */
class SmsParserTest {

    private val parser = SmsParser(SmsPatternLibrary.bundled())
    private val t0 = 1_755_000_000_000L

    private fun parsed(sender: String, body: String): SmsParser.Result.Parsed {
        val result = parser.parse(sender, body, t0)
        assertTrue("expected Parsed, got $result for: $body", result is SmsParser.Result.Parsed)
        return result as SmsParser.Result.Parsed
    }

    @Test
    fun `library loads with 15+ patterns and sane senders`() {
        val lib = SmsPatternLibrary.bundled()
        assertTrue(lib.compiled.size >= 15)
        assertTrue("HDFCBK" in lib.allowedSenderCodes)
        assertTrue("SBIUPI" in lib.allowedSenderCodes)
    }

    @Test
    fun `sender normalization strips dlt prefix and suffix`() {
        assertEquals("HDFCBK", SmsPatternLibrary.normalizeSender("VM-HDFCBK-S"))
        assertEquals("HDFCBK", SmsPatternLibrary.normalizeSender("AD-HDFCBK"))
        assertEquals("SBIUPI", SmsPatternLibrary.normalizeSender("jd-SBIUPI-t"))
        assertEquals("HDFCBK", SmsPatternLibrary.normalizeSender("HDFCBK"))
    }

    @Test
    fun `hdfc upi sent parses fully and auto-commits`() {
        val p = parsed(
            "VM-HDFCBK-S",
            "Sent Rs.545.00 From HDFC Bank A/C x4321 To SWIGGY LIMITED On 12/08/26 Ref 421234567890",
        )
        assertEquals(Money(54_500), p.txn.amount)
        assertEquals(TxnType.DEBIT, p.txn.type)
        assertEquals("4321", p.txn.accountLast4)
        assertEquals("SWIGGY LIMITED", p.txn.merchantRaw)
        assertEquals("421234567890", p.txn.reference)
        assertTrue(ConfidenceScorer.score(p.txn) >= ConfidenceThresholds.AUTO_COMMIT)
    }

    @Test
    fun `sbi upi debit parses`() {
        val p = parsed(
            "CP-SBIUPI-S",
            "Dear UPI user A/C X9876 debited by 120.0 on date 12Aug26 trf to ZOMATO Refno 519876543210. If not u? call 1800111109. -SBI",
        )
        assertEquals(Money(12_000), p.txn.amount)
        assertEquals("9876", p.txn.accountLast4)
        assertEquals("ZOMATO", p.txn.merchantRaw)
        assertEquals("519876543210", p.txn.reference)
    }

    @Test
    fun `icici debit parses`() {
        val p = parsed(
            "VM-ICICIB",
            "ICICI Bank Acct XX773 debited for Rs 2500.00 on 12-Aug-26; RELIANCEJIO credited. UPI:521100223344. Call 18002662 for dispute.",
        )
        assertEquals(Money(250_000), p.txn.amount)
        assertEquals("773", p.txn.accountLast4)
        assertEquals("RELIANCEJIO", p.txn.merchantRaw)
        assertEquals("521100223344", p.txn.reference)
    }

    @Test
    fun `axis upi debit parses merchant from upi segment`() {
        val p = parsed(
            "AX-AXISBK-S",
            "INR 349.00 debited A/c no. XX5566 12-08-26, 14:32:10 UPI/P2M/522233445566/NETFLIX ENT Not you? SMS BLOCKUPI to 918691000002",
        )
        assertEquals(Money(34_900), p.txn.amount)
        assertEquals("5566", p.txn.accountLast4)
        assertEquals("NETFLIX ENT", p.txn.merchantRaw)
        assertEquals("522233445566", p.txn.reference)
    }

    @Test
    fun `kotak sent and received parse`() {
        val sent = parsed(
            "VK-KOTAKB",
            "Sent Rs.1500.00 from Kotak Bank AC X8899 to landlord.rent@okicici on 12-08-26. UPI Ref 523344556677.",
        )
        assertEquals(TxnType.DEBIT, sent.txn.type)
        assertEquals(Money(150_000), sent.txn.amount)

        val received = parsed(
            "VK-KOTAKB",
            "Received Rs.5000.00 in your Kotak Bank AC X8899 from friend@upi on 12-08-26. UPI Ref:524455667788.",
        )
        assertEquals(TxnType.CREDIT, received.txn.type)
        assertEquals(Money(500_000), received.txn.amount)
    }

    @Test
    fun `hdfc card spend parses merchant`() {
        val p = parsed(
            "VM-HDFCBK",
            "Rs.1,299.00 spent on HDFC Bank Card x9012 at AMAZON RETAIL on 2026-08-12:18:11:00",
        )
        assertEquals(Money(129_900), p.txn.amount)
        assertEquals("9012", p.txn.accountLast4)
        assertEquals("AMAZON RETAIL", p.txn.merchantRaw)
    }

    @Test
    fun `atm withdrawal flags for transfer handling`() {
        val p = parsed(
            "VM-HDFCBK",
            "Rs.2000.00 withdrawn at ATM from a/c x4321 on 12-08-26. Avl bal Rs.15000",
        )
        assertTrue(p.isAtmWithdrawal)
        assertEquals(Money(200_000), p.txn.amount)
    }

    @Test
    fun `credit alerts parse as credit`() {
        val p = parsed(
            "VM-HDFCBK",
            "Rs.85000.00 credited to a/c x4321 on 01-08-26 by a/c linked to VPA employer@hdfcbank (UPI Ref No 520011223344).",
        )
        assertEquals(TxnType.CREDIT, p.txn.type)
        assertEquals(Money(8_500_000), p.txn.amount)
    }

    @Test
    fun `personal senders are ignored - the privacy gate is on sender shape`() {
        // A person texts from a phone number. Nothing numeric is ever read,
        // which is what spec B4 is actually protecting; the old fixed bank
        // allowlist enforced that by accident and made every unlisted bank
        // invisible along with it.
        listOf("+919812345678", "9812345678", "121", "+1-555-0100").forEach { sender ->
            val result = parser.parse(sender, "Rs.500 debited from a/c x1234 Ref 99887766", t0)
            assertTrue("personal sender was parsed: $sender → $result", result is SmsParser.Result.NotBankSender)
        }
    }

    @Test
    fun `a bank nobody wrote a pattern for is still read`() {
        val p = parsed(
            "VM-UCOBNK",
            "Your UCO Bank A/C XX7788 is debited by Rs.1,250.00 on 04-08-26 " +
                "towards SHOPPERS STOP. Ref No 601122334455.",
        )
        assertEquals(Money(125_000), p.txn.amount)
        assertEquals(TxnType.DEBIT, p.txn.type)
        assertEquals("7788", p.txn.accountLast4)
        assertEquals("601122334455", p.txn.reference)
        assertEquals("no pattern should have claimed this", null, p.patternId)
    }

    @Test
    fun `otp from bank sender is never a transaction`() {
        listOf(
            "635241 is the OTP for txn of Rs.4500.00 at AMAZON on HDFC Bank Card x9012. Do not share OTP.",
            "Use OTP 887766 to complete your payment of Rs 1200. Do not share with anyone.",
            "Your one time password is 445566 for adding beneficiary.",
        ).forEach { body ->
            val result = parser.parse("VM-HDFCBK", body, t0)
            assertTrue("OTP leaked through: $body → $result", result is SmsParser.Result.NotTransactional)
        }
    }

    @Test
    fun `promos and balance updates from bank sender are not transactions`() {
        listOf(
            "Pre-approved personal loan of Rs.5,00,000 waiting for you! Apply now.",
            "Get 10% cashback offer on your HDFC card this weekend at partner stores.",
            "Avl Bal in a/c x4321 is Rs.15,230.55 as on 12-08-26.",
        ).forEach { body ->
            val result = parser.parse("VM-HDFCBK", body, t0)
            assertTrue("Promo leaked through: $body → $result", result is SmsParser.Result.NotTransactional)
        }
    }

    @Test
    fun `a message with no account tail lands in review, never silently attributed`() {
        // With several banks in play and only one account added, "which
        // account?" is a question for the human, not a default.
        val result = parser.parse(
            "VM-FEDBNK",
            "Alert: an amount of Rs 750.00 has been debited towards AUTOPAY MUTUALFUND from your account.",
            t0,
        )
        assertTrue(result is SmsParser.Result.Parsed)
        val p = result as SmsParser.Result.Parsed
        assertNotNull(p.txn.amount)
        assertEquals(null, p.txn.accountLast4)
        val score = ConfidenceScorer.score(p.txn)
        assertTrue(
            "missing account should be review-bound, score=$score",
            score < ConfidenceThresholds.AUTO_COMMIT && score >= ConfidenceThresholds.REVIEW,
        )
    }

    @Test
    fun `an ambiguous direction verb is reviewed rather than guessed`() {
        val p = parsed(
            "VM-UCOBNK",
            "Rs.3,000.00 transferred, A/C XX4455, Ref 700011223344 on 04-08-26.",
        )
        val score = ConfidenceScorer.score(p.txn)
        assertTrue(
            "a bare 'transferred' should not auto-commit, score=$score",
            score < ConfidenceThresholds.AUTO_COMMIT,
        )
    }
}
