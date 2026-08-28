package dev.kosha.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's first real schema migration (v1 → v2, `net_worth_snapshots`).
 * Seeds a v1 fixture DB, runs `MIGRATION_1_2`, and asserts the new table is
 * both queryable and writable afterwards — exercising the migration policy
 * (spec B5) for real instead of only stating it.
 */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test : MigrationHarness() {

    @Test
    fun migrate1To2_addsNetWorthSnapshotsTable() {
        helper.createDatabase(TEST_DB, 1).apply {
            // v1 has no net_worth_snapshots table at all — nothing to seed.
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.execSQL(
            "INSERT INTO net_worth_snapshots (epochDay, assetsPaise, liabilitiesPaise, netPaise) " +
                "VALUES (19000, 100000, 20000, 80000)",
        )
        db.query("SELECT epochDay, netPaise FROM net_worth_snapshots").use { cursor ->
            assertTrue("expected the row just inserted", cursor.moveToFirst())
            assertEquals(19000L, cursor.getLong(0))
            assertEquals(80000L, cursor.getLong(1))
        }
    }
}
