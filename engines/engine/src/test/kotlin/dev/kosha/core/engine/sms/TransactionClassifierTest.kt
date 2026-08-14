package dev.kosha.core.engine.sms

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bank-agnostic layer, tested without any pattern library in the way.
 *
 * The question this answers is "did money move, and which way", from the
 * message alone — no bank identity, no assumption that a balance is quoted.
 */
class TransactionClassifierTest {

    private fun extraction(body: String): TransactionClassifier.Extraction {
        val outcome = TransactionClassifier.classifyBody(body)
        assertTrue("expected a transaction, got $outcome for: $body", outcome is TransactionClassifier.Outcome.Transaction)
        return (outcome as TransactionClassifier.Outcome.Transaction).extraction
    }

    private fun rejection(body: String): TransactionClassifier.Rejection {
        val outcome = TransactionClassifier.classifyBody(body)
        assertTrue("expected a rejection, got $outcome for: $body", outcome is TransactionClassifier.Outcome.NotTransaction)
        return (outcome as TransactionClassifier.Outcome.NotTransaction).reason
    }

    @Test
    fun `alphanumeric headers are banks, numeric senders are people`() {
        listOf("VM-HDFCBK-S", "AD-ICICIB", "JD-UCOBNK", "BOIIND", "AX-AXISBK").forEach {
            assertTrue("$it should read as a bank header", TransactionClassifier.isPlausibleBankSender(it))
        }
        listOf("+919812345678", "9812345678", "121", "+15550100", "").forEach {
            assertFalse("$it should not read as a bank header", TransactionClassifier.isPlausibleBankSender(it))
        }
    }

    @Test
    fun `direction comes from the verb, in either order`() {
        assertEquals(TxnType.DEBIT, TransactionClassifier.detectDirection("a/c debited by Rs.100")?.type)
        assertEquals(TxnType.CREDIT, TransactionClassifier.detectDirection("a/c credited with Rs.100")?.type)
        assertEquals(TxnType.DEBIT, TransactionClassifier.detectDirection("Rs.100 spent on card")?.type)
        assertEquals(TxnType.DEBIT, TransactionClassifier.detectDirection("Sent Rs.100 to a friend")?.type)
        assertEquals(TxnType.CREDIT, TransactionClassifier.detectDirection("Received Rs.100")?.type)
        assertEquals(TxnType.CREDIT, TransactionClassifier.detectDirection("Refund of Rs.100 processed")?.type)
    }

    @Test
    fun `when both verbs appear the earlier one describes this account`() {
        // ICICI phrasing: "Acct XX773 debited ...; MERCHANT credited."
        val d = TransactionClassifier.detectDirection(
            "ICICI Bank Acct XX773 debited for Rs 2500.00; RELIANCEJIO credited.",
        )
        assertEquals(TxnType.DEBIT, d?.type)
        assertTrue(d!!.explicit)
    }

    @Test
    fun `a two-way verb is resolved but flagged inexplicit`() {
        val out = TransactionClassifier.detectDirection("Rs.500 transferred, A/C XX1234")
        assertEquals(TxnType.DEBIT, out?.type)
        assertFalse("a bare 'transferred' is a guess", out!!.explicit)

        val inbound = TransactionClassifier.detectDirection("Rs.500 transferred to your a/c XX1234")
        assertEquals(TxnType.CREDIT, inbound?.type)
        assertFalse(inbound!!.explicit)
    }

    @Test
    fun `no balance in the message changes nothing`() {
        val e = extraction("INR 2,499.00 debited from A/c XX3344 towards BIGBASKET. UPI Ref No 601234567890")
        assertEquals(Money(249_900), e.amount)
        assertEquals(TxnType.DEBIT, e.direction)
        assertEquals("3344", e.accountLast4)
        assertEquals("601234567890", e.reference)
        assertTrue(e.directionExplicit)
    }

    @Test
    fun `a trailing balance is not mistaken for the amount`() {
        val e = extraction("Rs.25.00 spent on Card x1234 at PAYTM on 19-07-25. Avl bal Rs.5,000.00")
        assertEquals(Money(2_500), e.amount)
    }

    @Test
    fun `a leading balance is skipped in favour of the figure that moved`() {
        val e = extraction("Avl Bal Rs.9,999.00 in a/c XX1234 after debit of Rs.500.00 on 04-08-26")
        assertEquals(Money(50_000), e.amount)
    }

    @Test
    fun `an amount with no currency marker still counts when it hangs off the verb`() {
        val e = extraction("Dear UPI user A/C X9876 debited by 120.0 on date 12Aug26 trf to ZOMATO Refno 519876543210")
        assertEquals(Money(12_000), e.amount)
        // The helpline number in the same message must never win.
        assertEquals("9876", e.accountLast4)
    }

    @Test
    fun `balance-only alerts are rejected, spends that quote a balance are not`() {
        assertEquals(
            TransactionClassifier.Rejection.BALANCE_ONLY,
            rejection("Avl bal in A/C xx1234 is Rs.5,230.55 as on 19-07-25"),
        )
        assertEquals(Money(2_500), extraction("Rs.25.00 debited from a/c x1234. Avl bal Rs.5,000.00").amount)
    }

    @Test
    fun `otps, promos, failures and scheduled debits are all rejected with a reason`() {
        assertEquals(
            TransactionClassifier.Rejection.OTP,
            rejection("635241 is the OTP to authorise Rs.4,500.00 debited from a/c x1234. Do not share this."),
        )
        assertEquals(
            TransactionClassifier.Rejection.FAILED_TRANSACTION,
            rejection("Your payment of Rs.2,000.00 to AMAZON from a/c XX1234 has failed."),
        )
        assertEquals(
            TransactionClassifier.Rejection.FUTURE_OR_REQUEST,
            rejection("Rs.1,200.00 will be debited from a/c XX1234 on 07-08-26 towards NETFLIX."),
        )
        assertEquals(
            TransactionClassifier.Rejection.NO_DIRECTION,
            rejection("Get 10% cashback offer on your card this weekend at partner stores."),
        )
    }

    @Test
    fun `account tails are recovered from every masking style banks use`() {
        assertEquals("1234", extraction("Rs.10 debited from A/C x1234 to SHOP").accountLast4)
        assertEquals("1234", extraction("Rs.10 debited from account **1234 to SHOP").accountLast4)
        assertEquals("1234", extraction("Rs.10 debited from Acct XX1234 to SHOP").accountLast4)
        assertEquals("1234", extraction("Rs.10 spent on Card no. XX1234 at SHOP").accountLast4)
        assertNull(extraction("Rs.10 debited towards SHOP from your account.").accountLast4)
    }

    @Test
    fun `the users own account is never captured as the counterparty`() {
        // "debited from HDFC Bank XX0773" names where the money LEFT. Reading
        // it as a payee produced ledger rows titled after the user's own bank
        // and a "leak report" about A C NO.
        val e = extraction("Rs.16,173.00 debited from HDFC Bank XX0773 on 10-08-25")
        assertNull("captured the source account as a merchant: ${e.merchant}", e.merchant)
        assertEquals("0773", e.accountLast4)

        assertNull(extraction("Rs.189.00 debited to a/c no XX4455 on 11-08-25").merchant)
        assertNull(extraction("Rs.500.00 debited from your account. Ref 519912345678").merchant)
    }

    @Test
    fun `a credit does name who paid`() {
        // The same "from X" phrasing IS the counterparty on money coming in.
        val e = extraction("Rs.85,000.00 credited to a/c XX1234 from ACME PAYROLL on 01-08-26")
        assertEquals("ACME PAYROLL", e.merchant)
        assertEquals(TxnType.CREDIT, e.direction)
    }

    @Test
    fun `towards phrasing names the payee`() {
        assertEquals(
            "SHOPPERS STOP",
            extraction("Your A/C XX7788 is debited by Rs.1,250.00 towards SHOPPERS STOP. Ref 601122334455").merchant,
        )
    }

    @Test
    fun `a stray currency marker is never a merchant name`() {
        // "Rs" survived as a two-letter "name" and titled ledger rows.
        listOf(
            "Rs.15,000.00 credited to a/c XX7376 by Rs on 02-08-26",
            "Credited Rs 500 to your a/c XX1234 by transfer",
        ).forEach { body ->
            val merchant = extraction(body).merchant
            assertTrue(
                "currency marker captured as merchant: $merchant",
                merchant == null || !merchant.equals("rs", ignoreCase = true),
            )
        }
    }

    @Test
    fun `atm withdrawals are flagged for the cash-transfer path`() {
        assertTrue(extraction("Rs.2000.00 withdrawn at ATM from a/c x4321 on 12-08-26").isAtmWithdrawal)
        assertFalse(extraction("Rs.2000.00 debited from a/c x4321 to SHOP on 12-08-26").isAtmWithdrawal)
    }
}
