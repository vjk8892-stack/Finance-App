package dev.kosha.core.common

/**
 * All monetary amounts in Kosha are Long paise (spec G1). Never floating point.
 *
 * Formatting follows Indian digit grouping: ₹1,23,456.78 — last group of 3,
 * then groups of 2. Hand-rolled so JVM (tests) and Android (ICU) render
 * identically.
 */
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(paise + other.paise)
    operator fun minus(other: Money): Money = Money(paise - other.paise)
    operator fun unaryMinus(): Money = Money(-paise)
    operator fun times(factor: Long): Money = Money(paise * factor)
    override fun compareTo(other: Money): Int = paise.compareTo(other.paise)

    val isNegative: Boolean get() = paise < 0
    val abs: Money get() = if (paise < 0) Money(-paise) else this

    /**
     * "₹1,23,456.78". [withSymbol] toggles the rupee sign; [withPaise] toggles
     * the decimal part (dashboards often show whole rupees); [signed] prefixes
     * "+"/"−" (typographic minus, never a bare hyphen in UI copy).
     */
    fun format(
        withSymbol: Boolean = true,
        withPaise: Boolean = true,
        signed: Boolean = false,
    ): String {
        val absPaise = if (paise < 0) -paise else paise
        val rupees = absPaise / 100
        val fraction = absPaise % 100
        val grouped = groupIndian(rupees.toString())
        return buildString {
            if (paise < 0) append('−') else if (signed && paise > 0) append('+')
            if (withSymbol) append('₹')
            append(grouped)
            if (withPaise) {
                append('.')
                append(fraction.toString().padStart(2, '0'))
            }
        }
    }

    override fun toString(): String = format()

    companion object {
        val ZERO = Money(0)

        fun ofRupees(rupees: Long): Money = Money(rupees * 100)

        /**
         * Parses user/SMS text like "1,23,456.78", "₹1234", "1234.5", "Rs. 99".
         * Returns null when the text is not a plain positive amount.
         */
        fun parseOrNull(raw: String): Money? {
            val cleaned = raw
                .replace("₹", "")
                .replace(Regex("(?i)\\b(rs|inr)\\b\\.?"), "")
                .replace(",", "")
                .trim()
            if (cleaned.isEmpty() || !cleaned.matches(Regex("\\d+(\\.\\d{1,2})?"))) return null
            val parts = cleaned.split('.')
            val rupees = parts[0].toLongOrNull() ?: return null
            val fraction = if (parts.size == 2) parts[1].padEnd(2, '0').toLong() else 0L
            return Money(rupees * 100 + fraction)
        }

        private fun groupIndian(digits: String): String {
            if (digits.length <= 3) return digits
            val last3 = digits.takeLast(3)
            var head = digits.dropLast(3)
            val groups = ArrayDeque<String>()
            while (head.length > 2) {
                groups.addFirst(head.takeLast(2))
                head = head.dropLast(2)
            }
            if (head.isNotEmpty()) groups.addFirst(head)
            return groups.joinToString(",") + "," + last3
        }
    }
}
