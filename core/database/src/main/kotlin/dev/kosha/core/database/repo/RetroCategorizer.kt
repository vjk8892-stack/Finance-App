package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.merchant.MerchantCategoryRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies categorization to transactions that were captured BEFORE the rules
 * that would have categorized them existed.
 *
 * Categorization runs at commit time, so improving it only helps messages that
 * arrive afterwards — everything already in the ledger keeps whatever it got
 * on the day. That is a bad deal for the user: their history is the part they
 * care about, and telling them to re-scan pushes the cost of our late rule
 * onto them. Worse, a category-shaped chart reads a 100%-Uncategorized month
 * as "no information", so the whole Insights tab stays blank on real data.
 *
 * This walks the committed rows that still have no category and applies the
 * same two signals the committer uses, in the same order: what the user
 * themselves did with this merchant first, then the keyword rules.
 *
 * Idempotent: rows that already have a real category are never touched, so
 * running it repeatedly costs a read and changes nothing.
 */
@Singleton
class RetroCategorizer @Inject constructor(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {

    data class Result(
        val examined: Int,
        val categorized: Int,
        /** Merchant → category name, for reporting what it decided. */
        val byCategory: Map<String, Int>,
    )

    suspend fun run(): Result {
        val uncategorizedId = categoryDao.bySystemKey(SystemCategoryKey.UNCATEGORIZED)?.id
        val pending = transactionDao.uncategorizedWithMerchant(uncategorizedId)
        if (pending.isEmpty()) return Result(0, 0, emptyMap())

        val categoriesById = categoryDao.observeAllOnce().associateBy { it.id }
        val now = System.currentTimeMillis()

        // Decide once per merchant rather than per row: every transaction of a
        // merchant gets the same answer anyway, and it keeps the work
        // proportional to distinct merchants instead of ledger size.
        val decisions = pending
            .mapNotNull { it.merchantNormalized }
            .distinct()
            .associateWith { merchant ->
                val isCredit = pending.firstOrNull { it.merchantNormalized == merchant }
                    ?.type == TxnType.CREDIT
                resolve(merchant, isCredit, uncategorizedId)
            }
            .filterValues { it != null }

        var categorized = 0
        val tally = mutableMapOf<String, Int>()
        for ((merchant, categoryId) in decisions) {
            val id = categoryId ?: continue
            val affected = pending.count { it.merchantNormalized == merchant }
            transactionDao.recategorizeMerchant(merchant, id, now)
            categorized += affected
            val name = categoriesById[id]?.name ?: "Uncategorized"
            tally[name] = (tally[name] ?: 0) + affected
        }

        return Result(examined = pending.size, categorized = categorized, byCategory = tally)
    }

    /** The user's own history wins; the keyword table is the fallback (G7). */
    private suspend fun resolve(
        merchantNormalized: String,
        isCredit: Boolean,
        uncategorizedId: Long?,
    ): Long? {
        val learned = transactionDao.recentCategoriesForMerchant(merchantNormalized)
            .filter { it != uncategorizedId }
            .groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value >= LEARNED_THRESHOLD }
            ?.key
        if (learned != null) return learned

        val guess = MerchantCategoryRules.categoryNameFor(merchantNormalized, isCredit) ?: return null
        return categoryDao.byName(guess)?.id
    }

    private companion object {
        /** Spec G7 rule 4: ≥3 of the last 4 agree. */
        const val LEARNED_THRESHOLD = 3
    }
}
