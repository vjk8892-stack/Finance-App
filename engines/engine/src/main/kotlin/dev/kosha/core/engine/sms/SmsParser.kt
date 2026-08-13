package dev.kosha.core.engine.sms

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.ParsedTransaction
import dev.kosha.core.engine.pipeline.ParsedTransaction.Field
import dev.kosha.core.engine.pipeline.TxnType

/**
 * SMS → ParsedTransaction. Privacy rules (spec B4):
 *  - sender allowlist FIRST — non-bank senders are never parsed;
 *  - the raw body is parsed in memory and NOT retained here.
 *
 * Outcomes:
 *  - [Result.NotBankSender]: sender not on the allowlist → ignore silently.
 *  - [Result.NotTransactional]: bank sender, but OTP/promo/info → discard-with-log.
 *  - [Result.Parsed]: matched a library pattern (high confidence) or the
 *    generic fallback (low confidence → review queue, never silent drop).
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
        if (senderCode !in library.allowedSenderCodes) return Result.NotBankSender

        for (pattern in library.compiled) {
            if (senderCode !in pattern.spec.senderCodes) continue
            val match = pattern.regex.find(body) ?: continue
            val amount = match.groupOrNull("amount")?.let { Money.parseOrNull(it) } ?: continue
            val merchant = match.groupOrNull("merchant")?.trim()?.trimEnd('.', ',')
            val last4 = match.groupOrNull("last4")
            val ref = match.groupOrNull("ref")
            val base = pattern.spec.baseConfidence
            return Result.Parsed(
                txn = ParsedTransaction(
                    amount = amount,
                    type = if (pattern.spec.type == "credit") TxnType.CREDIT else TxnType.DEBIT,
                    accountLast4 = last4,
                    merchantRaw = merchant,
                    timestampMillis = receivedAtMillis,
                    reference = ref,
                    fieldConfidence = mapOf(
                        Field.AMOUNT to base,
                        Field.TYPE to base,
                        Field.ACCOUNT to if (last4 != null) base else 0.5,
                        Field.MERCHANT to if (merchant != null) base else 0.5,
                        Field.TIMESTAMP to 0.95, // receipt time, not parsed text time
                        Field.REFERENCE to if (ref != null) 1.0 else 0.5,
                    ),
                ),
                patternId = pattern.spec.id,
                bank = pattern.spec.bank,
                isAtmWithdrawal = pattern.spec.isAtmWithdrawal,
            )
        }

        return genericFallback(body, receivedAtMillis)
    }

    /**
     * Bank sender but no library pattern matched. If it still smells like a
     * money movement, extract what we can at low confidence so it lands in
     * the review queue (never silently dropped — spec Phase 2 gate). OTPs,
     * balance updates and promos return NotTransactional.
     */
    private fun genericFallback(body: String, receivedAtMillis: Long): Result {
        if (otpOrPromo.containsMatchIn(body)) {
            return Result.NotTransactional("otp-or-promo")
        }
        val verb = txnVerb.find(body) ?: return Result.NotTransactional("no-transaction-verb")
        val amountText = amountPattern.find(body)?.groupOrNull("amount")
            ?: return Result.NotTransactional("no-amount")
        val amount = Money.parseOrNull(amountText)
            ?: return Result.NotTransactional("unparseable-amount")

        val isCredit = verb.value.lowercase().let {
            it.startsWith("credit") || it.startsWith("received") || it.startsWith("deposit")
        }
        val last4 = accountPattern.find(body)?.groupOrNull("last4")
        return Result.Parsed(
            txn = ParsedTransaction(
                amount = amount,
                type = if (isCredit) TxnType.CREDIT else TxnType.DEBIT,
                accountLast4 = last4,
                merchantRaw = null,
                timestampMillis = receivedAtMillis,
                reference = null,
                fieldConfidence = mapOf(
                    Field.AMOUNT to 0.7,
                    Field.TYPE to 0.7,
                    Field.ACCOUNT to if (last4 != null) 0.7 else 0.4,
                    Field.MERCHANT to 0.3,
                    Field.TIMESTAMP to 0.95,
                    Field.REFERENCE to 0.3,
                ),
            ),
            patternId = null,
            bank = null,
            isAtmWithdrawal = false,
        )
    }

    private companion object {
        val otpOrPromo = Regex(
            "(?i)\\b(OTP|one[- ]time password|verification code|do not share|" +
                "offer|discount|cashback offer|apply now|loan approved|pre-approved|" +
                "avl bal|available balance is|avg bal|min bal|balance in a/c)\\b",
        )
        val txnVerb = Regex("(?i)\\b(debited|credited|spent|sent|received|withdrawn|paid|purchase of)\\b")
        val amountPattern = Regex("(?i)(?:Rs\\.?|INR|₹)\\s*(?<amount>[\\d,]+(?:\\.\\d{1,2})?)")
        val accountPattern = Regex("(?i)(?:a/c|acct|account|card)\\s*(?:no\\.?)?\\s*[Xx*]*(?<last4>\\d{3,4})")
    }
}

private fun MatchResult.groupOrNull(name: String): String? = try {
    groups[name]?.value?.takeIf { it.isNotBlank() }
} catch (e: IllegalArgumentException) {
    null
}
