package dev.kosha.core.database.model

/** Persisted as lowercase TEXT via [Converters] — safe to reorder, never rename. */
enum class AccountType { BANK, CASH, CARD, WALLET, MEALCARD }

enum class CategoryType { EXPENSE, INCOME }

enum class TxnType { DEBIT, CREDIT }

enum class TxnSource { SMS, OCR, MANUAL, RECURRING }

enum class MoodTag { IMPULSE, PLANNED, NECESSARY }

enum class TaxTag { TAX_80C, TAX_80D, HRA }

enum class BudgetPeriod { WEEKLY, MONTHLY }

enum class IncomeFrequency { MONTHLY, ONE_TIME, VARIABLE }

enum class RecurringFrequency { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY }

enum class GoalKind { SINKING_FUND, EMERGENCY_FUND }

enum class AssetLiabilityKind { ASSET, LIABILITY }

enum class EvidenceKind { SMS_TEXT, PHOTO_URI, UTR }

/** Stable identity for the system-reserved categories (spec G2) — survives rename. */
enum class SystemCategoryKey { TRANSFERS, CASH_WITHDRAWAL, UNCATEGORIZED }
