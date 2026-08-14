package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.LedgerRow
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.TransactionEntity
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
