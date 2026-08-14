package dev.kosha.core.engine.sms

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.ParsedTransaction
import dev.kosha.core.engine.pipeline.ParsedTransaction.Field
import dev.kosha.core.engine.pipeline.TxnType

/**
 * SMS → ParsedTransaction.
 *
 * The mechanism is [TransactionClassifier]: whether a message is a
 * transaction, and which way the money moved, is decided from the message
 * itself, not from knowing which bank sent it. That matters because people
 * hold accounts at banks nobody wrote a pattern for, and because a bank can
 * reword its alerts overnight.
 *
 * [SmsPatternLibrary] is kept as a PRECISION layer on top: when a curated
 * pattern for this sender also matches, its capture groups win over the
 * generic extractors and its confidence replaces the generic one, and we get
 * to name the bank. A pattern can no longer decide that a message IS a
 * transaction on its own, and its absence can no longer hide one.
 *
 * Privacy rules (spec B4) are unchanged: non-bank senders are never parsed,
 * and the raw body is not retained here.
 *
 * Outcomes:
 *  - [Result.NotBankSender]: personal message → ignore silently, no logging.
 *  - [Result.NotTransactional]: bank sender, but OTP/promo/balance/scheduled.
 *  - [Result.Parsed]: money moved. Confidence decides commit vs review.
 */
class SmsParser(private val library: SmsPatternLibrary) {

    sealed interface Result {
        data object NotBankSender : Result
        data class NotTransactional(val reason: String) : Result
        data class Parsed(
            val txn: ParsedTransaction,
            val patternId: String?,
            val bank: String?,
            val isAtmWithdrawal: Boolean,
        ) : Result
    }

    fun parse(sender: String, body: String, receivedAtMillis: Long): Result {
        val senderCode = SmsPatternLibrary.normalizeSender(sender)
        val knownBank = senderCode in library.allowedSenderCodes
        // Either we recognise the code, or it has the shape of a DLT header.
        // A numeric sender is a person, and stays unread (spec B4).
        if (!knownBank && !TransactionClassifier.isPlausibleBankSender(sender)) {
            return Result.NotBankSender
        }

        // Bank SMS are heavily multi-line ("Sent Rs.40\nFrom HDFC Bank A/C
        // x1234\nTo ZOMATO\nOn 15/07/25"). Kotlin's `.` does not cross a
        // newline, so every pattern using `.+?` silently failed on real
        // messages. Collapsing whitespace first makes one-line patterns work
        // against any line layout the bank happens to use.
        val text = TransactionClassifier.normalize(body)

        val extraction = when (val outcome = TransactionClassifier.classifyBody(text)) {
            is TransactionClassifier.Outcome.NotTransaction ->
                return Result.NotTransactional(outcome.reason.wireName())
            is TransactionClassifier.Outcome.Transaction -> outcome.extraction
        }

        val hit = library.compiled
            .filter { senderCode in it.spec.senderCodes }
            .firstNotNullOfOrNull { candidate -> candidate.regex.find(text)?.let { candidate to it } }
        val pattern = hit?.first
        val match = hit?.second

        // Curated captures win where they exist; the classifier fills the
        // rest. Banks move the reference around and some put the account tail
        // outside any keyword, so neither source is complete on its own.
        val amount = match?.groupOrNull("amount")?.let { Money.parseOrNull(it) } ?: extraction.amount
        val last4 = match?.groupOrNull("last4") ?: extraction.accountLast4
        val merchant = match?.groupOrNull("merchant")?.trim()?.trim('.', ',', ';')
            ?: extraction.merchant
        val reference = match?.groupOrNull("ref") ?: extraction.reference

        // A sender-specific pattern that matched is stronger evidence of
        // direction than a generic verb scan, so it overrides.
        val type = when (pattern?.spec?.type) {
            "credit" -> TxnType.CREDIT
            "debit" -> TxnType.DEBIT
            else -> extraction.direction
        }
        val directionCertain = pattern != null || extraction.directionExplicit
        val amountConfidence = pattern?.spec?.baseConfidence ?: GENERIC_AMOUNT

        return Result.Parsed(
            txn = ParsedTransaction(
                amount = amount,
                type = type,
                accountLast4 = last4,
                merchantRaw = merchant,
                timestampMillis = receivedAtMillis,
                reference = reference,
                fieldConfidence = mapOf(
                    Field.AMOUNT to amountConfidence,
                    Field.TYPE to if (directionCertain) amountConfidence else AMBIGUOUS_DIRECTION,
                    // No tail means we cannot say WHICH of the user's
                    // accounts moved — with several banks in play that is a
                    // question for the human, so it is scored down hard
                    // enough to land in review.
                    Field.ACCOUNT to if (last4 != null) FIELD_PRESENT else NO_ACCOUNT_TAIL,
                    // A missing counterparty is usually a message that names
                    // none (a salary credit, a bank transfer) rather than a
                    // failed read, so the penalty is mild.
                    Field.MERCHANT to if (merchant != null) FIELD_PRESENT else NO_MERCHANT,
                    Field.TIMESTAMP to 0.95, // receipt time, not parsed text time
                    Field.REFERENCE to if (reference != null) 1.0 else 0.5,
                ),
            ),
            patternId = pattern?.spec?.id,
            bank = pattern?.spec?.bank,
            isAtmWithdrawal = pattern?.spec?.isAtmWithdrawal ?: extraction.isAtmWithdrawal,
        )
    }

    private companion object {
        /** The currency-marked amount regex is reliable on its own. */
        const val GENERIC_AMOUNT = 0.93
        const val FIELD_PRESENT = 0.95
        const val AMBIGUOUS_DIRECTION = 0.6
        const val NO_ACCOUNT_TAIL = 0.7
        const val NO_MERCHANT = 0.82
    }
}

/** Stable, loggable names for the discard-with-log trail (spec B3). */
private fun TransactionClassifier.Rejection.wireName(): String = when (this) {
    TransactionClassifier.Rejection.NOT_BANK_SENDER -> "not-bank-sender"
    TransactionClassifier.Rejection.OTP -> "otp"
    TransactionClassifier.Rejection.PROMOTIONAL -> "promo"
    TransactionClassifier.Rejection.BALANCE_ONLY -> "balance-alert"
    TransactionClassifier.Rejection.FUTURE_OR_REQUEST -> "not-yet-moved"
    TransactionClassifier.Rejection.FAILED_TRANSACTION -> "failed-transaction"
    TransactionClassifier.Rejection.NO_AMOUNT -> "no-amount"
    TransactionClassifier.Rejection.NO_DIRECTION -> "no-transaction-verb"
}

private fun MatchResult.groupOrNull(name: String): String? = try {
    groups[name]?.value?.takeIf { it.isNotBlank() }
} catch (e: IllegalArgumentException) {
    null
}
