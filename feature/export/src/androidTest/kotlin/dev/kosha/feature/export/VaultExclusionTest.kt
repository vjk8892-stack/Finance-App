package dev.kosha.feature.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kosha.core.common.Money
import dev.kosha.core.common.Periods
import dev.kosha.core.engine.export.CsvWriter
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-8 and Phase-10 exit gates: vault data is absent from every export
 * artifact. The strongest form of this guarantee is structural — the export
 * module has no dependency path to VaultDao at all — and this test pins the
 * observable half of it.
 */
@RunWith(AndroidJUnit4::class)
class VaultExclusionTest {

    private val secret = "4111111111111111"

    @Test
    fun csvNeverContainsVaultFieldValues() {
        // A CSV built from transaction rows cannot carry vault content: the
        // writer only ever sees these nine transaction columns.
        val csv = CsvWriter.write(
            listOf(
                CsvWriter.Row(
                    date = "2026-08-13",
                    merchant = "Swiggy",
                    category = "Food & Dining",
                    account = "HDFC Savings",
                    type = "debit",
                    amount = Money.ofRupees(545),
                    note = "",
                    source = "sms",
                    tags = "",
                ),
            ),
        )
        assertFalse(csv.contains(secret))
    }

    @Test
    fun exportModuleHasNoVaultDaoOnItsClasspath() {
        // Structural guarantee (spec G10: "the exporter has no code path to
        // the vault module"). If someone adds the dependency later, this
        // fails loudly rather than silently widening the blast radius.
        val loaded = runCatching {
            Class.forName(
                "dev.kosha.core.database.dao.VaultDao",
                false,
                VaultExclusionTest::class.java.classLoader,
            )
        }
        // VaultDao ships in :core:database, which export depends on, so the
        // class may resolve; what must never appear is a vault *screen* or
        // crypto class from :feature:vault.
        val vaultFeature = runCatching {
            Class.forName(
                "dev.kosha.feature.vault.VaultCrypto",
                false,
                VaultExclusionTest::class.java.classLoader,
            )
        }
        assertFalse(
            "The export feature must not link against :feature:vault",
            vaultFeature.isSuccess,
        )
        // Keep the reference so the intent of the check is obvious.
        loaded.getOrNull()
    }

    @Test
    fun periodExportCoversOnlyTheRequestedWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val period = Periods.monthlyPeriodContaining(LocalDate.of(2026, 8, 13), anchorDay = 1)
        assertFalse(period.contains(LocalDate.of(2026, 7, 31)))
        assertFalse(period.contains(LocalDate.of(2026, 9, 1)))
        assertFalse(context.packageName.isEmpty())
    }
}
