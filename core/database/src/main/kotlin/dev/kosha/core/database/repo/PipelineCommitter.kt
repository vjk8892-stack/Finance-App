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

    /**
     * Where a captured transaction belongs.
     *
     * People routinely hold accounts at several banks but only add one to the
     * app. The old behaviour — fall back to the first bank account whenever
     * the tail did not match — quietly folded a second bank's spending into
     * the first account and corrupted its balance. Nothing here guesses any
     * more: an unrecognised tail becomes its own account, and a message with
     * no tail at all is only auto-attributed when there is exactly one
     * account it could mean.
     */
    sealed interface AccountResolution {
        /** The tail matched an account, or there was only one candidate. */
        data class Matched(val id: Long) : AccountResolution

        /** A tail the user never registered — discovered and created here. */
        data class Discovered(val id: Long, val last4: String) : AccountResolution

        /** No tail in the message and more than one account to choose from. */
        data class Ambiguous(val fallbackId: Long) : AccountResolution

        /** Onboarding not finished — nothing to attach a transaction to. */
        data object NoAccounts : AccountResolution
    }

    suspend fun resolveAccount(last4: String?): AccountResolution {
        val active = accountDao.activeAccounts()
        if (active.isEmpty()) return AccountResolution.NoAccounts

        if (!last4.isNullOrBlank()) {
            // Banks mask tails inconsistently (XX1234, X234, **234), so match
            // on the last three digits both sides can agree on.
            val tail = last4.takeLast(3)
            active.firstOrNull { !it.last4.isNullOrBlank() && it.last4.takeLast(3) == tail }
                ?.let { return AccountResolution.Matched(it.id) }

            if (active.size < MAX_DISCOVERED_ACCOUNTS) {
                val id = accountDao.insert(
                    AccountEntity(
                        name = "•• $last4",
                        type = AccountType.BANK,
                        last4 = last4,
                        colorToken = active.size % ACCOUNT_PALETTE_SIZE,
                    ),
                )
                return AccountResolution.Discovered(id, last4)
            }
            // Runaway tails (a noisy parse, not a real account) fall through
            // to the ambiguous path rather than filling the account list.
        }

        val single = active.singleOrNull()
        if (single != null) return AccountResolution.Matched(single.id)

        val fallback = active.firstOrNull { it.type == AccountType.BANK } ?: active.first()
        return AccountResolution.Ambiguous(fallback.id)
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
                val inserted = insert(
                    txn = outcome.txn,
                    merchantNormalized = outcome.merchantNormalized,
                    source = source,
                    score = outcome.score,
                    status = TxnStatus.COMMITTED,
                )
                    ?: return CommitResult.Dropped("no-account")
                attachEvidence(inserted.id, source, rawEvidence, retainRawBody)
                inserted.asResult()
            }
        }

        is Outcome.Review -> {
            val inserted = insert(
                txn = outcome.txn,
                merchantNormalized = outcome.merchantNormalized,
                source = source,
                score = outcome.score,
                status = TxnStatus.PENDING_REVIEW,
                reviewReason = outcome.reason,
                possibleDuplicateOfId = outcome.possibleDuplicateOfId,
            ) ?: return CommitResult.Dropped("no-account")
            attachEvidence(inserted.id, source, rawEvidence, retainRawBody)
            CommitResult.QueuedForReview(inserted.id)
        }

        is Outcome.LinkRecurring -> {
            val inserted = insert(
                txn = outcome.txn,
                merchantNormalized = outcome.merchantNormalized,
                source = source,
                score = outcome.score,
                status = TxnStatus.COMMITTED,
                recurringRuleId = outcome.ruleId,
            ) ?: return CommitResult.Dropped("no-account")
            attachEvidence(inserted.id, source, rawEvidence, retainRawBody)
            inserted.asResult()
        }

        is Outcome.TransferCandidate -> {
            // Commit the leg, link both legs as a Transfer (excluded from
            // income/expense math by the analytics queries).
            val transfersCategory = categoryDao.bySystemKey(SystemCategoryKey.TRANSFERS)
            val inserted = insert(
                txn = outcome.txn,
                merchantNormalized = null,
                source = source,
                score = outcome.score,
                status = TxnStatus.COMMITTED,
                forcedCategoryId = transfersCategory?.id,
            ) ?: return CommitResult.Dropped("no-account")
            val row = transactionDao.byId(inserted.id)
            val other = transactionDao.byId(outcome.existingId)
            // Only pair the legs once this one is actually in the ledger —
            // linking a row that is still awaiting account confirmation would
            // recategorize the other leg on the strength of a guess.
            if (row != null && other != null && inserted.status == TxnStatus.COMMITTED) {
                val (from, to) = if (row.type == TxnType.DEBIT) row to other else other to row
                transactionDao.insertTransfer(TransferEntity(fromTransactionId = from.id, toTransactionId = to.id))
                transactionDao.recategorize(other.id, transfersCategory?.id, System.currentTimeMillis())
            }
            attachEvidence(inserted.id, source, rawEvidence, retainRawBody)
            inserted.asResult()
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
            val inserted = insert(
                txn = outcome.txn, merchantNormalized = null, source = source,
                score = outcome.score, status = TxnStatus.COMMITTED,
                forcedCategoryId = cashWithdrawal?.id,
            ) ?: return CommitResult.Dropped("no-account")
            attachEvidence(inserted.id, source, rawEvidence, retainRawBody)
            return inserted.asResult()
        }

        val debit = insert(
            txn = outcome.txn, merchantNormalized = null, source = source,
            score = outcome.score, status = TxnStatus.COMMITTED,
            forcedCategoryId = transfersCategory?.id,
        ) ?: return CommitResult.Dropped("no-account")
        attachEvidence(debit.id, source, rawEvidence, retainRawBody)
        val debitRow = transactionDao.byId(debit.id) ?: return debit.asResult()
        // The matching cash credit only makes sense once the bank leg is real;
        // while the source account is unconfirmed there is nothing to move.
        if (debit.status != TxnStatus.COMMITTED) return debit.asResult()

        val creditId = transactionDao.insert(
            debitRow.copy(
                id = 0,
                accountId = cash.id,
                type = TxnType.CREDIT,
                merchantRaw = "ATM Withdrawal",
                merchantNormalized = null,
            ),
        )
        transactionDao.insertTransfer(TransferEntity(fromTransactionId = debit.id, toTransactionId = creditId))
        accountDao.recomputeBalance(cash.id)
        return debit.asResult()
    }

    /** What actually landed — [status] may be stricter than the one asked for. */
    private data class Inserted(val id: Long, val status: TxnStatus)

    private fun Inserted.asResult(): CommitResult =
        if (status == TxnStatus.COMMITTED) CommitResult.Committed(id) else CommitResult.QueuedForReview(id)

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
    ): Inserted? {
        val amount = txn.amount ?: return null
        val resolution = resolveAccount(txn.accountLast4)
        val accountId = when (resolution) {
            is AccountResolution.Matched -> resolution.id
            is AccountResolution.Discovered -> resolution.id
            is AccountResolution.Ambiguous -> resolution.fallbackId
            AccountResolution.NoAccounts -> return null
        }
        // Attribution the user has not confirmed never lands silently in the
        // ledger, however confident the parse itself was — a misattributed
        // amount is worse than one waiting in review.
        val attributionReason = when (resolution) {
            is AccountResolution.Discovered -> "new-account-${resolution.last4}"
            is AccountResolution.Ambiguous -> "account-unknown"
            else -> null
        }
        val effectiveStatus = if (attributionReason != null) TxnStatus.PENDING_REVIEW else status
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
                status = effectiveStatus,
                reviewReason = reviewReason ?: attributionReason,
                possibleDuplicateOfId = possibleDuplicateOfId,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        accountDao.recomputeBalance(accountId)
        return Inserted(id, effectiveStatus)
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

        /** Beyond this, unmatched tails are noise rather than real accounts. */
        const val MAX_DISCOVERED_ACCOUNTS = 12

        /** Spec G3 account palette. */
        const val ACCOUNT_PALETTE_SIZE = 8
    }
}
