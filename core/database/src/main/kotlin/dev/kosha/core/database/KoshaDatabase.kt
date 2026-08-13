package dev.kosha.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Phase-0 wiring proof: a single key-value entity behind the encrypted DB.
 * The full B5 schema replaces this in Phase 1. Destructive migration is
 * allowed only until Phase 2 (spec B5 migration policy).
 */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface AppMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AppMetaEntity)

    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    suspend fun get(key: String): String?
}

@Database(
    entities = [AppMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KoshaDatabase : RoomDatabase() {
    abstract fun appMetaDao(): AppMetaDao

    companion object {
        const val NAME = "kosha.db"
    }
}
