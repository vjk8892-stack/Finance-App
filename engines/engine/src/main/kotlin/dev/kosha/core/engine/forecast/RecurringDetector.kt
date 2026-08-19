package dev.kosha.core.engine.forecast

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Finds payments that repeat on a cadence, so they can be offered as rules.
 *
 * Recurring rules already existed and did real work — they forecast, they
 * auto-log, they stop an EMI being counted twice — but every one of them had
 * to be typed in by hand from memory. Nobody remembers all their subscriptions,
 * which is the entire reason subscriptions are a business model. The ledger
 * already knows: the same name, at roughly the same interval, three times over
 * is not a coincidence.
 *
 * Deliberately conservative. This produces a QUESTION, never a rule — a false
 * positive that becomes a forecast line the user did not ask for is worse than
 * a missed subscription, because it silently changes numbers they trust.
 */
object RecurringDetector {

    /** One past payment. Only what a cadence can be read from. */
    data class Occurrence(
        val merchantNormalized: String,
        val label: String,
        val amountPaise: Long,
        val date: LocalDate,
        val accountId: Long,
        val categoryId: Long?,
    )

    data class Candidate(
        val merchantNormalized: String,
        /** The name as the user would recognise it, from the latest payment. */
        val label: String,
        val frequency: RecurringEngine.Frequency,
        /** Median, not mean: one annual renewal must not drag a monthly figure. */
        val typicalAmountPaise: Long,
        val accountId: Long,
        val categoryId: Long?,
        val lastSeen: LocalDate,
        val nextDue: LocalDate,
        val occurrences: Int,
        /** Higher when the gaps are even and the amount never moves. */
        val confidence: Double,
    )

    /** Below three payments there is no cadence, only a repeat. */
    const val MIN_OCCURRENCES = 3

    /** Nothing weaker than this is worth interrupting the user about. */
    const val MIN_CONFIDENCE = 0.6

    /**
     * @param existingPatterns merchant patterns that already have a rule.
     * Matched case-insensitively — asking twice about the same subscription is
     * how a suggestion becomes noise.
     * @param dismissed merchants the user has already said no to.
     */
    fun detect(
        occurrences: List<Occurrence>,
        today: LocalDate,
        existingPatterns: Set<String> = emptySet(),
        dismissed: Set<String> = emptySet(),
    ): List<Candidate> {
        val known = (existingPatterns + dismissed).map { it.lowercase().trim() }.toSet()
        return occurrences
            .filter { it.merchantNormalized.isNotBlank() }
            .groupBy { it.merchantNormalized.lowercase().trim() }
            .filterKeys { it !in known }
            .values
            .mapNotNull { candidateFor(it, today) }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .sortedByDescending { it.confidence }
    }

    private fun candidateFor(group: List<Occurrence>, today: LocalDate): Candidate? {
        // Two payments on the same day are one payment split, or a retry —
        // either way they are one occurrence as far as a cadence is concerned.
        val byDate = group.sortedBy { it.date }.distinctBy { it.date }
        if (byDate.size < MIN_OCCURRENCES) return null

        val gaps = byDate.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.any { it <= 0 }) return null
        val typicalGap = median(gaps)
        val frequency = frequencyFor(typicalGap) ?: return null

        // Every gap has to look like the same cadence. One matching pair among
        // five random ones is not a subscription.
        val tolerance = toleranceFor(frequency)
        if (gaps.any { abs(it - typicalGap) > tolerance }) return null

        val last = byDate.last()
        // A subscription cancelled in March is not due in August. Allow one
        // missed cycle — a failed card payment happens — and no more.
        if (ChronoUnit.DAYS.between(last.date, today) > typicalGap * 2) return null

        val amounts = byDate.map { it.amountPaise }
        val typicalAmount = median(amounts.map { it.toLong() })
        if (typicalAmount <= 0L) return null

        return Candidate(
            merchantNormalized = last.merchantNormalized,
            label = last.label.takeIf { it.isNotBlank() } ?: last.merchantNormalized,
            frequency = frequency,
            typicalAmountPaise = typicalAmount,
            accountId = last.accountId,
            categoryId = byDate.mapNotNull { it.categoryId }.lastOrNull(),
            lastSeen = last.date,
            nextDue = RecurringEngine.nextDueDate(last.date, frequency),
            occurrences = byDate.size,
            confidence = confidence(gaps, typicalGap, amounts, typicalAmount, byDate.size),
        )
    }

    /**
     * Daily is absent on purpose: a coffee every morning is a habit, not a
     * subscription, and turning it into an auto-logged rule would invent
     * transactions on the days it did not happen.
     */
    private fun frequencyFor(gapDays: Long): RecurringEngine.Frequency? = when (gapDays) {
        in 6L..8L -> RecurringEngine.Frequency.WEEKLY
        in 27L..32L -> RecurringEngine.Frequency.MONTHLY
        in 88L..95L -> RecurringEngine.Frequency.QUARTERLY
        in 358L..373L -> RecurringEngine.Frequency.YEARLY
        else -> null
    }

    /**
     * Wider for longer cadences: a monthly bill lands anywhere from the 28th
     * to the 3rd depending on weekends, while a weekly one drifting four days
     * is not weekly.
     */
    private fun toleranceFor(frequency: RecurringEngine.Frequency): Long = when (frequency) {
        RecurringEngine.Frequency.DAILY -> 0L
        RecurringEngine.Frequency.WEEKLY -> 2L
        RecurringEngine.Frequency.MONTHLY -> 5L
        RecurringEngine.Frequency.QUARTERLY -> 8L
        RecurringEngine.Frequency.YEARLY -> 15L
    }

    private fun confidence(
        gaps: List<Long>,
        typicalGap: Long,
        amounts: List<Long>,
        typicalAmount: Long,
        count: Int,
    ): Double {
        var score = 0.6

        // An unvarying amount is the strongest single signal there is: that is
        // what a subscription looks like and what a variable bill does not.
        val amountDrift = amounts.maxOf { abs(it - typicalAmount).toDouble() } /
            typicalAmount.toDouble()
        score += when {
            amountDrift == 0.0 -> 0.25
            amountDrift <= 0.05 -> 0.15
            amountDrift <= 0.20 -> 0.05
            // A bill that swings by half is a utility, and guessing next
            // month's figure from it would be fiction.
            else -> -0.20
        }

        val gapDrift = gaps.maxOf { abs(it - typicalGap).toDouble() } / typicalGap.toDouble()
        score += if (gapDrift <= 0.05) 0.10 else 0.0

        // More history, more certainty — but it saturates. Twelve months of a
        // subscription is not four times the evidence of three.
        score += ((count - MIN_OCCURRENCES).coerceAtMost(3)) * 0.03

        return score.coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
