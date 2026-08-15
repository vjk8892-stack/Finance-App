package dev.kosha.core.engine.sms

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.TxnType

/**
 * Bank-agnostic transaction detection.
 *
 * The original design keyed everything off per-bank regexes, which meant any
 * bank not in the library was invisible — fatal when someone holds accounts
 * at several banks. A transaction alert has the same skeleton everywhere:
 *
 *     <amount> + <direction verb> + optionally <account tail>, <counterparty>, <reference>
 *
 * so that skeleton is what we detect. The bank pattern library still runs
 * first as a precision layer, but it is an optimisation, not the mechanism.
 */
object TransactionClassifier {

    /** Why a message is not a transaction. Surfaced in the debug log. */
    enum class Rejection {
        NOT_BANK_SENDER,
        OTP,
        PROMOTIONAL,
        BALANCE_ONLY,
        FUTURE_OR_REQUEST,
        FAILED_TRANSACTION,
        NO_AMOUNT,
        NO_DIRECTION,
    }

    data class Extraction(
        val amount: Money,
        val direction: TxnType,
        val accountLast4: String?,
        val merchant: String?,
        val reference: String?,
        val isAtmWithdrawal: Boolean,
        /**
         * Money moving between the user's OWN accounts — a credit card bill
         * paid, a self transfer. It is a real movement but it is neither
         * income nor spending, and counting it as either makes every total
         * wrong in both directions.
         */
        val isSelfTransfer: Boolean,
        /**
         * True when a one-way verb ("debited", "credited") fixed the
         * direction; false when it was inferred from a two-way verb
         * ("transferred") and therefore deserves a human look.
         */
        val directionExplicit: Boolean,
    )

    sealed interface Outcome {
        data class Transaction(val extraction: Extraction) : Outcome
        data class NotTransaction(val reason: Rejection) : Outcome
    }

    /**
     * Bank senders are alphanumeric DLT headers ("VM-HDFCBK", "AD-ICICIB").
     * A person texts from a NUMBER. Filtering on that shape keeps the spec-B4
     * privacy promise — personal messages are never parsed — while working
     * for banks that are not in the pattern library, which a fixed allowlist
     * cannot do.
     */
    fun isPlausibleBankSender(sender: String): Boolean {
        val cleaned = sender.trim().uppercase().replace("-", "")
        if (cleaned.isEmpty()) return false
        // +919812345678, 9812345678, 121 (telco) → not a bank alert header.
        val digitsOnly = cleaned.removePrefix("+").all { it.isDigit() }
        if (digitsOnly) return false
        return cleaned.any { it.isLetter() }
    }

    fun classify(rawBody: String, sender: String): Outcome {
        if (!isPlausibleBankSender(sender)) {
            return Outcome.NotTransaction(Rejection.NOT_BANK_SENDER)
        }
        return classifyBody(rawBody)
    }

    /**
     * Everything after the sender gate. Split out so a caller that has its
     * own reason to trust the sender — a code in the pattern library, say —
     * does not have to satisfy the generic header heuristic as well.
     */
    fun classifyBody(rawBody: String): Outcome {
        val text = normalize(rawBody)

        // Order matters: each check below can appear alongside real
        // transaction words, so the most decisive disqualifiers come first.
        if (OTP.containsMatchIn(text)) return Outcome.NotTransaction(Rejection.OTP)
        if (FAILED.containsMatchIn(text)) return Outcome.NotTransaction(Rejection.FAILED_TRANSACTION)
        if (FUTURE_OR_REQUEST.containsMatchIn(text)) {
            return Outcome.NotTransaction(Rejection.FUTURE_OR_REQUEST)
        }

        val direction = detectDirection(text)
            ?: return Outcome.NotTransaction(
                // A balance alert has no movement verb at all.
                if (BALANCE_WORDS.containsMatchIn(text)) Rejection.BALANCE_ONLY else Rejection.NO_DIRECTION,
            )

        if (PROMOTIONAL.containsMatchIn(text)) {
            return Outcome.NotTransaction(Rejection.PROMOTIONAL)
        }

        val amount = extractAmount(text)
            ?: return Outcome.NotTransaction(Rejection.NO_AMOUNT)

        return Outcome.Transaction(
            Extraction(
                amount = amount,
                direction = direction.type,
                accountLast4 = extractLast4(text),
                merchant = extractMerchant(text, direction.type),
                reference = extractReference(text),
                isAtmWithdrawal = ATM.containsMatchIn(text),
                isSelfTransfer = isSelfTransfer(text),
                directionExplicit = direction.explicit,
            ),
        )
    }

    /**
     * Money moving between accounts the user already owns.
     *
     * A credit card bill payment is the clearest case and the most damaging to
     * get wrong: the card issuer texts "we have received a payment towards
     * your card", which reads exactly like income, so paying off a card
     * inflates income by the payment AND the original spending is already
     * counted — the same rupees land in the totals twice, with the wrong sign.
     * The bank at the other end says "payment towards credit card", which is a
     * spend that never happened either.
     *
     * Treating both legs as transfers is the accounting-correct answer: the
     * money left the user's net worth when the card was SPENT, not when the
     * bill was settled.
     */
    fun isSelfTransfer(text: String): Boolean =
        CARD_BILL_PAYMENT.containsMatchIn(text) || SELF_TRANSFER.containsMatchIn(text)

    /** Collapses the line breaks banks use so one-line patterns can match. */
    fun normalize(rawBody: String): String = rawBody.replace(WHITESPACE, " ").trim()

    data class Direction(val type: TxnType, val explicit: Boolean)

    /**
     * Direction from the verb, never from the presence of a balance line —
     * plenty of alerts carry no balance at all. Where a verb is directional
     * only in context ("transferred to" vs "transferred from") the
     * preposition decides, and the result is flagged inexplicit so the
     * pipeline sends it to review rather than guessing silently.
     */
    fun detectDirection(text: String): Direction? {
        val creditHit = CREDIT_VERBS.find(text)
        val debitHit = DEBIT_VERBS.find(text)

        return when {
            creditHit != null && debitHit != null ->
                // Both appear ("credited to payee, debited from your a/c"):
                // the earlier verb describes this account's movement.
                if (debitHit.range.first <= creditHit.range.first) {
                    Direction(TxnType.DEBIT, explicit = true)
                } else {
                    Direction(TxnType.CREDIT, explicit = true)
                }
            creditHit != null -> Direction(TxnType.CREDIT, explicit = true)
            debitHit != null -> Direction(TxnType.DEBIT, explicit = true)
            else -> AMBIGUOUS_VERBS.find(text)?.let {
                // "transferred from X to your a/c" is money in; a bare
                // "transferred" is money out, which is the common case.
                val credit = TRANSFER_INBOUND.containsMatchIn(text)
                Direction(if (credit) TxnType.CREDIT else TxnType.DEBIT, explicit = false)
            }
        }
    }

    /**
     * The amount that MOVED, which is not always the first figure in the
     * message — "Avl Bal Rs.9,999 after a debit of Rs.500" leads with the
     * balance. So skip any figure introduced by balance wording and take the
     * first of what remains.
     *
     * Plenty of banks omit the currency marker entirely ("A/C X9876 debited
     * by 120.0"), so a figure sitting right against the direction verb counts
     * as well. That tier is deliberately second: requiring verb adjacency is
     * what keeps a helpline number from being read as a spend.
     */
    fun extractAmount(text: String): Money? {
        val marked = AMOUNT.findAll(text).toList()
        if (marked.isNotEmpty()) {
            val movement = marked.firstOrNull { match ->
                val from = (match.range.first - BALANCE_LOOKBEHIND).coerceAtLeast(0)
                !BALANCE_WORDS.containsMatchIn(text.substring(from, match.range.first))
            }
            val chosen = movement ?: marked.first()
            val figure = chosen.groups["amount"]?.value ?: chosen.groups["amount2"]?.value
            if (figure != null) return Money.parseOrNull(figure)
        }
        return VERB_ADJACENT_AMOUNT.find(text)
            ?.groups?.get("amount")?.value
            ?.let { Money.parseOrNull(it) }
    }

    fun extractLast4(text: String): String? =
        ACCOUNT_PATTERNS
            .firstNotNullOfOrNull { it.find(text)?.groups?.get("last4")?.value }
            ?.takeLast(4)

    fun extractReference(text: String): String? =
        REFERENCE_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.groups?.get("ref")?.value?.takeIf { it.isNotBlank() }
        }

    /**
     * The counterparty — who was paid, or who paid.
     *
     * [direction] matters: on a DEBIT, "from HDFC Bank XX0773" names the
     * user's own account, not a payee, and capturing it produced ledger rows
     * titled after the bank and leak reports about "A C NO". Inbound-only
     * phrasings are therefore only tried on credits.
     */
    fun extractMerchant(text: String, direction: TxnType): String? {
        val patterns = if (direction == TxnType.CREDIT) {
            MERCHANT_PATTERNS + INBOUND_MERCHANT_PATTERNS
        } else {
            MERCHANT_PATTERNS
        }
        for (pattern in patterns) {
            val candidate = pattern.find(text)?.groups?.get("merchant")?.value
                ?.trim()
                ?.trim('.', ',', ';', '-', ':')
                ?.takeIf { it.isNotBlank() && it.length in 2..60 }
                ?: continue
            if (isNotAName(candidate)) continue
            return candidate
        }
        return null
    }

    /**
     * Rejects captures that are structure rather than a counterparty. The
     * earlier check only rejected a candidate that was ENTIRELY one noise
     * word, so "a/c no" and "HDFC Bank XX0773" sailed through and became
     * merchant names.
     */
    fun isNotAName(candidate: String): Boolean {
        if (candidate.all { !it.isLetter() }) return true
        if (DATE_LIKE.containsMatchIn(candidate)) return true
        if (ACCOUNT_WORDS.containsMatchIn(candidate)) return true
        if (BANK_WORDS.containsMatchIn(candidate)) return true
        // "XX0773", "1234567890" — an identifier, whatever else is around it.
        if (MASKED_TAIL.containsMatchIn(candidate)) return true
        // A currency marker that lost its amount ("Rs", "INR") is not a payee;
        // it slipped through as a two-letter "name" and titled ledger rows.
        if (CURRENCY_ONLY.matches(candidate)) return true
        // Needs at least a couple of letters to be a name at all.
        return candidate.count { it.isLetter() } < 2
    }

    private val WHITESPACE = Regex("\\s+")

    private val OTP = Regex(
        "(?i)\\b(OTP|one[- ]time\\s*password|verification code|do not share this|" +
            "secure code|login code)\\b",
    )
    private val FAILED = Regex(
        "(?i)\\b(failed|declined|unsuccessful|could not be processed|reversed and credited)\\b",
    )
    /** Scheduled or requested money has not moved yet. */
    private val FUTURE_OR_REQUEST = Regex(
        "(?i)\\b(will be debited|will be deducted|is due|due on|due date|payment request|" +
            "collect request|requesting|has requested|autopay.{0,20}scheduled|" +
            "e-?mandate|statement is ready|bill generated)\\b",
    )
    private val PROMOTIONAL = Regex(
        "(?i)\\b(cashback offer|apply now|pre-?approved|loan offer|limited period|" +
            "click here|t&c apply|congratulations|win |voucher)\\b",
    )
    private val BALANCE_WORDS = Regex(
        "(?i)\\b(avl bal|available balance|avg bal|min bal|closing balance|balance in)\\b",
    )

    // Closed at both ends: an unterminated "cashback of" happily matched
    // "cashback offer" and turned a promo into a credit.
    private val DEBIT_VERBS = Regex(
        "(?i)\\b(?:debited|spent|withdrawn|paid to|payment of|purchase of|deducted|" +
            "debit of|sent|w/d|dr)\\b",
    )
    private val CREDIT_VERBS = Regex(
        "(?i)\\b(?:credited|received|deposited|refund(?:ed|s)?|cashback of|" +
            "credit of|added to|cr)\\b",
    )
    private val AMBIGUOUS_VERBS = Regex("(?i)\\b(transferred|transfer of|txn of)\\b")
    private val TRANSFER_INBOUND = Regex(
        "(?i)\\bto\\s+(?:your|ur)\\s+(?:a/c|ac|acct|account|card)\\b|\\btransferred\\s+to\\s+you\\b",
    )

    private val ATM = Regex("(?i)\\b(atm|cash withdrawal|w/d)\\b")

    /**
     * Paying OFF a card, which is not spending. The hard part is that a card
     * spend says "card" too — "Paid Rs.500 to SWIGGY using your HDFC Bank Card"
     * matched an earlier version of this through the bare `to` alternation and
     * a payee sitting in the gap, which quietly dropped a real expense out of
     * the month total. So the card has to be the TARGET of the payment, never
     * merely the instrument: only `towards`/`toward` may span an arbitrary gap,
     * and after a plain `to`/`for` at most two words may stand between it and
     * the card, which is room for a bank name and nothing else. A payee plus an
     * instrument phrase ("to SWIGGY using your HDFC Bank Card") cannot fit.
     */
    private val CARD_BILL_PAYMENT = Regex(
        "(?i)\\b(payment|paid|received|credited)\\b.{0,40}\\b(towards|toward)\\b.{0,25}" +
            "\\b(credit\\s*card|cc|card)\\b|" +
            "\\b(payment|paid|received)\\b.{0,25}\\b(to|for)\\s+(?:your\\s+|ur\\s+|the\\s+)?" +
            "(?:[a-z]{2,12}\\s+){0,2}(credit\\s*card|card)\\b|" +
            "\\b(credit\\s*card|card)\\s*(bill|payment|paymt|pymt)\\b|" +
            "\\bcard\\s*payment\\s*(received|made|successful)\\b|" +
            "\\bbill\\s*payment\\s*(received|towards)\\b",
    )

    /** Explicit self/own-account movement. */
    private val SELF_TRANSFER = Regex(
        "(?i)\\b(self[- ]transfer|own account|to your own|between your accounts|" +
            "transfer to self|self a/c)\\b",
    )
    private val DATE_LIKE = Regex("\\d{1,2}[-/][A-Za-z0-9]{2,4}[-/]\\d{2,4}|\\d{2}[-/]\\d{2}")

    /** Words that mean the capture is describing an account, not a payee. */
    private val ACCOUNT_WORDS = Regex(
        "(?i)\\b(a/?c|ac|acct|account|a c no|card|no\\.?|number|wallet)\\b",
    )
    private val BANK_WORDS = Regex(
        "(?i)\\b(bank|hdfc|icici|sbi|axis|kotak|yes ?bank|idfc|indusind|federal|" +
            "canara|union|pnb|bob|boi|uco|iob|rbl|dbs|citi|hsbc|standard chartered|" +
            "au small|bandhan|equitas|ujjivan|paytm payments|airtel payments)\\b",
    )
    private val MASKED_TAIL = Regex("(?i)[Xx*]{2,}\\s*\\d{3,}|\\b\\d{6,}\\b")

    /** Whole capture is just a currency marker or filler. */
    private val CURRENCY_ONLY = Regex(
        "(?i)(rs|inr|₹|amt|amount|txn|transaction|payment|debit|credit|" +
            "you|us|it|the|and|for|via|by|to|from)[.\\s]*",
    )

    /** How far back to look for balance wording introducing a figure. */
    private const val BALANCE_LOOKBEHIND = 24

    private val AMOUNT = Regex(
        "(?i)(?:Rs\\.?|INR|₹)\\s*(?<amount>[\\d,]+(?:\\.\\d{1,2})?)|" +
            "(?<amount2>[\\d,]+\\.\\d{2})\\s*(?:Rs\\.?|INR|₹)",
    )

    /** Unmarked figure hanging off the verb: "debited by 120.0", "credit of 500". */
    private val VERB_ADJACENT_AMOUNT = Regex(
        "(?i)\\b(?:debited|credited|withdrawn|spent|sent|received|deposited|paid|" +
            "deducted|transferred|debit|credit)\\s*(?:by|for|of|with|amount)?\\s*" +
            "(?<amount>\\d[\\d,]*(?:\\.\\d{1,2})?)\\b",
    )

    /**
     * Banks mask a varying number of digits. Canara sends "Acct XXXXX07683" —
     * FIVE trailing digits — and a `\d{3,4}` capture simply failed to match it,
     * with two consequences that both looked like something else.
     *
     * The transaction could not be attributed to any account, so it went to
     * the review queue instead of the ledger and read as "not captured". And
     * because the scan then continued past the user's own account, the next
     * tail in the message matched instead — which in "from a/c XXXXX07683 to
     * a/c XXXXX1234" is the PAYEE's account. Silently filing a transaction
     * against the wrong account is the worse of the two.
     *
     * Up to eight digits are captured now and the last four taken, which is
     * what every downstream comparison actually uses.
     */
    private val ACCOUNT_PATTERNS = listOf(
        Regex("(?i)(?:a/c|ac|acct|account|card)\\s*(?:no\\.?|number)?\\s*[Xx*]{0,8}\\s*(?<last4>\\d{3,8})\\b"),
        Regex("(?i)[Xx*]{2,}\\s*(?<last4>\\d{3,8})\\b"),
    )

    private val MERCHANT_PATTERNS = listOf(
        Regex("(?i)\\bto\\s+VPA\\s+(?<merchant>[^\\s,;]+)"),
        Regex("(?i)\\bVPA\\s+(?<merchant>[^\\s,;]+)"),
        Regex("(?i)\\btrf\\s+to\\s+(?<merchant>.+?)\\s+(?:Refno|Ref\\b)"),
        Regex("(?i)\\bat\\s+(?<merchant>.+?)\\s+on\\b"),
        Regex("(?i)\\bto\\s+(?<merchant>.+?)\\s+on\\b"),
        Regex("(?i)\\bto\\s+(?<merchant>[^.;]{2,40}?)\\s*(?:[.;]|\\bRef\\b)"),
        Regex("(?i)\\btowards\\s+(?<merchant>[^.;]{2,40}?)\\s*(?:[.;]|\\bon\\b|\\bRef\\b|$)"),
        // ICICI: "Acct XX773 debited ...; RELIANCEJIO credited." The payee is
        // the one being credited, so this reads correctly on a debit too.
        Regex("(?i);\\s*(?<merchant>.+?)\\s+credited\\b"),
        Regex("(?i)\\bInfo:?\\s*(?<merchant>[^.;]{2,40})"),
    )

    /**
     * Only meaningful on a credit. On a debit, "from X" is the account the
     * money left, and treating it as the payee is what produced ledger rows
     * named after the user's own bank.
     */
    private val INBOUND_MERCHANT_PATTERNS = listOf(
        Regex("(?i)\\bfrom\\s+(?<merchant>.+?)\\s+on\\b"),
        // "Cr. INR 5,000.00 on 12/08/26 from RAMESH K; UPI: ..." — the name is
        // ended by a separator, not by the word "on", so the pattern above
        // never fired and money received from a person arrived unnamed.
        Regex("(?i)\\bfrom\\s+(?<merchant>[^.;,]{2,40}?)\\s*(?:[.;,]|\\bRef\\b|\\bUPI\\b|$)"),
        Regex("(?i)\\bby\\s+(?<merchant>[^.;]{2,40}?)\\s*(?:[.;]|\\bon\\b|$)"),
    )

    private val REFERENCE_PATTERNS = listOf(
        Regex("(?i)\\bUPI\\s*Ref(?:erence)?(?:\\s*No)?\\.?:?\\s*(?<ref>\\d{6,})"),
        Regex("(?i)\\breference\\s+(?:number|no)\\.?\\s*(?:is)?\\s*(?<ref>\\d{6,})"),
        Regex("(?i)\\bRef(?:no|\\s*No)?\\.?:?\\s*(?<ref>\\d{6,})"),
        Regex("(?i)\\bRRN\\.?:?\\s*(?<ref>\\d{6,})"),
        Regex("(?i)\\bUPI:?\\s*(?<ref>\\d{9,})"),
        Regex("(?i)\\b(?:txn|transaction)\\s*(?:id|no)\\.?:?\\s*(?<ref>[A-Z0-9]{8,25})"),
    )
}
