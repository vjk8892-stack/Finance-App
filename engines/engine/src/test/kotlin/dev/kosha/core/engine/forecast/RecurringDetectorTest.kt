package dev.kosha.core.engine.forecast

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringDetectorTest {

    private val today = LocalDate.of(2026, 8, 19)

    private fun occurrence(
        merchant: String,
        amountRupees: Long,
        date: LocalDate,
        accountId: Long = 1L,
        categoryId: Long? = 7L,
    ) = RecurringDetector.Occurrence(
        merchantNormalized = merchant.lowercase(),
        label = merchant,
        amountPaise = amountRupees * 100,
        date = date,
        accountId = accountId,
        categoryId = categoryId,
    )

    private fun monthly(merchant: String, amountRupees: Long, day: Int, months: List<Int>) =
        months.map { occurrence(merchant, amountRupees, LocalDate.of(2026, it, day)) }

    @Test
    fun `a fixed monthly subscription is detected`() {
        val found = RecurringDetector.detect(
            monthly("Netflix", 649, day = 12, months = listOf(5, 6, 7, 8)),
            today,
        )

        assertEquals(1, found.size)
        val candidate = found.single()
        assertEquals(RecurringEngine.Frequency.MONTHLY, candidate.frequency)
        assertEquals(64_900L, candidate.typicalAmountPaise)
        assertEquals("Netflix", candidate.label)
        assertEquals(LocalDate.of(2026, 9, 12), candidate.nextDue)
        assertEquals(4, candidate.occurrences)
        assertTrue("steady subscription should score high", candidate.confidence > 0.8)
    }

    @Test
    fun `two payments are not a cadence`() {
        val found = RecurringDetector.detect(
            monthly("Spotify", 119, day = 3, months = listOf(7, 8)),
            today,
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `irregular dates are not recurring`() {
        val found = RecurringDetector.detect(
            listOf(
                occurrence("Swiggy", 420, LocalDate.of(2026, 6, 2)),
                occurrence("Swiggy", 380, LocalDate.of(2026, 6, 19)),
                occurrence("Swiggy", 510, LocalDate.of(2026, 8, 1)),
                occurrence("Swiggy", 260, LocalDate.of(2026, 8, 14)),
            ),
            today,
        )
        assertTrue("food delivery is frequent, not periodic", found.isEmpty())
    }

    @Test
    fun `a cancelled subscription is not offered`() {
        // Last seen in March; five months of silence means it is gone.
        val found = RecurringDetector.detect(
            monthly("Gym", 1500, day = 5, months = listOf(1, 2, 3)),
            today,
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a merchant that already has a rule is not offered again`() {
        val occurrences = monthly("Netflix", 649, day = 12, months = listOf(5, 6, 7, 8))

        assertTrue(
            "matching an existing rule must ignore case",
            RecurringDetector.detect(occurrences, today, existingPatterns = setOf("NETFLIX"))
                .isEmpty(),
        )
        assertTrue(
            RecurringDetector.detect(occurrences, today, dismissed = setOf("netflix")).isEmpty(),
        )
    }

    @Test
    fun `month-end billing still counts as monthly`() {
        // 31 Jan, 28 Feb, 31 Mar: gaps of 28 and 31 days around a 29-day median.
        val found = RecurringDetector.detect(
            listOf(
                occurrence("Rent", 25_000, LocalDate.of(2026, 5, 31)),
                occurrence("Rent", 25_000, LocalDate.of(2026, 6, 30)),
                occurrence("Rent", 25_000, LocalDate.of(2026, 7, 31)),
                occurrence("Rent", 25_000, LocalDate.of(2026, 8, 31)),
            ),
            LocalDate.of(2026, 9, 2),
        )
        assertEquals(RecurringEngine.Frequency.MONTHLY, found.single().frequency)
    }

    @Test
    fun `a swinging utility bill scores below the bar`() {
        val found = RecurringDetector.detect(
            listOf(
                occurrence("BESCOM", 800, LocalDate.of(2026, 5, 10)),
                occurrence("BESCOM", 3200, LocalDate.of(2026, 6, 10)),
                occurrence("BESCOM", 1100, LocalDate.of(2026, 7, 10)),
                occurrence("BESCOM", 2600, LocalDate.of(2026, 8, 10)),
            ),
            today,
        )
        assertTrue(
            "an amount that triples month to month cannot be forecast as a fixed rule",
            found.isEmpty(),
        )
    }

    @Test
    fun `a weekly payment is detected as weekly`() {
        val found = RecurringDetector.detect(
            (0..4).map { occurrence("Cook", 1200, LocalDate.of(2026, 7, 20).plusWeeks(it.toLong())) },
            LocalDate.of(2026, 8, 20),
        )
        assertEquals(RecurringEngine.Frequency.WEEKLY, found.single().frequency)
    }

    @Test
    fun `a daily habit is never turned into a rule`() {
        val found = RecurringDetector.detect(
            (0..9).map { occurrence("Chai", 20, LocalDate.of(2026, 8, 8).plusDays(it.toLong())) },
            today,
        )
        assertTrue("a daily coffee is a habit, not a subscription", found.isEmpty())
    }

    @Test
    fun `two payments on the same day count once`() {
        // A retried card payment must not read as a zero-day gap.
        val found = RecurringDetector.detect(
            monthly("Netflix", 649, day = 12, months = listOf(5, 6, 7, 8)) +
                occurrence("Netflix", 649, LocalDate.of(2026, 8, 12)),
            today,
        )
        assertEquals(4, found.single().occurrences)
    }

    @Test
    fun `the strongest candidate comes first`() {
        val found = RecurringDetector.detect(
            monthly("Netflix", 649, day = 12, months = listOf(5, 6, 7, 8)) +
                listOf(
                    // Same cadence, but the amount drifts — less certain.
                    occurrence("Broadband", 1100, LocalDate.of(2026, 5, 4)),
                    occurrence("Broadband", 1250, LocalDate.of(2026, 6, 4)),
                    occurrence("Broadband", 1180, LocalDate.of(2026, 7, 6)),
                    occurrence("Broadband", 1210, LocalDate.of(2026, 8, 4)),
                ),
            today,
        )
        assertEquals(listOf("Netflix", "Broadband"), found.map { it.label })
    }
}
