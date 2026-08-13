package dev.kosha.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule

/**
 * Migration test harness (Phase-1 exit gate: "migration test harness in
 * place"). Exported schema JSON lands in `core/database/schemas/` (committed
 * from the CI build that first freezes the schema). Every schema change after
 * Phase 2 adds a `Migration` plus a test here that seeds a fixture DB at the
 * old version and asserts data survives `runMigrationsAndValidate`.
 */
abstract class MigrationHarness {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KoshaDatabase::class.java,
    )

    // First real migration test arrives with schema v2 (post-freeze), e.g.:
    // @Test fun migrate1To2() {
    //     helper.createDatabase(TEST_DB, 1).use { db -> /* seed v1 fixture */ }
    //     helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
    // }

    companion object {
        const val TEST_DB = "migration-test.db"
    }
}
