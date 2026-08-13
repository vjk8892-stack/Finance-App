package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TransactionEvidenceEntity
import dev.kosha.core.database.model.TransferEntity
import kotlinx.coroutines.flow.Flow

/** Row projection for the ledger list: transaction + display names. */
data class LedgerRow(
    @Embedded val txn: TransactionEntity,
    val categoryName: String?,
    val categoryIcon: String?,
    val accountName: String,
    val accountColorToken: Int,
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
               a.name AS accountName, a.colorToken AS accountColorToken
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        JOIN accounts a ON a.id = t.accountId
        WHERE t.parentTransactionId IS NULL
        ORDER BY t.timestampMillis DESC
        LIMIT :limit
        """
    )
    fun observeLedger(limit: Int = 500): Flow<List<LedgerRow>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE timestampMillis >= :fromMillis AND timestampMillis < :toMillis
        ORDER BY timestampMillis DESC
        """
    )
    suspend fun inWindow(fromMillis: Long, toMillis: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE reference = :reference LIMIT 1")
    suspend fun byReference(reference: String): TransactionEntity?

    @Query("UPDATE transactions SET categoryId = :categoryId, updatedAtMillis = :now WHERE id = :id")
    suspend fun recategorize(id: Long, categoryId: Long?, now: Long)

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
