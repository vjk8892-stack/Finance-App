package dev.kosha.core.database.repo

import dev.kosha.core.common.Money
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.CategoryState
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TransactionEvidenceEntity
import dev.kosha.core.database.model.TxnStatus
import javax.inject.Inject
import javax.inject.Singleton
import dev.kosha.core.database.settings.TrackingWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Single write path for transactions.
 *
 * PIPELINE INVARIANT (spec B2/B3): ingest features never call this directly
 * for captured data — SMS/OCR go through the ingestion pipeline, which is the
 * only other caller. Manual entry and recurring instances arrive pre-trusted.
 */
@Singleton
class TransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val balanceMaintainer: BalanceMaintainer,
    private val trackingWindow: TrackingWindow,
) {

    /**
     * Committed rows from the tracking boundary onwards.
     *
     * Filtered here rather than in each screen: the ledger, the account
     * statement and the natural-language query all read this one flow, and a
     * boundary honoured by two of the three would show three different
     * histories of the same month.
     */
    fun observeLedger(): Flow<List<LedgerRow>> =
        combine(transactionDao.observeLedger(), trackingWindow.startMillis) { rows, from ->
            if (from <= 0L) rows else rows.filter { it.txn.timestampMillis >= from }
        }

    /**
     * The review queue, boundary-filtered for the same reason as the ledger:
     * approving a row that will then be hidden is a wasted decision, and a
     * queue badge counting invisible rows can never be cleared.
     */
    fun observeReviewQueue(): Flow<List<LedgerRow>> =
        combine(transactionDao.observeReviewQueue(), trackingWindow.startMillis) { rows, from ->
            if (from <= 0L) rows else rows.filter { it.txn.timestampMillis >= from }
        }

    suspend fun byId(id: Long): TransactionEntity? = transactionDao.byId(id)

    suspend fun add(txn: TransactionEntity): Long {
        val id = transactionDao.insert(txn)
        balanceMaintainer.recompute(txn.accountId)
        return id
    }

    suspend fun update(txn: TransactionEntity) {
        transactionDao.update(txn.copy(updatedAtMillis = System.currentTimeMillis()))
        balanceMaintainer.recompute(txn.accountId)
    }

    suspend fun delete(id: Long) {
        val existing = transactionDao.byId(id) ?: return
        transactionDao.deleteWithChildren(id)
        balanceMaintainer.recompute(existing.accountId)
    }

    /** One line of a split: part of the parent's amount, under its own category. */
    data class SplitLine(val categoryId: Long?, val amount: Money, val note: String? = null)

    /**
     * Divides one transaction across several categories.
     *
     * The schema, the period maths and every read path have supported splits
     * since Phase 1 — parents are excluded from category breakdowns when they
     * have children, children are excluded from balances and exports — but
     * nothing in the app could create one. A ₹4,000 supermarket bill that is
     * half groceries and half a birthday present had to be filed as one or the
     * other, or typed in twice.
     *
     * Children are read-only satellites of the parent: they carry no account
     * effect of their own (the money moved once), and deleting the parent
     * deletes them.
     *
     * @return false when the lines do not add up to the parent exactly.
     * Anything else leaves the category breakdown disagreeing with the total
     * it is a breakdown OF, which is worse than not splitting at all.
     */
    suspend fun split(parentId: Long, lines: List<SplitLine>): Boolean {
        val parent = transactionDao.byId(parentId) ?: return false
        if (parent.parentTransactionId != null) return false
        if (lines.size < 2) return false
        if (lines.any { it.amount.paise <= 0 }) return false
        if (lines.sumOf { it.amount.paise } != parent.amountPaise) return false

        // Replace rather than append: splitting twice should give the second
        // answer, not both answers added together.
        transactionDao.deleteChildrenOf(parentId)
        val now = System.currentTimeMillis()
        lines.forEach { line ->
            transactionDao.insert(
                parent.copy(
                    id = 0,
                    parentTransactionId = parentId,
                    categoryId = line.categoryId,
                    amountPaise = line.amount.paise,
                    note = line.note,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
        }
        return true
    }

    /** Drops the split lines, leaving the transaction whole again. */
    suspend fun unsplit(parentId: Long) {
        transactionDao.deleteChildrenOf(parentId)
    }

    suspend fun splitLines(parentId: Long): List<TransactionEntity> =
        transactionDao.childrenOf(parentId)

    // --- Undo ---
    //
    // Every destructive action captures enough to put things back. Room only
    // auto-generates an id when it is 0, so re-inserting a captured row with
    // its original id restores it exactly — no schema change, no tombstones,
    // and references elsewhere keep pointing at the right row.

    /** A removed transaction, its split children and its evidence. */
    data class DeletedTransaction(
        val rows: List<TransactionEntity>,
        val evidence: List<TransactionEvidenceEntity>,
    )

    suspend fun deleteCapturing(id: Long): DeletedTransaction? {
        val parent = transactionDao.byId(id) ?: return null
        // deleteWithChildren removes the split lines as well, so an undo that
        // only restored the parent would silently drop them and leave the
        // category breakdown wrong.
        val children = transactionDao.childrenOf(id)
        val evidence = transactionDao.evidenceFor(id)
        transactionDao.deleteWithChildren(id)
        balanceMaintainer.recompute(parent.accountId)
        return DeletedTransaction(listOf(parent) + children, evidence)
    }

    suspend fun deleteAllCapturing(ids: List<Long>): DeletedTransaction? {
        if (ids.isEmpty()) return null
        val rows = ids.mapNotNull { transactionDao.byId(it) } + ids.flatMap { transactionDao.childrenOf(it) }
        val evidence = ids.flatMap { transactionDao.evidenceFor(it) }
        val accountIds = transactionDao.accountIdsFor(ids)
        transactionDao.deleteBatch(ids)
        accountIds.forEach { balanceMaintainer.recompute(it) }
        return DeletedTransaction(rows, evidence)
    }

    suspend fun restore(deleted: DeletedTransaction) {
        if (deleted.rows.isEmpty()) return
        // Parents first: a child's parentTransactionId must have something to
        // point at by the time it lands.
        val (parents, children) = deleted.rows.partition { it.parentTransactionId == null }
        transactionDao.insertAll(parents)
        if (children.isNotEmpty()) transactionDao.insertAll(children)
        if (deleted.evidence.isNotEmpty()) transactionDao.insertEvidenceAll(deleted.evidence)
        deleted.rows.map { it.accountId }.distinct().forEach { balanceMaintainer.recompute(it) }
    }

    /** What a row looked like in the review queue, so approval is reversible. */
    data class ReviewState(val id: Long, val status: TxnStatus, val reason: String?)

    suspend fun approveAllCapturing(ids: List<Long>): List<ReviewState> {
        if (ids.isEmpty()) return emptyList()
        val before = ids.mapNotNull { id ->
            transactionDao.byId(id)?.let { ReviewState(it.id, it.status, it.reviewReason) }
        }
        approveAll(ids)
        return before
    }

    suspend fun restoreReviewStates(states: List<ReviewState>) {
        if (states.isEmpty()) return
        val now = System.currentTimeMillis()
        states.forEach { transactionDao.restoreStatus(it.id, it.status, it.reason, now) }
        val accountIds = transactionDao.accountIdsFor(states.map { it.id })
        accountIds.forEach { balanceMaintainer.recompute(it) }
    }

    suspend fun recategorizeMerchantCapturing(
        merchantNormalized: String,
        categoryId: Long?,
    ): List<CategoryState> {
        val before = transactionDao.categoryStateForMerchant(merchantNormalized)
        recategorizeMerchant(merchantNormalized, categoryId)
        return before
    }

    /**
     * Recategorize an explicit set of rows, capturing what they were.
     *
     * Separate from the by-merchant version because a multi-select is a choice
     * about THESE rows: picking three of a merchant's twenty and having all
     * twenty change would be the app overruling the selection it just asked
     * for.
     */
    suspend fun recategorizeAllCapturing(ids: List<Long>, categoryId: Long?): List<CategoryState> {
        val before = transactionDao.categoryStateFor(ids)
        transactionDao.recategorizeBatch(ids, categoryId, System.currentTimeMillis())
        return before
    }

    suspend fun restoreCategories(states: List<CategoryState>) {
        val now = System.currentTimeMillis()
        states.forEach { transactionDao.recategorize(it.id, it.categoryId, now) }
    }

    suspend fun recategorize(id: Long, categoryId: Long?) {
        transactionDao.recategorize(id, categoryId, System.currentTimeMillis())
    }

    /** Every transaction of a merchant, in one decision (spec G7). */
    suspend fun recategorizeMerchant(merchantNormalized: String, categoryId: Long?) {
        transactionDao.recategorizeMerchant(merchantNormalized, categoryId, System.currentTimeMillis())
    }

    /**
     * Approve a batch. Balances are recomputed once per affected account
     * rather than once per row — approving a hundred rows one at a time would
     * recompute the same account a hundred times.
     */
    suspend fun approveAll(ids: List<Long>) {
        if (ids.isEmpty()) return
        val accountIds = transactionDao.accountIdsFor(ids)
        transactionDao.approveReviewBatch(ids, System.currentTimeMillis())
        accountIds.forEach { balanceMaintainer.recompute(it) }
    }

    suspend fun deleteAll(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Read the accounts BEFORE the rows disappear.
        val accountIds = transactionDao.accountIdsFor(ids)
        transactionDao.deleteBatch(ids)
        accountIds.forEach { balanceMaintainer.recompute(it) }
    }
}
