package dev.kosha.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v1 → v2: adds `net_worth_snapshots` (the Net Worth trend line).
 * Purely additive — no existing table is touched — so this is the safest
 * possible first exercise of the "tested Migration" policy (spec B5).
 * Column types and defaults must match [dev.kosha.core.database.model.NetWorthSnapshotEntity]
 * exactly, since `Migration1To2Test` validates the result against Room's own
 * exported schema, not just against this SQL running without error.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `net_worth_snapshots` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `epochDay` INTEGER NOT NULL,
                `assetsPaise` INTEGER NOT NULL,
                `liabilitiesPaise` INTEGER NOT NULL,
                `netPaise` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
