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

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("UPDATE accounts SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Delete
    suspend fun delete(account: AccountEntity)

    /**
     * currentBalance = openingBalance + Σ(parent txns), spec B5: recomputed,
     * never independently mutated. Children (splits) excluded via
     * parentTransactionId IS NULL.
     */
    @Query(
        """
        UPDATE accounts SET currentBalancePaise = openingBalancePaise + COALESCE(
            (SELECT SUM(CASE WHEN t.type = 'credit' THEN t.amountPaise ELSE -t.amountPaise END)
             FROM transactions t
             WHERE t.accountId = accounts.id AND t.parentTransactionId IS NULL
               AND t.status = 'committed'),
            0)
        WHERE id = :accountId
        """
    )
    suspend fun recomputeBalance(accountId: Long)
}
