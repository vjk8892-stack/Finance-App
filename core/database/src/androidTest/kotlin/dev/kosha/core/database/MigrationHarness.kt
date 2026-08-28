package dev.kosha.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule

/**
 * Migration test harness (Phase-1 exit gate: "migration test harness in
 * place"). `MigrationTestHelper.createDatabase` needs a committed schema
 * JSON asset for the "before" version
 * (`dev.kosha.core.database.KoshaDatabase/<version>.json`) — CI's "Export
 * Room schemas" step only ever regenerates the CURRENT version into an
 * untracked `core/database/schemas/`, and none has ever been committed to
 * git. The first real migration (v1 → v2, `net_worth_snapshots`) hit exactly
 * this: `Migration1To2Test` couldn't use this harness because `1.json` does
 * not exist, and tests `MIGRATION_1_2` directly against a bare SQLite file
 * instead. Whoever adds the NEXT migration should commit
 * `core/database/schemas/dev.kosha.core.database.KoshaDatabase/2.json` (copy
 * it out of a CI run's workspace, or a local build once the Android SDK is
 * available) before relying on this class — with that file committed, this
 * harness works exactly as written below.
 */
abstract class MigrationHarness {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KoshaDatabase::class.java,
    )

    // Once a schema JSON is committed for the version being migrated FROM:
    // @Test fun migrateNToNPlus1() {
    //     helper.createDatabase(TEST_DB, N).use { db -> /* seed an N fixture */ }
    //     helper.runMigrationsAndValidate(TEST_DB, N + 1, true, MIGRATION_N_NPLUS1)
    // }

    companion object {
        const val TEST_DB = "migration-test.db"
    }
}
