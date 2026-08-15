package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kosha.core.database.model.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE isActive = 1 ORDER BY id")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 ORDER BY id")
    suspend fun activeAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>

    /** Inactive ones included: their balances still have to stay consistent. */
    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun allAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("UPDATE accounts SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Delete
    suspend fun delete(account: AccountEntity)

    /**
     * currentBalance = openingBalance + Σ(parent txns from [fromMillis]),
     * spec B5: recomputed, never independently mutated. Children (splits)
     * excluded via parentTransactionId IS NULL.
     *
     * [fromMillis] is the tracking boundary, 0 when everything is tracked. It
     * belongs in the SAME sum the ledger shows, or the balance and the rows
     * under it cover different periods and adding the rows up stops matching
     * the figure above them. With a boundary set, the opening balance means
     * "what was in this account on that date".
     */
    @Query(
        """
        UPDATE accounts SET currentBalancePaise = openingBalancePaise + COALESCE(
            (SELECT SUM(CASE WHEN t.type = 'credit' THEN t.amountPaise ELSE -t.amountPaise END)
             FROM transactions t
             WHERE t.accountId = accounts.id AND t.parentTransactionId IS NULL
               AND t.status = 'committed' AND t.timestampMillis >= :fromMillis),
            0)
        WHERE id = :accountId
        """
    )
    suspend fun recomputeBalance(accountId: Long, fromMillis: Long)
}
