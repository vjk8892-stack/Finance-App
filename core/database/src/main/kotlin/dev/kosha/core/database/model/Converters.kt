package dev.kosha.core.database.model

import androidx.room.TypeConverter

/** Enums persist as lowercase names — stable across enum reordering. */
class Converters {
    @TypeConverter fun accountTypeToDb(v: AccountType?): String? = v?.name?.lowercase()
    @TypeConverter fun accountTypeFromDb(v: String?): AccountType? = v?.let { AccountType.valueOf(it.uppercase()) }

    @TypeConverter fun categoryTypeToDb(v: CategoryType?): String? = v?.name?.lowercase()
    @TypeConverter fun categoryTypeFromDb(v: String?): CategoryType? = v?.let { CategoryType.valueOf(it.uppercase()) }

    @TypeConverter fun txnTypeToDb(v: TxnType?): String? = v?.name?.lowercase()
    @TypeConverter fun txnTypeFromDb(v: String?): TxnType? = v?.let { TxnType.valueOf(it.uppercase()) }

    @TypeConverter fun txnSourceToDb(v: TxnSource?): String? = v?.name?.lowercase()
    @TypeConverter fun txnSourceFromDb(v: String?): TxnSource? = v?.let { TxnSource.valueOf(it.uppercase()) }

    @TypeConverter fun moodToDb(v: MoodTag?): String? = v?.name?.lowercase()
    @TypeConverter fun moodFromDb(v: String?): MoodTag? = v?.let { MoodTag.valueOf(it.uppercase()) }

    @TypeConverter fun taxToDb(v: TaxTag?): String? = v?.name?.lowercase()
    @TypeConverter fun taxFromDb(v: String?): TaxTag? = v?.let { TaxTag.valueOf(it.uppercase()) }

    @TypeConverter fun budgetPeriodToDb(v: BudgetPeriod?): String? = v?.name?.lowercase()
    @TypeConverter fun budgetPeriodFromDb(v: String?): BudgetPeriod? = v?.let { BudgetPeriod.valueOf(it.uppercase()) }

    @TypeConverter fun incomeFreqToDb(v: IncomeFrequency?): String? = v?.name?.lowercase()
    @TypeConverter fun incomeFreqFromDb(v: String?): IncomeFrequency? = v?.let { IncomeFrequency.valueOf(it.uppercase()) }

    @TypeConverter fun recurFreqToDb(v: RecurringFrequency?): String? = v?.name?.lowercase()
    @TypeConverter fun recurFreqFromDb(v: String?): RecurringFrequency? = v?.let { RecurringFrequency.valueOf(it.uppercase()) }

    @TypeConverter fun goalKindToDb(v: GoalKind?): String? = v?.name?.lowercase()
    @TypeConverter fun goalKindFromDb(v: String?): GoalKind? = v?.let { GoalKind.valueOf(it.uppercase()) }

    @TypeConverter fun alKindToDb(v: AssetLiabilityKind?): String? = v?.name?.lowercase()
    @TypeConverter fun alKindFromDb(v: String?): AssetLiabilityKind? = v?.let { AssetLiabilityKind.valueOf(it.uppercase()) }

    @TypeConverter fun evidenceKindToDb(v: EvidenceKind?): String? = v?.name?.lowercase()
    @TypeConverter fun evidenceKindFromDb(v: String?): EvidenceKind? = v?.let { EvidenceKind.valueOf(it.uppercase()) }

    @TypeConverter fun sysKeyToDb(v: SystemCategoryKey?): String? = v?.name?.lowercase()
    @TypeConverter fun sysKeyFromDb(v: String?): SystemCategoryKey? = v?.let { SystemCategoryKey.valueOf(it.uppercase()) }

    @TypeConverter fun txnStatusToDb(v: TxnStatus?): String? = v?.name?.lowercase()
    @TypeConverter fun txnStatusFromDb(v: String?): TxnStatus? = v?.let { TxnStatus.valueOf(it.uppercase()) }
}
