package dev.kosha.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's first real schema migration (v1 → v2, `net_worth_snapshots`).
 *
 * This does NOT use [MigrationHarness]'s `MigrationTestHelper`: that helper
 * validates the "before" state against a committed schema JSON asset
 * (`dev.kosha.core.database.KoshaDatabase/1.json`) — and this project has
 * never committed one (`core/database/schemas/` is generated fresh by CI
 * each run, not checked into git). Asking for it fails with
 * `FileNotFoundException` before a single migration statement runs.
 * Fixing that properly means committing the schema JSON for every version
 * going forward, starting now; until that exists, this test drives
 * `MIGRATION_1_2` directly against a bare v1 SQLite file (no tables besides
 * what the migration itself creates), which is enough to prove this
 * specific migration's SQL is correct without needing a historical fixture
 * that doesn't exist yet.
 */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {

    @Test
    fun migrate1To2_addsNetWorthSnapshotsTable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "migration-1-2-test.db"
        context.deleteDatabase(dbName)

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    // Nothing pre-existing matters for this migration — it only
                    // ever adds a brand-new table, so a v1 with zero tables is a
                    // faithful enough "before".
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)

        try {
            val db = helper.writableDatabase
            MIGRATION_1_2.migrate(db)

            db.execSQL(
                "INSERT INTO net_worth_snapshots (epochDay, assetsPaise, liabilitiesPaise, netPaise) " +
                    "VALUES (19000, 100000, 20000, 80000)",
            )
            db.query("SELECT epochDay, netPaise FROM net_worth_snapshots").use { cursor ->
                assertTrue("expected the row just inserted", cursor.moveToFirst())
                assertEquals(19000L, cursor.getLong(0))
                assertEquals(80000L, cursor.getLong(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }
}
