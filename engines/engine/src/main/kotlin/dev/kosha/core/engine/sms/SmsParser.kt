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

        // Bank SMS are heavily multi-line ("Sent Rs.40\nFrom HDFC Bank A/C
        // x1234\nTo ZOMATO\nOn 15/07/25"). Kotlin's `.` does not cross a
        // newline, so every pattern using `.+?` silently failed on real
        // messages and everything fell through to the low-confidence
        // fallback. Collapsing whitespace first makes one-line patterns work
        // against any line layout the bank happens to use.
        val text = body.replace(WHITESPACE, " ").trim()

        for (pattern in library.compiled) {
            if (senderCode !in pattern.spec.senderCodes) continue
            val match = pattern.regex.find(text) ?: continue
            val amount = match.groupOrNull("amount")?.let { Money.parseOrNull(it) } ?: continue

            // A pattern that identifies the message is not obliged to capture
            // every field — banks move the reference around, and some put the
            // account tail outside any keyword. Fill the gaps with the
            // generic extractors instead of demanding one perfect regex per
            // format, which is what left references null on real messages.
            val merchant = match.groupOrNull("merchant")?.trim()?.trimEnd('.', ',')
                ?: extractMerchant(text)
            val last4 = match.groupOrNull("last4") ?: extractLast4(text)
            val ref = match.groupOrNull("ref") ?: extractReference(text)
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

        return genericFallback(text, receivedAtMillis)
    }

    /**
     * Bank sender but no library pattern matched. If it still smells like a
     * money movement, extract what we can at low confidence so it lands in
     * the review queue (never silently dropped — spec Phase 2 gate). OTPs,
     * balance updates and promos return NotTransactional.
     */
    private fun genericFallback(body: String, receivedAtMillis: Long): Result {
        // An OTP is definitive regardless of anything else in the message.
        if (otpPattern.containsMatchIn(body)) {
            return Result.NotTransactional("otp")
        }

        val verb = txnVerb.find(body)
        if (verb == null) {
            // No movement verb: a balance alert or a promo, not a spend.
            // Order matters — real debit alerts routinely END with
            // "Avl bal Rs.X", so a balance mention only disqualifies a
            // message when nothing actually moved.
            val reason = if (balanceOnly.containsMatchIn(body)) "balance-alert" else "no-transaction-verb"
            return Result.NotTransactional(reason)
        }
        if (promoPattern.containsMatchIn(body)) {
            return Result.NotTransactional("promo")
        }

        val amountText = amountPattern.find(body)?.groupOrNull("amount")
            ?: return Result.NotTransactional("no-amount")
        val amount = Money.parseOrNull(amountText)
            ?: return Result.NotTransactional("unparseable-amount")

        val isCredit = verb.value.lowercase().let {
            it.startsWith("credit") || it.startsWith("received") || it.startsWith("deposit")
        }
        val last4 = extractLast4(body)
        val merchant = extractMerchant(body)
        val reference = extractReference(body)

        // Detail actually found lifts confidence; a bare amount stays low.
        val detail = listOfNotNull(last4, merchant, reference).size
        val base = when (detail) {
            3 -> 0.85
            2 -> 0.78
            1 -> 0.72
            else -> 0.65
        }
        return Result.Parsed(
            txn = ParsedTransaction(
                amount = amount,
                type = if (isCredit) TxnType.CREDIT else TxnType.DEBIT,
                accountLast4 = last4,
                merchantRaw = merchant,
                timestampMillis = receivedAtMillis,
                reference = reference,
                fieldConfidence = mapOf(
                    Field.AMOUNT to base,
                    Field.TYPE to base,
                    Field.ACCOUNT to if (last4 != null) base else 0.45,
                    Field.MERCHANT to if (merchant != null) base else 0.4,
                    Field.TIMESTAMP to 0.95,
                    Field.REFERENCE to if (reference != null) 0.9 else 0.4,
                ),
            ),
            patternId = null,
            bank = null,
            isAtmWithdrawal = atmPattern.containsMatchIn(body),
        )
    }

    /** Who the money went to/came from, across the phrasings banks actually use. */
    private fun extractMerchant(body: String): String? {
        for (pattern in merchantPatterns) {
            val candidate = pattern.find(body)?.groupOrNull("merchant")
                ?.trim()
                ?.trim('.', ',', ';', '-')
                ?.takeIf { it.isNotBlank() && it.length in 2..60 }
                ?: continue
            // Reject captures that are really dates, amounts or bare numbers.
            if (candidate.all { it.isDigit() }) continue
            if (datePattern.matches(candidate)) continue
            return candidate
        }
        return null
    }

    private fun extractReference(body: String): String? =
        referencePatterns.firstNotNullOfOrNull { it.find(body)?.groupOrNull("ref") }

    /** The account tail, with or without an "a/c" style keyword in front. */
    private fun extractLast4(body: String): String? =
        accountPatterns.firstNotNullOfOrNull { it.find(body)?.groupOrNull("last4") }

    private companion object {
        val otpPattern = Regex(
            "(?i)\\b(OTP|one[- ]time password|verification code|do not share this)\\b",
        )
        val promoPattern = Regex(
            "(?i)\\b(cashback offer|apply now|loan approved|pre-approved|" +
                "limited period|click here|t&c apply|congratulations)\\b",
        )
        /** Only meaningful when no transaction verb is present. */
        val balanceOnly = Regex(
            "(?i)\\b(avl bal|available balance|avg bal|min bal|balance in a/c|statement)\\b",
        )
        val txnVerb = Regex(
            "(?i)\\b(debited|credited|spent|sent|received|withdrawn|paid|" +
                "purchase of|transferred|deducted)\\b",
        )
        val amountPattern = Regex("(?i)(?:Rs\\.?|INR|₹)\\s*(?<amount>[\\d,]+(?:\\.\\d{1,2})?)")
        val accountPatterns = listOf(
            // "A/C x1234", "account **1234", "Card no. XX1234"
            Regex("(?i)(?:a/c|ac|acct|account|card)\\s*(?:no\\.?)?\\s*[Xx*]{0,6}\\s*(?<last4>\\d{3,4})\\b"),
            // "Federal Bank XX1234" — masked tail with no keyword in front.
            // Requires the mask characters so a bare number is never taken.
            Regex("(?i)[Xx*]{2,}\\s*(?<last4>\\d{3,4})\\b"),
        )
        val atmPattern = Regex("(?i)\\b(atm|cash withdrawal|w/d)\\b")
        val datePattern = Regex("\\d{1,2}[-/][A-Za-z0-9]{2,4}[-/]\\d{2,4}")

        /** Ordered most-specific first. */
        val merchantPatterns = listOf(
            // "to VPA merchant@ybl" / "linked to VPA name@bank"
            Regex("(?i)\\bto\\s+VPA\\s+(?<merchant>[^\\s,;]+)"),
            Regex("(?i)\\bVPA\\s+(?<merchant>[^\\s,;]+)"),
            // SBI: "trf to ZOMATO Refno 123"
            Regex("(?i)\\btrf\\s+to\\s+(?<merchant>.+?)\\s+(?:Refno|Ref\\b)"),
            // "at AMAZON RETAIL on 12-08-25"
            Regex("(?i)\\bat\\s+(?<merchant>.+?)\\s+on\\b"),
            // "To ZOMATO On 15/07/25"  (HDFC UPI, once whitespace is collapsed)
            Regex("(?i)\\bto\\s+(?<merchant>.+?)\\s+on\\b"),
            // "to JOHN DOE. Ref 123"
            Regex("(?i)\\bto\\s+(?<merchant>[^.;]{2,40}?)\\s*(?:[.;]|\\bRef\\b)"),
            // Credits: "from JOHN DOE on"
            Regex("(?i)\\bfrom\\s+(?<merchant>.+?)\\s+on\\b"),
            // ICICI: "; MERCHANT credited"
            Regex("(?i);\\s*(?<merchant>.+?)\\s+credited\\b"),
            // "Info: NEFT-SALARY-ACME" / "Info:UPI/..."
            Regex("(?i)\\bInfo:?\\s*(?<merchant>[^.;]{2,40})"),
        )

        val referencePatterns = listOf(
            Regex("(?i)\\bUPI\\s*Ref(?:erence)?(?:\\s*No)?\\.?:?\\s*(?<ref>\\d{6,})"),
            Regex("(?i)\\breference\\s+number\\s+is\\s+(?<ref>\\d{6,})"),
            Regex("(?i)\\bRef(?:no|\\s*No)?\\.?:?\\s*(?<ref>\\d{6,})"),
            Regex("(?i)\\bRRN\\.?:?\\s*(?<ref>\\d{6,})"),
            Regex("(?i)\\bUPI:?\\s*(?<ref>\\d{9,})"),
            Regex("(?i)\\b(?:txn|transaction)\\s*id\\.?:?\\s*(?<ref>[A-Z0-9]{8,25})"),
        )

        val WHITESPACE = Regex("\\s+")
    }
}

private fun MatchResult.groupOrNull(name: String): String? = try {
    groups[name]?.value?.takeIf { it.isNotBlank() }
} catch (e: IllegalArgumentException) {
    null
}
