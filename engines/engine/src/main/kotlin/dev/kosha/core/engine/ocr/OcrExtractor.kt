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

        return when (template.appLabel) {
            "generic-bill" -> extractBill(lines, template, capturedAtMillis, liveCapture)
            else -> extractUpiScreenshot(lines, template, capturedAtMillis, liveCapture)
        }
    }

    private fun extractUpiScreenshot(
        lines: List<String>,
        template: OcrTemplate,
        capturedAtMillis: Long,
        liveCapture: Boolean,
    ): Extraction? {
        val amount = lines.firstNotNullOfOrNull { prominentAmount(it) }
            ?: lines.firstNotNullOfOrNull { anyAmount(it) }
            ?: return null

        // The BANK UTR is the dedup key that matches the bank's SMS. UPI apps
        // also print their own transaction ID, which the bank never sends —
        // so a UTR-labeled value always wins over an app id.
        val utr = lines.firstNotNullOfOrNull { line ->
            bankUtrPattern.find(line)?.groups?.get("utr")?.value
        } ?: lines.firstNotNullOfOrNull { line ->
            appTxnIdPattern.find(line)?.groups?.get("utr")?.value
        }

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
            // Fall back to the line following a bare "To"/"Paid to" label.
            val idx = lines.indexOfFirst { it.equals("To", true) || it.equals("Paid to", true) }
            if (idx >= 0 && idx + 1 < lines.size) merchant = lines[idx + 1]
        }
        if (lines.any { failedPattern.containsMatchIn(it) }) return null

        val base = template.baseConfidence
        val last4 = lines.firstNotNullOfOrNull { last4Pattern.find(it)?.groups?.get("last4")?.value }
        return Extraction(
            txn = ParsedTransaction(
                amount = amount,
                type = type,
                accountLast4 = last4,
                merchantRaw = merchant,
                timestampMillis = capturedAtMillis,
                reference = utr,
                fieldConfidence = mapOf(
                    Field.AMOUNT to base,
                    Field.TYPE to base,
                    Field.ACCOUNT to if (last4 != null) base else 0.45,
                    Field.MERCHANT to if (merchant != null) base else 0.4,
                    Field.TIMESTAMP to if (liveCapture) 0.92 else 0.6,
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
        val amount = totalLine?.let { anyAmount(it) }
            ?: lines.mapNotNull { anyAmount(it) }.maxByOrNull { it.paise }
            ?: return null

        val merchant = lines.firstOrNull { line ->
            line.length in 3..40 && anyAmount(line) == null && !totalKeywords.containsMatchIn(line)
        }

        val items = lines.mapNotNull { line ->
            val itemAmount = anyAmount(line) ?: return@mapNotNull null
            if (totalKeywords.containsMatchIn(line)) return@mapNotNull null
            val name = line.replace(amountPattern, "").trim().trim('-', ':', '.', 'x', 'X').trim()
            if (name.length < 2 || itemAmount.paise >= amount.paise) return@mapNotNull null
            LineItem(name, itemAmount)
        }

        return Extraction(
            txn = ParsedTransaction(
                amount = amount,
                type = TxnType.DEBIT,
                merchantRaw = merchant,
                timestampMillis = capturedAtMillis,
                reference = null,
                fieldConfidence = mapOf(
                    Field.AMOUNT to template.baseConfidence,
                    Field.TYPE to 0.85,
                    Field.ACCOUNT to 0.35,
                    Field.MERCHANT to if (merchant != null) 0.7 else 0.35,
                    Field.TIMESTAMP to if (liveCapture) 0.8 else 0.6,
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
        val last4Pattern = Regex("(?i)(?:X|\\*){2,}\\s*(?<last4>\\d{3,4})\\b")
        val failedPattern = Regex("(?i)\\b(failed|declined|unsuccessful|payment failed)\\b")
        val totalKeywords = Regex("(?i)\\b(grand\\s*total|total\\s*amount|net\\s*(?:payable|amount)|amount\\s*payable|total)\\b")
    }
}
