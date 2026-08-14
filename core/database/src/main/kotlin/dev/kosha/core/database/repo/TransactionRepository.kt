package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.CategoryState
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TransactionEvidenceEntity
import dev.kosha.core.database.model.TxnStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

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
) {

    fun observeLedger(): Flow<List<LedgerRow>> = transactionDao.observeLedger()

    suspend fun byId(id: Long): TransactionEntity? = transactionDao.byId(id)

    suspend fun add(txn: TransactionEntity): Long {
        val id = transactionDao.insert(txn)
        accountDao.recomputeBalance(txn.accountId)
        return id
    }

    suspend fun update(txn: TransactionEntity) {
        transactionDao.update(txn.copy(updatedAtMillis = System.currentTimeMillis()))
        accountDao.recomputeBalance(txn.accountId)
    }

    suspend fun delete(id: Long) {
        val existing = transactionDao.byId(id) ?: return
        transactionDao.deleteWithChildren(id)
        accountDao.recomputeBalance(existing.accountId)
    }

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
        accountDao.recomputeBalance(parent.accountId)
        return DeletedTransaction(listOf(parent) + children, evidence)
    }

    suspend fun deleteAllCapturing(ids: List<Long>): DeletedTransaction? {
        if (ids.isEmpty()) return null
        val rows = ids.mapNotNull { transactionDao.byId(it) } + ids.flatMap { transactionDao.childrenOf(it) }
        val evidence = ids.flatMap { transactionDao.evidenceFor(it) }
        val accountIds = transactionDao.accountIdsFor(ids)
        transactionDao.deleteBatch(ids)
        accountIds.forEach { accountDao.recomputeBalance(it) }
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
        deleted.rows.map { it.accountId }.distinct().forEach { accountDao.recomputeBalance(it) }
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
        accountIds.forEach { accountDao.recomputeBalance(it) }
    }

    suspend fun recategorizeMerchantCapturing(
        merchantNormalized: String,
        categoryId: Long?,
    ): List<CategoryState> {
        val before = transactionDao.categoryStateForMerchant(merchantNormalized)
        recategorizeMerchant(merchantNormalized, categoryId)
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
        accountIds.forEach { accountDao.recomputeBalance(it) }
    }

    suspend fun deleteAll(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Read the accounts BEFORE the rows disappear.
        val accountIds = transactionDao.accountIdsFor(ids)
        transactionDao.deleteBatch(ids)
        accountIds.forEach { accountDao.recomputeBalance(it) }
    }
}
