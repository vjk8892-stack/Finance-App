package dev.kosha.core.engine.constitution

import dev.kosha.core.common.Money
import dev.kosha.core.engine.query.QueryFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Personal financial constitution (spec Phase 12 / G12).
 *
 * A rule is either machine-checkable — the same filter grammar as SavedQuery
 * plus a comparator, evaluated at pipeline commit time — or free text, which
 * surfaces at period close for the user's own review. Kosha never judges a
 * free-text rule automatically; it just brings it back at the right moment.
 */
@Serializable
data class MachineCheck(
    val filter: QueryFilter,
    val assert: Assertion,
) {
    @Serializable
    data class Assertion(
        val sumLtePaise: Long? = null,
        val sumGtePaise: Long? = null,
        val countLte: Int? = null,
    )
}

object ConstitutionEngine {

    data class Evaluation(
        val ruleId: Long,
        val violated: Boolean,
        val observed: Money,
        val observedCount: Int,
        val explanation: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCheck(machineCheckJson: String?): MachineCheck? {
        if (machineCheckJson.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<MachineCheck>(machineCheckJson) }.getOrNull()
    }

    /**
     * Evaluates one rule against the totals its filter selected. Returns null
     * for free-text rules (nothing to check automatically).
     */
    fun evaluate(
        ruleId: Long,
        check: MachineCheck?,
        matchedTotal: Money,
        matchedCount: Int,
    ): Evaluation? {
        val assertion = check?.assert ?: return null

        assertion.sumLtePaise?.let { limit ->
            val violated = matchedTotal.paise > limit
            return Evaluation(
                ruleId = ruleId,
                violated = violated,
                observed = matchedTotal,
                observedCount = matchedCount,
                explanation = if (violated) {
                    "${matchedTotal.format(withPaise = false)} spent against a " +
                        "${Money(limit).format(withPaise = false)} limit"
                } else {
                    "${matchedTotal.format(withPaise = false)} of " +
                        "${Money(limit).format(withPaise = false)} — within your rule"
                },
            )
        }
        assertion.sumGtePaise?.let { floor ->
            val violated = matchedTotal.paise < floor
            return Evaluation(
                ruleId = ruleId,
                violated = violated,
                observed = matchedTotal,
                observedCount = matchedCount,
                explanation = if (violated) {
                    "${matchedTotal.format(withPaise = false)} against a target of " +
                        Money(floor).format(withPaise = false)
                } else {
                    "${matchedTotal.format(withPaise = false)} — target met"
                },
            )
        }
        assertion.countLte?.let { limit ->
            val violated = matchedCount > limit
            return Evaluation(
                ruleId = ruleId,
                violated = violated,
                observed = matchedTotal,
                observedCount = matchedCount,
                explanation = if (violated) {
                    "$matchedCount times against a limit of $limit"
                } else {
                    "$matchedCount of $limit — within your rule"
                },
            )
        }
        return null
    }

    /** Violation trend across periods (spec Phase 12 Insights entry). */
    fun violationTrend(violationsPerPeriod: List<Int>): Trend {
        if (violationsPerPeriod.size < 2) return Trend.NOT_ENOUGH_DATA
        val recent = violationsPerPeriod.last()
        val previous = violationsPerPeriod[violationsPerPeriod.size - 2]
        return when {
            recent < previous -> Trend.IMPROVING
            recent > previous -> Trend.SLIPPING
            else -> Trend.STEADY
        }
    }

    enum class Trend { IMPROVING, STEADY, SLIPPING, NOT_ENOUGH_DATA }
}
