package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kosha.core.database.model.AssetLiabilityEntity
import dev.kosha.core.database.model.DebtAccountEntity
import dev.kosha.core.database.model.FinancialGoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalsDao {
    @Insert
    suspend fun insertGoal(goal: FinancialGoalEntity): Long

    @Update
    suspend fun updateGoal(goal: FinancialGoalEntity)

    @Delete
    suspend fun deleteGoal(goal: FinancialGoalEntity)

    @Query("SELECT * FROM financial_goals ORDER BY priority, id")
    fun observeGoals(): Flow<List<FinancialGoalEntity>>

    @Query("SELECT * FROM financial_goals WHERE kind = 'emergency_fund' LIMIT 1")
    suspend fun emergencyFund(): FinancialGoalEntity?

    @Insert
    suspend fun insertDebt(debt: DebtAccountEntity): Long

    @Update
    suspend fun updateDebt(debt: DebtAccountEntity)

    @Delete
    suspend fun deleteDebt(debt: DebtAccountEntity)

    @Query("SELECT * FROM debt_accounts ORDER BY id")
    fun observeDebts(): Flow<List<DebtAccountEntity>>

    @Insert
    suspend fun insertAssetLiability(item: AssetLiabilityEntity): Long

    @Update
    suspend fun updateAssetLiability(item: AssetLiabilityEntity)

    @Delete
    suspend fun deleteAssetLiability(item: AssetLiabilityEntity)

    @Query("SELECT * FROM assets_liabilities ORDER BY kind, id")
    fun observeAssetsLiabilities(): Flow<List<AssetLiabilityEntity>>
}
