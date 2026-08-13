package dev.kosha.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Full Room schema per spec B5, shipped complete in Phase 1 (even where the
 * feature arrives later) to avoid migration churn. All amounts are Long
 * paise; all timestamps are epoch millis UTC with a recorded zone offset
 * where dedup windows care (spec G1).
 */

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val last4: String? = null,
    val openingBalancePaise: Long = 0,
    // currentBalance = openingBalance + Σ(parent transactions). Recomputed,
    // never independently mutated (spec B5) — cached here for display.
    val currentBalancePaise: Long = 0,
    val isActive: Boolean = true,
    /** Index into the 8-swatch account palette (spec G3). */
    val colorToken: Int = 0,
)

@Entity(
    tableName = "categories",
    indices = [Index("parentId")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: CategoryType,
    /** Icon token name resolved by the design system. */
    val icon: String,
    val parentId: Long? = null,
    val isSystem: Boolean = false,
    /** Stable key for system-reserved rows; null for user/seed categories. */
    val systemKey: SystemCategoryKey? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("accountId", "timestampMillis"),
        Index("categoryId"),
        Index("reference"),
        Index("dedupGroupId"),
        Index("parentTransactionId"),
        Index("recurringRuleId"),
        Index("timestampMillis"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val categoryId: Long? = null,
    val amountPaise: Long,
    val type: TxnType,
    val merchantRaw: String? = null,
    val merchantNormalized: String? = null,
    val note: String? = null,
    val timestampMillis: Long,
    /** Zone offset (minutes) recorded at capture — dedup windows around midnight. */
    val tzOffsetMinutes: Int = 0,
    val source: TxnSource,
    val confidence: Double = 1.0,
    val moodTag: MoodTag? = null,
    val taxTag: TaxTag? = null,
    val recurringRuleId: Long? = null,
    val dedupGroupId: String? = null,
    /**
     * Enables SPLIT + line items (spec B5): children carry categories and sum
     * to the parent amount; parent carries the real money movement. Category
     * totals use children when present (else parent); account balance math
     * uses parents only. Never both.
     */
    val parentTransactionId: Long? = null,
    /** Bank reference / UPI UTR — strongest dedup key. */
    val reference: String? = null,
    /** COMMITTED rows count everywhere; PENDING_REVIEW rows only in the queue. */
    val status: TxnStatus = TxnStatus.COMMITTED,
    val reviewReason: String? = null,
    /** Set when the dedup engine flagged an unprovable duplicate (B3 rule 2). */
    val possibleDuplicateOfId: Long? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Links a debit in one account to a credit in another (spec B5 Transfer). */
@Entity(
    tableName = "transfers",
    indices = [Index("fromTransactionId", unique = true), Index("toTransactionId", unique = true)],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromTransactionId: Long,
    val toTransactionId: Long,
)

@Entity(
    tableName = "transaction_evidence",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId")],
)
data class TransactionEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val kind: EvidenceKind,
    /** Encrypted at rest by Ring 1 (whole-DB SQLCipher). */
    val payload: String,
)

@Entity(tableName = "sms_patterns")
data class SmsPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderIdPattern: String,
    val regexTemplate: String,
    /** JSON: capture-group → field mapping. */
    val fieldMap: String,
    val bankLabel: String,
    val isActive: Boolean = true,
    val version: Int = 1,
)

@Entity(tableName = "ocr_templates")
data class OcrTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** phonepe | gpay | paytm | generic-bill */
    val appLabel: String,
    val anchorKeywords: String,
    val fieldHeuristics: String,
    val version: Int = 1,
)

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val categoryId: Long? = null,
    val amountPaise: Long? = null,
    val merchantPattern: String? = null,
    val frequency: RecurringFrequency,
    val nextDueDateMillis: Long,
    val autoLog: Boolean = false,
    val isCreditCardDue: Boolean = false,
    val label: String,
    val isActive: Boolean = true,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** null = overall budget. */
    val categoryId: Long? = null,
    val period: BudgetPeriod = BudgetPeriod.MONTHLY,
    val limitPaise: Long,
    val alertThresholdPct: Int = 80,
    val startDateMillis: Long,
    val isActive: Boolean = true,
)

@Entity(tableName = "income_sources")
data class IncomeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountPaise: Long,
    val frequency: IncomeFrequency = IncomeFrequency.MONTHLY,
    /** Day of month the credit is expected (1–28), if fixed. */
    val expectedDay: Int? = null,
    val accountId: Long? = null,
    val isActive: Boolean = true,
)

/** Persisted at period close; immutable ledger of history (spec B5). */
@Entity(tableName = "period_summaries", indices = [Index("periodStartMillis", unique = true)])
data class PeriodSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val expectedIncomePaise: Long,
    val actualIncomePaise: Long,
    val totalExpensePaise: Long,
    val savingsGapPaise: Long,
    /** expectedIncome − actualIncome − … : income the user expected but couldn't trace. */
    val untrackedGapPaise: Long,
    val closedAtMillis: Long,
    val notes: String? = null,
)

@Entity(tableName = "financial_goals")
data class FinancialGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetAmountPaise: Long,
    val targetDateMillis: Long? = null,
    val allocatedPaise: Long = 0,
    val priority: Int = 0,
    val linkedAccountId: Long? = null,
    val kind: GoalKind = GoalKind.SINKING_FUND,
)

/**
 * Authoritative source for any tracked loan/EMI (spec B5): net worth
 * auto-includes remaining balance as a liability — never re-entered in
 * AssetLiability.
 */
@Entity(tableName = "debt_accounts")
data class DebtAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val principalPaise: Long,
    /** Annual rate in basis points (e.g. 950 = 9.50%) — never floating point. */
    val rateBps: Int,
    val emiAmountPaise: Long,
    val tenureMonths: Int,
    val startDateMillis: Long,
)

@Entity(tableName = "assets_liabilities")
data class AssetLiabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: AssetLiabilityKind,
    val valuePaise: Long,
    val valuationDateMillis: Long,
    val notes: String? = null,
)

/** Ring-2 encrypted (spec B4): fields blob is ciphertext, never plaintext. */
@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** account | card | custom */
    val kind: String,
    val fieldsEncrypted: ByteArray,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "constitution_rules")
data class ConstitutionRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleText: String,
    /** SavedQuery filter grammar + comparator (spec G12); null = free-text rule. */
    val machineCheck: String? = null,
    val isActive: Boolean = true,
)

@Entity(tableName = "rule_violations", indices = [Index("ruleId")])
data class RuleViolationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val transactionId: Long? = null,
    val periodId: Long? = null,
    val timestampMillis: Long,
)

@Entity(tableName = "warranty_items")
data class WarrantyItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long? = null,
    val itemName: String,
    val purchaseDateMillis: Long,
    val warrantyMonths: Int,
    val expiryDateMillis: Long,
    val receiptPhotoUri: String? = null,
)

@Entity(tableName = "saved_queries")
data class SavedQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filterJson: String,
    val sortJson: String? = null,
)
