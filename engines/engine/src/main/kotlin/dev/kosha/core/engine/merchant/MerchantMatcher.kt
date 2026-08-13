package dev.kosha.core.engine.merchant

import kotlin.math.max
import kotlin.math.min

/**
 * Merchant normalization & fuzzy matching, spec G7.
 *
 * 1. Normalize: uppercase → strip UPI/txn noise tokens → collapse whitespace
 * 2. Exact match on normalized form → same merchant
 * 3. Jaro-Winkler ≥ 0.90 → auto-link; 0.82–0.90 → suggest in review; below → new
 */
object MerchantMatcher {

    const val AUTO_LINK_THRESHOLD = 0.90
    const val SUGGEST_THRESHOLD = 0.82

    private val noiseTokens = setOf(
        "UPI", "POS", "IMPS", "NEFT", "RTGS", "VPA", "PVT", "LTD", "PVTLTD",
        "PRIVATE", "LIMITED", "INDIA", "PAYMENT", "PAYMENTS", "PAY", "REF",
        "TXN", "TRANSACTION", "P2M", "P2A", "OKICICI", "OKHDFCBANK", "OKAXIS",
        "OKSBI", "YBL", "IBL", "AXL", "PAYTM", "APL",
    )

    private val trailingJunk = Regex("[\\d/@._-]+$")
    private val refNumbers = Regex("\\b\\d{6,}\\b")
    private val dates = Regex("\\b\\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\\d{2,4}\\b")
    private val nonAlnum = Regex("[^A-Z0-9 ]")
    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.uppercase()
        // VPA handle: keep only the part before @ (swiggy@icici → SWIGGY)
        val at = s.indexOf('@')
        if (at > 0) s = s.substring(0, at)
        s = dates.replace(s, " ")
        s = refNumbers.replace(s, " ")
        s = nonAlnum.replace(s, " ")
        s = s.split(' ')
            .filter { it.isNotBlank() && it !in noiseTokens }
            .joinToString(" ")
        s = trailingJunk.replace(s, "")
        return whitespace.replace(s, " ").trim()
    }

    sealed interface MatchResult {
        data class AutoLink(val canonical: String, val similarity: Double) : MatchResult
        data class Suggest(val canonical: String, val similarity: Double) : MatchResult
        data object NewMerchant : MatchResult
    }

    /** [known] are normalized canonical merchant names already in the ledger. */
    fun match(rawMerchant: String, known: Collection<String>): MatchResult {
        val normalized = normalize(rawMerchant)
        if (normalized.isEmpty()) return MatchResult.NewMerchant
        if (normalized in known) return MatchResult.AutoLink(normalized, 1.0)

        var best: String? = null
        var bestScore = 0.0
        for (candidate in known) {
            val score = jaroWinkler(normalized, candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return when {
            best != null && bestScore >= AUTO_LINK_THRESHOLD -> MatchResult.AutoLink(best, bestScore)
            best != null && bestScore >= SUGGEST_THRESHOLD -> MatchResult.Suggest(best, bestScore)
            else -> MatchResult.NewMerchant
        }
    }

    /** Whether two already-normalized names refer to the same merchant (dedup rule 2). */
    fun sameMerchant(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || jaroWinkler(a, b) >= AUTO_LINK_THRESHOLD
    }

    fun jaroWinkler(s1: String, s2: String): Double {
        val jaro = jaro(s1, s2)
        if (jaro < 0.7) return jaro
        var prefix = 0
        for (i in 0 until min(min(s1.length, s2.length), 4)) {
            if (s1[i] == s2[i]) prefix++ else break
        }
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    private fun jaro(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val matchWindow = max(s1.length, s2.length) / 2 - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val start = max(0, i - matchWindow)
            val end = min(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        return (m / s1.length + m / s2.length + (m - transpositions / 2.0) / m) / 3.0
    }
}
