package dev.kosha.core.engine.insight

import dev.kosha.core.common.Money
import kotlin.math.abs

/**
 * Anomaly detection, spec G5.
 *
 * Scope: per normalized merchant with ≥5 prior transactions, else per
 * category with ≥8 prior, else INACTIVE (cold-start honesty — no flags).
 *
 * Method: robust z via median/MAD (resists outliers on small personal
 * samples far better than mean/σ): flag when
 *   |amount − median| / (1.4826·MAD) > threshold   AND
 *   |amount − median| > ₹200
 *
 * Feedback: an "expected" dismissal excludes that transaction from future
 * baselines; two dismissals for the same merchant raise its threshold to 4.
 */
object AnomalyEngine {

    const val MERCHANT_MIN_HISTORY = 5
    const val CATEGORY_MIN_HISTORY = 8
    const val DEFAULT_THRESHOLD = 3.0
    const val RAISED_THRESHOLD = 4.0
    const val DISMISSALS_TO_RAISE = 2
    const val MAX_ACTIVE_FLAGS = 3
    val MIN_ABSOLUTE_DEVIATION = Money.ofRupees(200)
    const val HISTORY_WINDOW_DAYS = 180L // trailing 6 months

    private const val MAD_TO_SIGMA = 1.4826

    data class Candidate(
        val transactionId: Long,
        val amount: Money,
        val merchantNormalized: String?,
        val categoryId: Long?,
        /** Display name for the category, so a flag can say what it is about. */
        val categoryName: String? = null,
        val timestampMillis: Long,
    )

    data class History(
        val amounts: List<Money>,
        /** How many times the user dismissed a flag for this scope as expected. */
        val dismissals: Int = 0,
    )

    data class Flag(
        val transactionId: Long,
        val amount: Money,
        val median: Money,
        val robustZ: Double,
        val scope: Scope,
        /**
         * What the flag is ABOUT. Every anomaly row used to read the same
         * "Bigger than usual", which is unreadable once there are three of
         * them — the user cannot tell which spend is being questioned.
         */
        val label: String,
        val explanation: String,
    )

    enum class Scope { MERCHANT, CATEGORY }

    /**
     * @param merchantHistory prior amounts for the candidate's merchant
     * @param categoryHistory prior amounts for the candidate's category
     */
    fun evaluate(
        candidate: Candidate,
        merchantHistory: History?,
        categoryHistory: History?,
    ): Flag? {
        val (history, scope) = when {
            merchantHistory != null && merchantHistory.amounts.size >= MERCHANT_MIN_HISTORY ->
                merchantHistory to Scope.MERCHANT
            categoryHistory != null && categoryHistory.amounts.size >= CATEGORY_MIN_HISTORY ->
                categoryHistory to Scope.CATEGORY
            // Cold start: not enough history to have an opinion.
            else -> return null
        }

        val values = history.amounts.map { it.paise }
        val median = median(values)
        val mad = median(values.map { abs(it - median) })
        val deviation = abs(candidate.amount.paise - median)

        if (deviation <= MIN_ABSOLUTE_DEVIATION.paise) return null

        val threshold = if (history.dismissals >= DISMISSALS_TO_RAISE) {
            RAISED_THRESHOLD
        } else {
            DEFAULT_THRESHOLD
        }

        // MAD of 0 means an utterly consistent history; any deviation past
        // the absolute floor is then genuinely unusual.
        val robustZ = if (mad == 0L) {
            Double.POSITIVE_INFINITY
        } else {
            deviation / (MAD_TO_SIGMA * mad)
        }
        if (robustZ <= threshold) return null

        val scopeLabel = if (scope == Scope.MERCHANT) {
            candidate.merchantNormalized ?: "this merchant"
        } else {
            candidate.categoryName ?: "this category"
        }
        return Flag(
            transactionId = candidate.transactionId,
            amount = candidate.amount,
            median = Money(median),
            robustZ = robustZ,
            scope = scope,
            label = scopeLabel,
            explanation = "usually around ${Money(median).format(withPaise = false)} at $scopeLabel",
        )
    }

    /** Calm-design cap: at most 3 flags shown at once, biggest deviations first. */
    fun cap(flags: List<Flag>): List<Flag> =
        flags.sortedByDescending { abs(it.amount.paise - it.median.paise) }.take(MAX_ACTIVE_FLAGS)

    internal fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
