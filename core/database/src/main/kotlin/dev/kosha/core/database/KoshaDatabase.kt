package dev.kosha.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.GoalsDao
import dev.kosha.core.database.dao.MetaDao
import dev.kosha.core.database.dao.PlanningDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.dao.VaultDao
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AssetLiabilityEntity
import dev.kosha.core.database.model.BudgetEntity
import dev.kosha.core.database.model.CategoryEntity
import dev.kosha.core.database.model.ConstitutionRuleEntity
import dev.kosha.core.database.model.Converters
import dev.kosha.core.database.model.DebtAccountEntity
import dev.kosha.core.database.model.FinancialGoalEntity
import dev.kosha.core.database.model.IncomeSourceEntity
import dev.kosha.core.database.model.NetWorthSnapshotEntity
import dev.kosha.core.database.model.OcrTemplateEntity
import dev.kosha.core.database.model.PeriodSummaryEntity
import dev.kosha.core.database.model.RecurringRuleEntity
import dev.kosha.core.database.model.RuleViolationEntity
import dev.kosha.core.database.model.SavedQueryEntity
import dev.kosha.core.database.model.SmsPatternEntity
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TransactionEvidenceEntity
import dev.kosha.core.database.model.TransferEntity
import dev.kosha.core.database.model.VaultEntryEntity
import dev.kosha.core.database.model.WarrantyItemEntity

/**
 * Full B5 schema, shipped complete in Phase 1 to avoid migration churn.
 * Migration policy (spec B5): destructive migrations FORBIDDEN after
 * Phase 2 — every later schema change ships a tested Migration.
 *
 * v2 (`net_worth_snapshots`, additive): the app's first real migration —
 * see `MIGRATION_1_2` in `Migrations.kt` and `Migration1To2Test` in
 * `androidTest`, which is what actually exercises the policy above instead
 * of just stating it.
 */
@Database(
    entities = [
        AccountEntity::class,
        TransferEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        TransactionEvidenceEntity::class,
        SmsPatternEntity::class,
        OcrTemplateEntity::class,
        RecurringRuleEntity::class,
        BudgetEntity::class,
        IncomeSourceEntity::class,
        PeriodSummaryEntity::class,
        FinancialGoalEntity::class,
        DebtAccountEntity::class,
        AssetLiabilityEntity::class,
        VaultEntryEntity::class,
        ConstitutionRuleEntity::class,
        RuleViolationEntity::class,
        WarrantyItemEntity::class,
        SavedQueryEntity::class,
        NetWorthSnapshotEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KoshaDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun planningDao(): PlanningDao
    abstract fun goalsDao(): GoalsDao
    abstract fun metaDao(): MetaDao
    abstract fun vaultDao(): VaultDao

    companion object {
        const val NAME = "kosha.db"
    }
}
