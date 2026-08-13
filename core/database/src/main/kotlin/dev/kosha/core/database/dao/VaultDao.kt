package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kosha.core.database.model.VaultEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ring-2 boundary (spec B4): only :feature:vault talks to this DAO, and the
 * export module has NO code path here — enforced by convention + Phase 8 tests.
 */
@Dao
interface VaultDao {
    @Insert
    suspend fun insert(entry: VaultEntryEntity): Long

    @Update
    suspend fun update(entry: VaultEntryEntity)

    @Delete
    suspend fun delete(entry: VaultEntryEntity)

    @Query("SELECT * FROM vault_entries ORDER BY label")
    fun observeAll(): Flow<List<VaultEntryEntity>>
}
