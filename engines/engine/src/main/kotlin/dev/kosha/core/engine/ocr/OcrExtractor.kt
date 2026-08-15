package dev.kosha.core.engine.ocr

import dev.kosha.core.common.Money
import dev.kosha.core.engine.pipeline.ParsedTransaction
import dev.kosha.core.engine.pipeline.ParsedTransaction.Field
import dev.kosha.core.engine.pipeline.TxnType

/**
 * OCR text → ParsedTransaction (spec Phase 4). Pure Kotlin: ML Kit does the
 * recognition on-device and hands us plain text lines; all interpretation
 * happens here so it is exhaustively unit-testable.
 *
 * Two families:
 *  - UPI app screenshots (PhonePe / GPay / Paytm): highly structured, anchor
 *    keywords + a UTR → high confidence.
 *  - Generic printed bills: total-line heuristics → mid confidence, so they
 *    default to the review queue (spec F risk register).
 */
class OcrExtractor(private val templates: OcrTemplateLibrary = OcrTemplateLibrary.bundled()) {

    data class Extraction(
        val txn: ParsedTransaction,
        val templateId: String,
        val appLabel: String,
        /** Item lines from an itemized bill, when present. */
        val lineItems: List<LineItem> = emptyList(),
        /** Warranty prompt hint: product-looking bill with a named item. */
        val warrantyCandidate: String? = null,
    )

    data class LineItem(val name: String, val amount: Money)

    /**
     * [liveCapture] distinguishes the Scan tab (camera, shot moments after
     * paying — capture time is a good proxy for transaction time) from the
     * Import tab (gallery screenshots of arbitrary age). Imported captures
     * score lower on TIMESTAMP so they land in review rather than
     * auto-committing with a wrong date.
     */
    fun extract(rawText: String, capturedAtMillis: Long, liveCapture: Boolean = true): Extraction? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val upper = lines.joinToString("\n") { it.uppercase() }

        val template = templates.templates.firstOrNull { t ->
            t.anchorKeywords.count { upper.contains(it.uppercase()) } >= t.minAnchorHits
        } ?: templates.genericBill

        // A payment receipt is recognisable by its SHAPE — a payee label and a
        // reference or a VPA — not by whose logo is on it. Routing on brand
        // templates alone sent every unrecognised app's receipt down the
        // printed-bill path, which reads no payee, no reference and no card.
        val hasPayeeLabel = lines.any { PAID_TO_LABEL.containsMatchIn(it) } ||
            lines.any { RECEIVED_FROM_LABEL.containsMatchIn(it) }
        val looksLikeReceipt = hasPayeeLabel &&
            (lines.any { REFERENCE_LABEL.containsMatchIn(it) } || lines.any { it.contains("@") })

        return when {
            template.appLabel != "generic-bill" ->
                extractUpiScreenshot(lines, template, capturedAtMillis, liveCapture)
            looksLikeReceipt ->
                extractUpiScreenshot(lines, templates.genericBill, capturedAtMillis, liveCapture)
            else -> extractBill(lines, template, capturedAtMillis, liveCapture)
        }
    }

    // ---------------------------------------------------------------------
    // Structure, not branding.
    //
    // Every payment receipt lays out the same way regardless of which app drew
    // it: a hero amount, a "to"/"from" label, a reference, a date. What varies
    // is the wording and — crucially — whether the VALUE sits on the same line
    // as its label or on the next one. Real receipts put "to:" on its own line
    // and the payee underneath, which is why a same-line-only reading returned
    // the screen heading as the merchant.
    // ---------------------------------------------------------------------

    /**
     * The value belonging to [label]: the remainder of the label's own line if
     * there is one, otherwise the next line that is not chrome.
     */
    internal fun labelledValue(lines: List<String>, label: Regex): String? {
        lines.forEachIndexed { index, line ->
            val match = label.find(line) ?: return@forEachIndexed
            val sameLine = line.substring(match.range.last + 1).trim().trim(':', '-', '\u2013').trim()
            if (sameLine.length >= 2 && !isChrome(sameLine) && !isInitialsBlob(sameLine)) return sameLine
            // Value on the following line — the common layout on phone
            // receipts. Collected rather than returned on first hit, because
            // the line directly under "Paid to" is often the AVATAR: a two- or
            // three-letter monogram drawn from the name that follows it. "MG"
            // is not who you paid, Mani Gopalgowda is.
            val candidates = mutableListOf<String>()
            for (next in index + 1 until minOf(index + 4, lines.size)) {
                val candidate = lines[next].trim()
                if (candidate.length >= 2 && !isChrome(candidate)) candidates += candidate
            }
            candidates.firstOrNull { !isInitialsBlob(it) }?.let { return it }
            candidates.firstOrNull()?.let { return it }
        }
        return null
    }

    /**
     * The identifier belonging to [label]. Separate from [labelledValue]
     * because a reference is usually ALL DIGITS, which the chrome filter
     * correctly rejects for a payee and must not reject here — reading
     * "transaction ID:" with the payee rule returned the next brand name on
     * the screen instead of the number underneath the label.
     */
    internal fun labelledIdentifier(lines: List<String>, label: Regex): String? {
        lines.forEachIndexed { index, line ->
            val match = label.find(line) ?: return@forEachIndexed
            val sameLine = line.substring(match.range.last + 1).trim().trim(':', '-', '#').trim()
            REFERENCE_VALUE.find(sameLine)?.let { return it.value }
            for (next in index + 1 until minOf(index + 3, lines.size)) {
                val candidate = lines[next].trim().trim(':', '-', '#').trim()
                // Must be the identifier ITSELF, not a line that merely
                // contains one, or a sentence would qualify.
                if (REFERENCE_VALUE.matches(candidate)) return candidate
            }
        }
        return null
    }

    /**
     * An avatar monogram — the initials circle a payment app draws beside a
     * name. Short, all capitals, no spaces. Rejected only when a fuller
     * candidate exists, so a genuinely short payee name is not lost.
     */
    internal fun isInitialsBlob(line: String): Boolean {
        val t = line.trim()
        return t.length in 1..3 && t.none { it.isWhitespace() } && t.all { it.isUpperCase() }
    }

    /**
     * Lines that are never a payee: screen headings, app branding, VPAs, card
     * masks, bare identifiers and reward blurbs. Without this the first short
     * line on the screen — "payment details" — became the merchant name.
     */
    internal fun isChrome(line: String): Boolean {
        val t = line.trim()
        if (t.length < 2) return true
        if (t.contains("@")) return true // a VPA is an address, not a name
        if (t.none { it.isLetter() }) return true
        if (MASKED_ID.containsMatchIn(t)) return true
        if (CHROME_WORDS.containsMatchIn(t)) return true
        if (anyAmount(t) != null) return true
        return false
    }

    /**
     * The hero figure on a receipt that carries NO usable currency marker.
     *
     * Every amount pattern required a clean "₹", "Rs" or "INR", and ML Kit
     * drops or mangles the rupee glyph constantly — it comes back as nothing,
     * or as a stray letter or symbol. A receipt whose amount recognised as
     * plain "175" therefore yielded no amount at all and the whole capture
     * failed. That is one bad glyph away from every receipt, which is far too
     * fragile a hinge for the feature to hang on.
     *
     * Only consulted when nothing currency-marked was found anywhere, and only
     * for a line that is JUST a number once a leading marker is stripped — so
     * a reward blurb ("you earned a total of 5") cannot qualify, because there
     * the number is embedded in a sentence.
     */
    internal fun standaloneAmount(line: String): Money? {
        val t = line.trim()
        if (t.contains('@')) return null
        val stripped = STRAY_PREFIX.replace(t, "").trim()
        if (!STANDALONE_NUMBER.matches(stripped)) return null

        val grouped = stripped.contains(',') || stripped.contains('.')
        val digits = stripped.count { it.isDigit() }
        // A long run of BARE digits is an identifier — a reference, a phone
        // number, an account. Grouped or decimal figures get more room,
        // because the separators are what make them read as an amount.
        if (!grouped && digits > 6) return null
        if (grouped && digits > 9) return null
        return Money.parseOrNull(stripped)
    }

    /** Largest standalone figure on the page — the hero amount, when unmarked. */
    internal fun largestStandaloneAmount(lines: List<String>): Money? =
        lines.mapNotNull { standaloneAmount(it) }.maxByOrNull { it.paise }

    /** "15 aug 2026 * 6:07 pm", "15/08/2026", "15-Aug-26" → epoch millis. */
    internal fun receiptDate(lines: List<String>, zoneOffsetMillis: Long = 0L): Long? {
        for (line in lines) {
            val match = DATE_PATTERNS.firstNotNullOfOrNull { it.find(line) } ?: continue
            val day = match.groups["d"]?.value?.toIntOrNull() ?: continue
            val monthText = match.groups["m"]?.value ?: continue
            val month = monthText.toIntOrNull()
                ?: MONTHS[monthText.lowercase().take(3)]
                ?: continue
            val yearRaw = match.groups["y"]?.value?.toIntOrNull() ?: continue
            val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
            if (month !in 1..12 || day !in 1..31) continue
            // Midday, so a timezone shift cannot move the DAY — the time of day
            // is not what a receipt is being read for.
            val days = daysFromCivil(year, month, day)
            return days * 86_400_000L + 12 * 3_600_000L + zoneOffsetMillis
        }
        return null
    }

    /** Days since 1970-01-01, proleptic Gregorian. Pure arithmetic, no java.time. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun extractUpiScreenshot(
        lines: List<String>,
        template: OcrTemplate,
        capturedAtMillis: Long,
        liveCapture: Boolean,
    ): Extraction? {
        val marked = lines.firstNotNullOfOrNull { prominentAmount(it) }
            ?: lines.firstNotNullOfOrNull { anyAmount(it) }
        val amount = marked ?: largestStandaloneAmount(lines) ?: return null
        val amountWasGuessed = marked == null

        // The BANK UTR is the dedup key that matches the bank's SMS. UPI apps
        // also print their own transaction ID, which the bank never sends —
        // so a UTR-labeled value always wins over an app id.
        val utr = lines.firstNotNullOfOrNull { line ->
            bankUtrPattern.find(line)?.groups?.get("utr")?.value
        } ?: lines.firstNotNullOfOrNull { line ->
            appTxnIdPattern.find(line)?.groups?.get("utr")?.value
        // Receipts routinely print "transaction ID:" on one line and the value
        // on the next, which a same-line-only pattern can never see.
        } ?: labelledIdentifier(lines, REFERENCE_LABEL)

        // "Paid to X" / "To: X" / "Received from X"
        var merchant: String? = null
        var type = TxnType.DEBIT
        for (line in lines) {
            val paid = paidToPattern.find(line)
            if (paid != null) {
                merchant = paid.groups["name"]?.value?.trim()
                type = TxnType.DEBIT
                break
            }
            val received = receivedFromPattern.find(line)
            if (received != null) {
                merchant = received.groups["name"]?.value?.trim()
                type = TxnType.CREDIT
                break
            }
        }
        if (merchant == null) {
            // A "to" label with the payee on the following line. Deliberately
            // tried BEFORE "from": on a payment receipt the "from" block names
            // the user's own card, and reading it would both misname the payee
            // and flip the transaction into income.
            merchant = labelledValue(lines, PAID_TO_LABEL)?.also { type = TxnType.DEBIT }
                ?: labelledValue(lines, RECEIVED_FROM_LABEL)?.also { type = TxnType.CREDIT }
        }
        if (lines.any { failedPattern.containsMatchIn(it) }) return null

        val base = template.baseConfidence
        val last4 = lines
            .firstNotNullOfOrNull { last4Pattern.find(it)?.groups?.get("last4")?.value }
            ?.takeLast(4)
        // A receipt states its own date. Using the moment the screenshot was
        // picked instead put months-old payments on today, which is the single
        // thing that makes an imported receipt useless.
        val stamped = receiptDate(lines)
        return Extraction(
            txn = ParsedTransaction(
                amount = amount,
                type = type,
                accountLast4 = last4,
                merchantRaw = merchant,
                timestampMillis = stamped ?: capturedAtMillis,
                reference = utr,
                fieldConfidence = mapOf(
                    // An unmarked number is a GUESS, and must not be dressed up
                    // as anything else. The rupee glyph is frequently misread
                    // as a leading digit — "₹50" comes back as "750" — so the
                    // figure can be an order of magnitude out with nothing in
                    // the text to reveal it. Below the review threshold, so the
                    // preview flags the field and the row cannot slip in
                    // unexamined.
                    Field.AMOUNT to if (amountWasGuessed) GUESSED_AMOUNT else base,
                    Field.TYPE to base,
                    Field.ACCOUNT to if (last4 != null) base else 0.45,
                    Field.MERCHANT to if (merchant != null) base else 0.4,
                    Field.TIMESTAMP to when {
                        stamped != null -> 0.95
                        liveCapture -> 0.92
                        else -> 0.6
                    },
                    Field.REFERENCE to if (utr != null) 1.0 else 0.5,
                ),
            ),
            templateId = template.id,
            appLabel = template.appLabel,
        )
    }

    private fun extractBill(
        lines: List<String>,
        template: OcrTemplate,
        capturedAtMillis: Long,
        liveCapture: Boolean,
    ): Extraction? {
        // Total: prefer an explicit grand-total line, else the largest amount.
        val totalLine = lines.lastOrNull { totalKeywords.containsMatchIn(it) }
        val marked = totalLine?.let { anyAmount(it) }
            ?: lines.mapNotNull { anyAmount(it) }.maxByOrNull { it.paise }
        val amount = marked ?: largestStandaloneAmount(lines) ?: return null
        val amountWasGuessed = marked == null

        // A labelled payee wins; otherwise the first line that is plausibly a
        // name. The old rule took the first short line without an amount in it,
        // which on a phone receipt is the screen heading — "payment details"
        // arrived in the ledger as the merchant.
        val merchant = labelledValue(lines, PAID_TO_LABEL)
            ?: lines.firstOrNull { line ->
                line.length in 3..40 && !isChrome(line) && !totalKeywords.containsMatchIn(line)
            }

        val items = lines.mapNotNull { line ->
            val itemAmount = anyAmount(line) ?: return@mapNotNull null
            if (totalKeywords.containsMatchIn(line)) return@mapNotNull null
            val name = line.replace(amountPattern, "").trim().trim('-', ':', '.', 'x', 'X').trim()
            if (name.length < 2 || itemAmount.paise >= amount.paise) return@mapNotNull null
            LineItem(name, itemAmount)
        }

        val stamped = receiptDate(lines)
        return Extraction(
            txn = ParsedTransaction(
                amount = amount,
                type = TxnType.DEBIT,
                merchantRaw = merchant,
                timestampMillis = stamped ?: capturedAtMillis,
                reference = labelledIdentifier(lines, REFERENCE_LABEL),
                fieldConfidence = mapOf(
                    Field.AMOUNT to if (amountWasGuessed) GUESSED_AMOUNT else template.baseConfidence,
                    Field.TYPE to 0.85,
                    Field.ACCOUNT to 0.35,
                    Field.MERCHANT to if (merchant != null) 0.7 else 0.35,
                    Field.TIMESTAMP to when {
                        stamped != null -> 0.95
                        liveCapture -> 0.8
                        else -> 0.6
                    },
                    Field.REFERENCE to 0.35,
                ),
            ),
            templateId = template.id,
            appLabel = template.appLabel,
            lineItems = items,
            warrantyCandidate = items.maxByOrNull { it.amount.paise }?.name,
        )
    }

    /** A line that is essentially just a big amount — the UPI hero figure. */
    private fun prominentAmount(line: String): Money? {
        val match = heroAmountPattern.matchEntire(line.trim()) ?: return null
        return Money.parseOrNull(match.groups["amount"]!!.value)
    }

    private fun anyAmount(line: String): Money? {
        val match = amountPattern.find(line) ?: return null
        return Money.parseOrNull(match.groups["amount"]!!.value)
    }

    private companion object {
        val heroAmountPattern = Regex("(?i)^(?:₹|Rs\\.?|INR)\\s*(?<amount>[\\d,]+(?:\\.\\d{1,2})?)$")
        val amountPattern = Regex("(?i)(?:₹|Rs\\.?|INR)\\s*(?<amount>[\\d,]+(?:\\.\\d{1,2})?)")
        /** Bank-side reference: what the bank also puts in its SMS. */
        val bankUtrPattern = Regex("(?i)\\b(?:UTR|RRN|UPI\\s*Ref(?:erence)?(?:\\s*No\\.?)?|UPI\\s*transaction\\s*ID)\\s*[:#-]?\\s*(?<utr>[A-Z0-9]{8,25})\\b")

        /** App-side id: better than nothing, but never matches an SMS. */
        val appTxnIdPattern = Regex("(?i)\\b(?:Transaction\\s*ID|Txn\\.?\\s*ID|Order\\s*ID)\\s*[:#-]?\\s*(?<utr>[A-Z0-9]{8,25})\\b")
        val paidToPattern = Regex("(?i)^(?:Paid\\s+to|To)\\s*[:\\-]?\\s*(?<name>.{2,40})$")
        val receivedFromPattern = Regex("(?i)^(?:Received\\s+from|From)\\s*[:\\-]?\\s*(?<name>.{2,40})$")
        // Cards are masked to as few as two visible digits ("XXXXXX76").
        val last4Pattern = Regex("(?i)(?:X|\\*){2,}\\s*(?<last4>\\d{2,8})\\b")

        /** Label forms, matched at the START of a line so a mid-sentence "to" cannot fire. */
        val PAID_TO_LABEL = Regex("(?i)^(?:paid\\s+to|pay\\s+to|to|payee|merchant|sent\\s+to)\\b")
        val RECEIVED_FROM_LABEL = Regex("(?i)^(?:received\\s+from|from|payer|sender)\\b")
        val REFERENCE_LABEL = Regex(
            "(?i)^(?:transaction\\s*id|txn\\.?\\s*id|order\\s*id|utr|rrn|" +
                "upi\\s*ref(?:erence)?(?:\\s*no\\.?)?|ref(?:erence)?(?:\\s*no\\.?)?)\\b",
        )
        val REFERENCE_VALUE = Regex("[A-Za-z0-9]{8,25}")

        /**
         * Confidence for an amount read WITHOUT a currency marker. Deliberately
         * under the 0.8 the preview treats as "worth a second look", so a
         * guessed figure always arrives flagged.
         */
        const val GUESSED_AMOUNT = 0.55

        /** A mangled or missing currency glyph, or a real one, at the start. */
        val STRAY_PREFIX = Regex("(?i)^(?:₹|rs\\.?|inr|[^\\p{L}\\d\\s]{1,2}|[a-z])\\s*")
        val STANDALONE_NUMBER = Regex("\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?")

        /** Never a payee: headings, branding, reward blurbs, status words. */
        val CHROME_WORDS = Regex(
            "(?i)\\b(payment\\s*details?|transaction\\s*details?|receipt|powered\\s*by|" +
                "unified\\s*payments?|upi|you\\s*earned|cashback|rewards?|points?|" +
                "share|download|help|support|success(?:ful)?|completed|pending|" +
                "credit\\s*card|debit\\s*card|bank\\s*name|account\\s*number|" +
                "view\\s*details|contact|home|back)\\b",
        )
        val MASKED_ID = Regex("(?i)(?:x|\\*){3,}\\s*\\d+|\\b\\d{9,}\\b")

        val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        )
        val DATE_PATTERNS = listOf(
            // 15 aug 2026 / 15-Aug-26 / 15 August 2026
            Regex("(?i)\\b(?<d>\\d{1,2})[\\s\\-/]+(?<m>[A-Za-z]{3,9})[\\s\\-/,]+(?<y>\\d{2,4})\\b"),
            // Aug 15, 2026
            Regex("(?i)\\b(?<m>[A-Za-z]{3,9})[\\s\\-/]+(?<d>\\d{1,2})[\\s\\-/,]+(?<y>\\d{2,4})\\b"),
            // 15/08/2026 / 15-08-26
            Regex("\\b(?<d>\\d{1,2})[\\-/](?<m>\\d{1,2})[\\-/](?<y>\\d{2,4})\\b"),
        )
        val failedPattern = Regex("(?i)\\b(failed|declined|unsuccessful|payment failed)\\b")
        val totalKeywords = Regex("(?i)\\b(grand\\s*total|total\\s*amount|net\\s*(?:payable|amount)|amount\\s*payable|total)\\b")
    }
}
