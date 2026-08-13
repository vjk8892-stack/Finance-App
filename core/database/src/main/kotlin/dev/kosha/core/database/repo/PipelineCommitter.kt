package dev.kosha.core.database.repo

import dev.kosha.core.database.dao.AccountDao
import dev.kosha.core.database.dao.CategoryDao
import dev.kosha.core.database.dao.TransactionDao
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.model.EvidenceKind
import dev.kosha.core.database.model.SystemCategoryKey
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TransactionEvidenceEntity
import dev.kosha.core.database.model.TransferEntity
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.engine.pipeline.DedupEngine
import dev.kosha.core.engine.pipeline.IngestionPipeline.Outcome
import dev.kosha.core.engine.pipeline.ParsedTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONLY component that turns pipeline outcomes into rows (spec B2
 * invariant: ingest modules never write the transaction table directly).
 *
 * Also applies:
 *  - account resolution from SMS last4;
 *  - merchant auto-categorization (G7 rule 4: inherit the category used on
 *    ≥3 of the last 4 transactions of the merchant, else Uncategorized);
 *  - ATM-withdrawal semantics (G2): bank→Cash transfer when a Cash account
 *    exists, else the system Cash Withdrawal category.
 */
@Singleton
class PipelineCommitter @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
) {

    sealed interface CommitResult {
        data class Committed(val txnId: Long) : CommitResult
        data class QueuedForReview(val txnId: Long) : CommitResult
        data class MergedEvidence(val existingId: Long) : CommitResult
        data class Dropped(val reason: String) : CommitResult
    }

    /** Recent window handed to the dedup engine. */
    suspend fun dedupWindow(aroundMillis: Long, spreadMillis: Long = DEFAULT_SPREAD): List<DedupEngine.ExistingTxn> =
        transactionDao.inWindow(aroundMillis - spreadMillis, aroundMillis + spreadMillis).map {
            DedupEngine.ExistingTxn(
                id = it.id,
                amountPaise = it.amountPaise,
                type = when (it.type) {
                    TxnType.DEBIT -> dev.kosha.core.engine.pipeline.TxnType.DEBIT
                    TxnType.CREDIT -> dev.kosha.core.engine.pipeline.TxnType.CREDIT
                },
                accountId = it.accountId,
                accountLast4 = null, // resolved per-account below when needed
                merchantNormalized = it.merchantNormalized,
                timestampMillis = it.timestampMillis,
                reference = it.reference,
                recurringRuleId = it.recurringRuleId,
            )
        }

    suspend fun resolveAccountId(last4: String?): Long? {
        val active = accountDao.activeAccounts()
        if (active.isEmpty()) return null
        if (!last4.isNullOrBlank()) {
            active.firstOrNull { acc ->
                !acc.last4.isNullOrBlank() && acc.last4.takeLast(3) == last4.takeLast(3)
            }?.let { return it.id }
        }
        return (active.firstOrNull { it.type == AccountType.BANK } ?: active.first()).id
    }

    suspend fun commit(
        outcome: Outcome,
        source: TxnSource,
        rawEvidence: String?,
        retainRawBody: Boolean,
    ): CommitResult = when (outcome) {
        is Outcome.Ignore -> CommitResult.Dropped("not-bank-sender")
        is Outcome.Discard -> CommitResult.Dropped(outcome.reason)

        is Outcome.MergeEvidence -> {
            attachEvidence(outcome.existingId, source, rawEvidence, retainRawBody)
            CommitResult.MergedEvidence(outcome.existingId)
        }

        is Outcome.Commit -> {
            if (outcome.isAtmWithdrawal) {
                commitAtmWithdrawal(outcome, source, rawEvidence, retainRawBody)
            } else {
                val id = insert(
                    txn = outcome.txn,
                    merchantNormalized = outcome.merchantNormalized,
                    source = source,
                    score = outcome.score,
                    status = TxnStatus.COMMITTED,
                )
                    ?: return CommitResult.Dropped("no-account")
                attachEvidence(id, source, rawEvidence, retainRawBody)
                CommitResult.Committed(id)
            }
        }

        is Outcome.Review -> {
            val id = insert(
                txn = outcome.txn,
                merchantNormalized = outcome.merchantNormalized,
                source = source,
                score = outcome.score,
                status = TxnStatus.PENDING_REVIEW,
                reviewReason = outcome.reason,
                possibleDuplicateOfId = outcome.possibleDuplicateOfId,
            ) ?: return CommitResult.Dropped("no-account")
            attachEvidence(id, source, rawEvidence, retainRawBody)
            CommitResult.QueuedForReview(id)
        }

        is Outcome.LinkRecurring -> {
            val id = insert(
                txn = outcome.txn,
                merchantNormalized = outcome.merchantNormalized,
                source = source,
                score = outcome.score,
                status = TxnStatus.COMMITTED,
                recurringRuleId = outcome.ruleId,
            ) ?: return CommitResult.Dropped("no-account")
            attachEvidence(id, source, rawEvidence, retainRawBody)
            CommitResult.Committed(id)
        }

        is Outcome.TransferCandidate -> {
            // Commit the leg, link both legs as a Transfer (excluded from
            // income/expense math by the analytics queries).
            val transfersCategory = categoryDao.bySystemKey(SystemCategoryKey.TRANSFERS)
            val id = insert(
                txn = outcome.txn,
                merchantNormalized = null,
                source = source,
                score = outcome.score,
                status = TxnStatus.COMMITTED,
                forcedCategoryId = transfersCategory?.id,
            ) ?: return CommitResult.Dropped("no-account")
            val inserted = transactionDao.byId(id)
            val other = transactionDao.byId(outcome.existingId)
            if (inserted != null && other != null) {
                val (from, to) = if (inserted.type == TxnType.DEBIT) inserted to other else other to inserted
                transactionDao.insertTransfer(TransferEntity(fromTransactionId = from.id, toTransactionId = to.id))
                transactionDao.recategorize(other.id, transfersCategory?.id, System.currentTimeMillis())
            }
            attachEvidence(id, source, rawEvidence, retainRawBody)
            CommitResult.Committed(id)
        }
    }

    private suspend fun commitAtmWithdrawal(
        outcome: Outcome.Commit,
        source: TxnSource,
        rawEvidence: String?,
        retainRawBody: Boolean,
    ): CommitResult {
        val accounts = accountDao.activeAccounts()
        val cash = accounts.firstOrNull { it.type == AccountType.CASH }
        val transfersCategory = categoryDao.bySystemKey(SystemCategoryKey.TRANSFERS)

        if (cash == null) {
            // No cash account: fall back to the system Cash Withdrawal
            // category (excluded from spend analytics, spec G2).
            val cashWithdrawal = categoryDao.bySystemKey(SystemCategoryKey.CASH_WITHDRAWAL)
            val id = insert(
                txn = outcome.txn, merchantNormalized = null, source = source,
                score = outcome.score, status = TxnStatus.COMMITTED,
                forcedCategoryId = cashWithdrawal?.id,
            ) ?: return CommitResult.Dropped("no-account")
            attachEvidence(id, source, rawEvidence, retainRawBody)
            return CommitResult.Committed(id)
        }

        val debitId = insert(
            txn = outcome.txn, merchantNormalized = null, source = source,
            score = outcome.score, status = TxnStatus.COMMITTED,
            forcedCategoryId = transfersCategory?.id,
        ) ?: return CommitResult.Dropped("no-account")
        val debit = transactionDao.byId(debitId) ?: return CommitResult.Committed(debitId)
        val creditId = transactionDao.insert(
            debit.copy(
                id = 0,
                accountId = cash.id,
                type = TxnType.CREDIT,
                merchantRaw = "ATM Withdrawal",
                merchantNormalized = null,
            ),
        )
        transactionDao.insertTransfer(TransferEntity(fromTransactionId = debitId, toTransactionId = creditId))
        accountDao.recomputeBalance(cash.id)
        attachEvidence(debitId, source, rawEvidence, retainRawBody)
        return CommitResult.Committed(debitId)
    }

    private suspend fun insert(
        txn: ParsedTransaction,
        merchantNormalized: String?,
        source: TxnSource,
        score: Double,
        status: TxnStatus,
        reviewReason: String? = null,
        possibleDuplicateOfId: Long? = null,
        recurringRuleId: Long? = null,
        forcedCategoryId: Long? = null,
    ): Long? {
        val amount = txn.amount ?: return null
        val accountId = resolveAccountId(txn.accountLast4) ?: return null
        val categoryId = forcedCategoryId ?: autoCategory(merchantNormalized)
        val now = System.currentTimeMillis()
        val id = transactionDao.insert(
            TransactionEntity(
                accountId = accountId,
                categoryId = categoryId,
                amountPaise = amount.paise,
                type = when (txn.type) {
                    dev.kosha.core.engine.pipeline.TxnType.CREDIT -> TxnType.CREDIT
                    else -> TxnType.DEBIT
                },
                merchantRaw = txn.merchantRaw,
                merchantNormalized = merchantNormalized,
                timestampMillis = txn.timestampMillis ?: now,
                source = source,
                confidence = score,
                recurringRuleId = recurringRuleId,
                reference = txn.reference,
                status = status,
                reviewReason = reviewReason,
                possibleDuplicateOfId = possibleDuplicateOfId,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        accountDao.recomputeBalance(accountId)
        return id
    }

    /** G7 rule 4: ≥3 of the last 4 txns of this merchant share a category → inherit it. */
    private suspend fun autoCategory(merchantNormalized: String?): Long? {
        val uncategorized = categoryDao.bySystemKey(SystemCategoryKey.UNCATEGORIZED)?.id
        if (merchantNormalized.isNullOrBlank()) return uncategorized
        val recent = transactionDao.recentCategoriesForMerchant(merchantNormalized)
        val dominant = recent.groupingBy { it }.eachCount().entries
            .firstOrNull { it.value >= 3 }
        return dominant?.key ?: uncategorized
    }

    private suspend fun attachEvidence(
        txnId: Long,
        source: TxnSource,
        rawEvidence: String?,
        retainRawBody: Boolean,
    ) {
        // SMS privacy default (spec B4): raw body NOT stored unless the
        // debug retention toggle is on. Photo URIs are always attached.
        if (rawEvidence == null) return
        val kind = when (source) {
            TxnSource.SMS -> if (retainRawBody) EvidenceKind.SMS_TEXT else return
            TxnSource.OCR -> EvidenceKind.PHOTO_URI
            else -> return
        }
        transactionDao.insertEvidence(
            TransactionEvidenceEntity(transactionId = txnId, kind = kind, payload = rawEvidence),
        )
    }

    private companion object {
        /** ±3 h window handed to dedup (covers rule 2's ±10 min with margin). */
        const val DEFAULT_SPREAD = 3 * 60 * 60 * 1000L
    }
}
