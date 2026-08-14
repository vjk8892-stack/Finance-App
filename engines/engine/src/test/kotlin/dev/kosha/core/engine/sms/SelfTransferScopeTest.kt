package dev.kosha.core.engine.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blast radius of `isSelfTransfer`.
 *
 * A message flagged here is force-filed into Transfers, and Transfers is left
 * out of the month total, the savings gap and the charts. That is correct for
 * money that only moved between the user's OWN pockets — but if it ever fires
 * on money that actually left, the expense silently disappears from every
 * total in the app while the balance still drops. There is no louder failure
 * mode than a number that is quietly too small, so the negative cases below
 * matter more than the positive ones.
 */
class SelfTransferScopeTest {

    private fun self(body: String) = TransactionClassifier.isSelfTransfer(
        body.replace(Regex("\\s+"), " "),
    )

    @Test
    fun moneyLeavingToSomeoneElseIsNeverATransfer() {
        val payments = listOf(
            // The user's own ₹15,000 case: their bank to ANOTHER account.
            "Rs.15000.00 debited from A/c XX1234 on 12-Aug-25 to RAJESH KUMAR UPI Ref 5219",
            "INR 15,000 transferred from your A/c XX1234 to A/c XX9988 via IMPS Ref 771",
            "Rs 8000 sent to priya@okhdfc from A/c XX1234 UPI Ref 8891",
            "Your a/c XX1234 debited Rs.15000 by NEFT to SHARMA TRADERS",
            "Rs.45000 debited towards RENT to LANDLORD via NEFT",
            // The card is the INSTRUMENT here, not the target. This is the one
            // that regressed: a payee in the gap plus a trailing "Card" read as
            // a bill payment and took a real spend out of the totals.
            "Paid Rs.500 to SWIGGY using your HDFC Bank Card ending 4321",
            "Payment of Rs.2400 to BIG BAZAAR made with your ICICI Credit Card",
            "Rs.1299 spent on your ICICI Credit Card at AMAZON",
            "Rs.250 paid to UBER via UPI from your card linked a/c",
            "Rs.3500 paid for FLIGHT BOOKING on your Axis card XX9012",
            "Payment received for ORDER 8821 to MERCHANT XYZ",
        )
        payments.forEach { assertFalse("should NOT be a transfer: $it", self(it)) }
    }

    @Test
    fun payingOffYourOwnCardIsATransfer() {
        val transfers = listOf(
            "Payment of Rs.6386.45 received towards your HDFC Credit Card XX4321",
            "Thank you. Rs.6386.45 received towards your credit card ending 4321",
            "Rs.6386.45 credit card bill payment successful",
            "Your credit card payment of Rs.6386 has been received",
            "Payment of Rs.6386 to your HDFC Bank Credit Card is successful",
            "Bill payment received for card XX4321 Rs.6386",
        )
        transfers.forEach { assertTrue("should be a transfer: $it", self(it)) }
    }

    @Test
    fun movingMoneyBetweenYourOwnAccountsIsATransfer() {
        val transfers = listOf(
            "Rs.5000 self transfer to your own account XX9988 completed",
            "INR 20000 moved between your accounts XX1234 and XX9988",
            "Rs.7500 transfer to self a/c XX9988 successful",
        )
        transfers.forEach { assertTrue("should be a transfer: $it", self(it)) }
    }
}
