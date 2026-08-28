package dev.kosha.core.database.repo

import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.MetaDao
import dev.kosha.core.database.model.ConstitutionRuleEntity
import dev.kosha.core.database.model.RuleViolationEntity
import dev.kosha.core.engine.constitution.ConstitutionEngine
import dev.kosha.core.engine.constitution.MachineCheck
import dev.kosha.core.engine.query.Aggregation
import dev.kosha.core.engine.query.Query
import dev.kosha.core.engine.query.QueryAnswer
import dev.kosha.core.engine.query.QueryFilter
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * The personal financial constitution (spec Novel section / G12) — rules the
 * user writes for themselves, some machine-checkable.
 *
 * Evaluation is a READ-ONLY pass over already-committed transactions via the
 * same [QueryRepository] the query builder and NLU assistant use, run on
 * demand (when the Constitution screen is opened). It deliberately does not
 * hook into `PipelineCommitter` — that class's one job is deciding whether a
 * transaction commits at all, and is covered by tests
 * (`MultiAccountAttributionTest` and friends) that a rule-check pass has no
 * business risking. A rule that is violated is still true a few minutes
 * later when this runs, so nothing about "checked at commit time" is lost by
 * checking shortly after instead.
 */
@Singleton
class ConstitutionRepository @Inject constructor(
    private val metaDao: MetaDao,
    private val queryRepository: QueryRepository,
) {
    private val zone: ZoneId = ZoneId.systemDefault()

    val allRules: Flow<List<ConstitutionRuleEntity>> = metaDao.observeAllConstitutionRules()

    data class RuleStatus(val rule: ConstitutionRuleEntity, val evaluation: ConstitutionEngine.Evaluation?)

    suspend fun addFreeTextRule(text: String) {
        metaDao.insertConstitutionRule(ConstitutionRuleEntity(ruleText = text, machineCheck = null))
    }

    /** The one machine-checkable rule shape this pass offers a builder for: a category spending cap. */
    suspend fun addCategoryLimitRule(categoryName: String, limit: Money) {
        val check = MachineCheck(
            filter = QueryFilter(categoryNames = listOf(categoryName)),
            assert = MachineCheck.Assertion(sumLtePaise = limit.paise),
        )
        metaDao.insertConstitutionRule(
            ConstitutionRuleEntity(
                ruleText = "$categoryName: up to ${limit.format(withPaise = false)} this calendar month",
                machineCheck = ConstitutionEngine.encodeCheck(check),
            ),
        )
    }

    suspend fun setActive(rule: ConstitutionRuleEntity, active: Boolean) {
        metaDao.updateConstitutionRule(rule.copy(isActive = active))
    }

    suspend fun delete(rule: ConstitutionRuleEntity) {
        metaDao.deleteConstitutionRule(rule)
    }

    /**
     * Evaluates every active machine-checkable rule against the ledger right
     * now, and records at most one violation per rule per day — the `== `
     * dedup pattern `WarrantyReminderWorker` already uses, so re-opening the
     * screen ten times today logs the breach once, not ten times.
     */
    suspend fun evaluateActive(anchorDay: Int): List<RuleStatus> {
        val rules = metaDao.observeConstitutionRules().first()
        val todayStartMillis = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val loggedToday = metaDao.violationsSince(todayStartMillis).map { it.ruleId }.toSet()

        return rules.map { rule ->
            val check = ConstitutionEngine.parseCheck(rule.machineCheck) ?: return@map RuleStatus(rule, null)

            val aggregation = if (check.assert.countLte != null) Aggregation.COUNT else Aggregation.SUM
            val result = queryRepository.run(Query(check.filter, aggregation), anchorDay)
            val (total, count) = when (val answer = result.answer) {
                is QueryAnswer.Sum -> answer.total to answer.count
                is QueryAnswer.Count -> Money.ZERO to answer.count
                else -> Money.ZERO to 0
            }

            val evaluation = ConstitutionEngine.evaluate(rule.id, check, total, count)
            if (evaluation?.violated == true && rule.id !in loggedToday) {
                metaDao.insertRuleViolation(
                    RuleViolationEntity(ruleId = rule.id, timestampMillis = System.currentTimeMillis()),
                )
            }
            RuleStatus(rule, evaluation)
        }
    }

    /**
     * This 30-day window's violation count against the previous one — a
     * two-point read rather than per-period bookkeeping, since violations
     * aren't tied to the user's pay-period anchor the way spend totals are.
     */
    suspend fun violationTrend(): ConstitutionEngine.Trend {
        val now = System.currentTimeMillis()
        val windowMillis = 30L * 24 * 60 * 60 * 1000
        val thisWindow = metaDao.violationsSince(now - windowMillis).size
        val bothWindows = metaDao.violationsSince(now - 2 * windowMillis).size
        return ConstitutionEngine.violationTrend(listOf(bothWindows - thisWindow, thisWindow))
    }
}
