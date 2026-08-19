package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TransactionEvidenceEntity
import dev.kosha.core.database.model.TransferEntity
import kotlinx.coroutines.flow.Flow

/** id → category, for restoring a bulk recategorization. */
data class CategoryState(val id: Long, val categoryId: Long?)

/** Row projection for the ledger list: transaction + display names. */
data class LedgerRow(
    @Embedded val txn: TransactionEntity,
    val categoryName: String?,
    val categoryIcon: String?,
    val accountName: String,
    val accountColorToken: Int,
    /**
     * The photo this row was read from, if any. Carried on the row itself so
     * the ledger can show a thumbnail without a per-row query — a receipt you
     * captured is only evidence if you can see that it is still attached.
     */
    val photoUri: String? = null,
)

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id OR parentTransactionId = :id")
    suspend fun deleteWithChildren(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @RoomTransaction
    @Query(
        """
        SELECT t.*, c.name AS categoryName, c.icon AS categoryIcon,
               a.name AS accountName, a.colorToken AS accountColorToken,
               (SELECT e.payload FROM transaction_evidence e
                 WHERE e.transactionId = t.id AND e.kind = 'photo_uri'
                 ORDER BY e.id LIMIT 1) AS photoUri
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        JOIN accounts a ON a.id = t.accountId
        WHERE t.parentTransactionId IS NULL AND t.status = 'committed'
        ORDER BY t.timestampMillis DESC
        """
    )
    /**
     * EVERY committed parent row, deliberately unbounded.
     *
     * This was capped at 500 and the cap reached far further than the list it
     * was written for: the account statement's opening + in − out = now, the
     * natural-language query, and the tracked/hidden counts all read this same
     * flow. Past 500 transactions the statement stopped reconciling and the
     * ledger simply ended, with nothing on screen to say anything was missing —
     * a number that is quietly incomplete is worse than one that is obviously
     * absent. A year of captured bank messages crosses 500 easily.
     *
     * The cost is bounded: the row is a transaction plus four display columns,
     * so even a decade of data is a few megabytes, and the list is rendered
     * lazily.
     */
    fun observeLedger(): Flow<List<LedgerRow>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE timestampMillis >= :fromMillis AND timestampMillis < :toMillis
        ORDER BY timestampMillis DESC
        """
    )
    suspend fun inWindow(fromMillis: Long, toMillis: Long): List<TransactionEntity>

    // --- Review queue (spec B3/C2) ---

    @RoomTransaction
    @Query(
        """
        SELECT t.*, c.name AS categoryName, c.icon AS categoryIcon,
               a.name AS accountName, a.colorToken AS accountColorToken,
               (SELECT e.payload FROM transaction_evidence e
                 WHERE e.transactionId = t.id AND e.kind = 'photo_uri'
                 ORDER BY e.id LIMIT 1) AS photoUri
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        JOIN accounts a ON a.id = t.accountId
        WHERE t.status = 'pending_review'
        ORDER BY t.timestampMillis ASC
        """
    )
    fun observeReviewQueue(): Flow<List<LedgerRow>>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'pending_review'")
    fun observeReviewCount(): Flow<Int>

    @Query("SELECT MIN(timestampMillis) FROM transactions WHERE status = 'pending_review'")
    fun observeOldestReviewMillis(): Flow<Long?>

    @Query(
        """
        UPDATE transactions SET status = 'committed', reviewReason = NULL,
               possibleDuplicateOfId = NULL, updatedAtMillis = :now
        WHERE id = :id
        """
    )
    suspend fun approveReview(id: Long, now: Long)

    /**
     * Auto-categorization source (spec G7 rule 4): categories of the last
     * [limit] committed txns for a normalized merchant, newest first.
     */
    @Query(
        """
        SELECT categoryId FROM transactions
        WHERE merchantNormalized = :merchantNormalized AND status = 'committed'
              AND categoryId IS NOT NULL
        ORDER BY timestampMillis DESC LIMIT :limit
        """
    )
    suspend fun recentCategoriesForMerchant(merchantNormalized: String, limit: Int = 4): List<Long>

    @Query("SELECT * FROM transactions WHERE reference = :reference LIMIT 1")
    suspend fun byReference(reference: String): TransactionEntity?

    @Query("SELECT MIN(timestampMillis) FROM transactions WHERE status = 'committed'")
    suspend fun oldestTimestamp(): Long?

    /** Distinct normalized merchants — the NLU's merchant vocabulary. */
    @Query(
        """
        SELECT DISTINCT merchantNormalized FROM transactions
        WHERE merchantNormalized IS NOT NULL AND merchantNormalized != ''
        ORDER BY merchantNormalized
        """
    )
    suspend fun knownMerchants(): List<String>

    /** Ledger revisions that should refresh derived period/budget state. */
    @Query("SELECT COUNT(*) FROM transactions")
    fun observeTransactionCount(): Flow<Int>

    @Query("UPDATE transactions SET categoryId = :categoryId, updatedAtMillis = :now WHERE id = :id")
    suspend fun recategorize(id: Long, categoryId: Long?, now: Long)

    // --- Bulk operations (spec C2.4: a queue you cannot drain is a queue
    // that stops being read at all) ---

    /**
     * Approve several at once. Same effect as [approveReview] per row; done in
     * one statement so 117 rows is one write, not 117.
     */
    @Query(
        """
        UPDATE transactions SET status = 'committed', reviewReason = NULL,
               possibleDuplicateOfId = NULL, updatedAtMillis = :now
        WHERE id IN (:ids)
        """
    )
    suspend fun approveReviewBatch(ids: List<Long>, now: Long)

    @Query("DELETE FROM transactions WHERE id IN (:ids) OR parentTransactionId IN (:ids)")
    suspend fun deleteBatch(ids: List<Long>)

    /** Apply one category to every transaction of a merchant, at any status. */
    @Query(
        """
        UPDATE transactions SET categoryId = :categoryId, updatedAtMillis = :now
        WHERE merchantNormalized = :merchantNormalized
        """
    )
    suspend fun recategorizeMerchant(merchantNormalized: String, categoryId: Long?, now: Long)

    /**
     * Committed rows that still have no real category, for the retro pass.
     * [uncategorizedId] is the system Uncategorized row, which counts as "not
     * categorized" just as much as NULL does.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE status = 'committed'
          AND merchantNormalized IS NOT NULL AND merchantNormalized != ''
          AND (categoryId IS NULL OR categoryId = :uncategorizedId)
        """
    )
    suspend fun uncategorizedWithMerchant(uncategorizedId: Long?): List<TransactionEntity>

    /** Re-insert restored rows keeping their original ids (undo). */
    @Insert
    suspend fun insertAll(txns: List<TransactionEntity>): List<Long>

    @Insert
    suspend fun insertEvidenceAll(evidence: List<TransactionEvidenceEntity>): List<Long>

    /** Put a row back in the review queue exactly as it was (undo). */
    @Query(
        """
        UPDATE transactions SET status = :status, reviewReason = :reason,
               updatedAtMillis = :now
        WHERE id = :id
        """
    )
    suspend fun restoreStatus(id: Long, status: TxnStatus, reason: String?, now: Long)

    /** Split lines of a parent — deleted with it, so restored with it too. */
    @Query("SELECT * FROM transactions WHERE parentTransactionId = :parentId")
    suspend fun childrenOf(parentId: Long): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    /** Rows of a merchant with their current category, captured before a bulk change. */
    @Query("SELECT id, categoryId FROM transactions WHERE merchantNormalized = :merchantNormalized")
    suspend fun categoryStateForMerchant(merchantNormalized: String): List<CategoryState>

    /** Accounts touched by a set of rows, so balances can be recomputed once each. */
    @Query("SELECT DISTINCT accountId FROM transactions WHERE id IN (:ids)")
    suspend fun accountIdsFor(ids: List<Long>): List<Long>

    // Evidence
    @Insert
    suspend fun insertEvidence(evidence: TransactionEvidenceEntity): Long

    @Query("SELECT * FROM transaction_evidence WHERE transactionId = :txnId")
    suspend fun evidenceFor(txnId: Long): List<TransactionEvidenceEntity>

    // Transfers
    @Insert
    suspend fun insertTransfer(transfer: TransferEntity): Long

    @Query("SELECT * FROM transfers WHERE fromTransactionId = :txnId OR toTransactionId = :txnId LIMIT 1")
    suspend fun transferFor(txnId: Long): TransferEntity?

    /** Transaction ids that are a leg of any transfer — excluded from income/expense math (spec B5). */
    @Query("SELECT fromTransactionId FROM transfers UNION SELECT toTransactionId FROM transfers")
    suspend fun transferLegIds(): List<Long>
}
