package dev.kosha.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kosha.core.common.Money
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.repo.BalanceMaintainer
import dev.kosha.core.database.repo.PipelineCommitter
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.database.settings.TrackingWindow
import dev.kosha.core.database.seed.CategorySeeder
import dev.kosha.core.engine.pipeline.IngestionPipeline
import dev.kosha.core.engine.pipeline.ParsedTransaction
import dev.kosha.core.engine.pipeline.TxnType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A second bank's transactions must never land on the first bank's account.
 *
 * People hold accounts at several banks and typically add one to the app.
 * The committer used to fall back to "the first bank account" whenever the
 * SMS account tail matched nothing, which corrupted that account's balance
 * silently — the app looked like it was working. These cases pin the
 * replacement behaviour.
 */
@RunWith(AndroidJUnit4::class)
class MultiAccountAttributionTest {

    private fun inMemoryDb(context: Context): KoshaDatabase =
        Room.inMemoryDatabaseBuilder(context, KoshaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * Built the same way Hilt builds it, so a constructor change breaks here
     * rather than drifting silently — which is exactly what happened while
     * these tests had no device to run on.
     */
    private fun committerFor(context: Context, db: KoshaDatabase): PipelineCommitter {
        val trackingWindow = TrackingWindow(SettingsRepository(context))
        return PipelineCommitter(
            BalanceMaintainer(db.accountDao(), trackingWindow),
            db.transactionDao(),
            db.accountDao(),
            db.categoryDao(),
        )
    }

    private fun capture(last4: String?, amountPaise: Long = 50_000) = ParsedTransaction(
        amount = Money(amountPaise),
        type = TxnType.DEBIT,
        accountLast4 = last4,
        merchantRaw = "SHOP",
        timestampMillis = System.currentTimeMillis(),
        reference = "60${amountPaise}0011",
        fieldConfidence = ParsedTransaction.Field.entries.associateWith { 0.99 },
    )

    private suspend fun commit(committer: PipelineCommitter, txn: ParsedTransaction) =
        committer.commit(
            outcome = IngestionPipeline.Outcome.Commit(
                txn = txn,
                score = 0.99,
                merchantNormalized = "SHOP",
                isAtmWithdrawal = false,
                isSelfTransfer = false,
                bank = null,
                patternId = null,
            ),
            source = TxnSource.SMS,
            rawEvidence = null,
            retainRawBody = false,
        )

    @Test
    fun unmatchedTailBecomesItsOwnAccountAndWaitsForReview() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = inMemoryDb(context)
        CategorySeeder.ensureSeeded(db.categoryDao())
        val committer = committerFor(context, db)

        val hdfc = db.accountDao().insert(
            AccountEntity(name = "HDFC Savings", type = AccountType.BANK, last4 = "1234"),
        )

        // A message from a bank the user never added to the app.
        val result = commit(committer, capture("7788"))
        assertTrue("unconfirmed attribution must not commit: $result", result is PipelineCommitter.CommitResult.QueuedForReview)

        val accounts = db.accountDao().activeAccounts()
        assertEquals(2, accounts.size)
        val discovered = accounts.single { it.id != hdfc }
        assertEquals("7788", discovered.last4)

        val txn = db.transactionDao().byId((result as PipelineCommitter.CommitResult.QueuedForReview).txnId)
        assertNotNull(txn)
        assertEquals(discovered.id, txn!!.accountId)
        assertEquals(TxnStatus.PENDING_REVIEW, txn.status)
        assertEquals("new-account-7788", txn.reviewReason)

        // The whole point: the account the user DID add is untouched.
        assertEquals(0L, db.accountDao().byId(hdfc)!!.currentBalancePaise)
        db.close()
    }

    @Test
    fun theOnlyAccountAdoptsTheFirstTailItSees() = runBlocking {
        // Onboarding treats the tail as optional, so the common install has
        // one account with none. Without this, every single message would
        // look like a new bank and the app would be all review queue.
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = inMemoryDb(context)
        CategorySeeder.ensureSeeded(db.categoryDao())
        val committer = committerFor(context, db)

        val hdfc = db.accountDao().insert(
            AccountEntity(name = "HDFC Savings", type = AccountType.BANK, last4 = null),
        )

        val first = commit(committer, capture("1234", 10_000))
        assertTrue("adoption must be confirmable: $first", first is PipelineCommitter.CommitResult.QueuedForReview)
        assertEquals(1, db.accountDao().activeAccounts().size)
        assertEquals("1234", db.accountDao().byId(hdfc)!!.last4)
        assertEquals(
            "account-tail-1234",
            db.transactionDao().byId((first as PipelineCommitter.CommitResult.QueuedForReview).txnId)!!.reviewReason,
        )

        // Now the tail is known, the same bank's next message just commits...
        val second = commit(committer, capture("1234", 20_000))
        assertTrue("$second", second is PipelineCommitter.CommitResult.Committed)

        // ...and a genuinely different bank becomes its own account.
        val other = commit(committer, capture("7788", 30_000))
        assertTrue("$other", other is PipelineCommitter.CommitResult.QueuedForReview)
        assertEquals(2, db.accountDao().activeAccounts().size)
        db.close()
    }

    @Test
    fun matchingTailGoesStraightToItsAccount() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = inMemoryDb(context)
        CategorySeeder.ensureSeeded(db.categoryDao())
        val committer = committerFor(context, db)

        val hdfc = db.accountDao().insert(
            AccountEntity(name = "HDFC Savings", type = AccountType.BANK, last4 = "1234"),
        )
        db.accountDao().insert(AccountEntity(name = "ICICI", type = AccountType.BANK, last4 = "7788"))

        val result = commit(committer, capture("1234"))
        assertTrue("$result", result is PipelineCommitter.CommitResult.Committed)
        val txn = db.transactionDao().byId((result as PipelineCommitter.CommitResult.Committed).txnId)
        assertEquals(hdfc, txn!!.accountId)
        assertEquals(TxnStatus.COMMITTED, txn.status)
        assertEquals(-50_000L, db.accountDao().byId(hdfc)!!.currentBalancePaise)
        db.close()
    }

    @Test
    fun noTailIsAutoAttributedOnlyWhenThereIsOneAccount() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = inMemoryDb(context)
        CategorySeeder.ensureSeeded(db.categoryDao())
        val committer = committerFor(context, db)

        val only = db.accountDao().insert(
            AccountEntity(name = "HDFC Savings", type = AccountType.BANK, last4 = "1234"),
        )
        val single = commit(committer, capture(null, 10_000))
        assertTrue("one account leaves nothing to guess: $single", single is PipelineCommitter.CommitResult.Committed)
        assertEquals(
            only,
            db.transactionDao().byId((single as PipelineCommitter.CommitResult.Committed).txnId)!!.accountId,
        )

        // Add a second account and the same shape of message becomes a question.
        db.accountDao().insert(AccountEntity(name = "ICICI", type = AccountType.BANK, last4 = "7788"))
        val ambiguous = commit(committer, capture(null, 20_000))
        assertTrue("two accounts, no tail → review: $ambiguous", ambiguous is PipelineCommitter.CommitResult.QueuedForReview)
        val txn = db.transactionDao()
            .byId((ambiguous as PipelineCommitter.CommitResult.QueuedForReview).txnId)!!
        assertEquals(TxnStatus.PENDING_REVIEW, txn.status)
        assertEquals("account-unknown", txn.reviewReason)
        db.close()
    }

    /**
     * With no accounts at all, the capture used to be DROPPED — a real payment
     * silently discarded because the user had not finished onboarding. Nothing
     * told them, and nothing could recover it: the message had been read and
     * marked seen. The first message now creates the account it names and
     * queues itself for review, so the transaction survives and the user is
     * still the one who confirms where it belongs.
     */
    @Test
    fun theFirstMessageWithNoAccountsCreatesOneAndAsks() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = inMemoryDb(context)
        CategorySeeder.ensureSeeded(db.categoryDao())
        val committer = committerFor(context, db)

        val result = commit(committer, capture("1234"))
        assertTrue("$result", result is PipelineCommitter.CommitResult.QueuedForReview)

        val created = db.accountDao().activeAccounts().single()
        assertEquals("1234", created.last4)

        val txn = db.transactionDao()
            .byId((result as PipelineCommitter.CommitResult.QueuedForReview).txnId)!!
        assertEquals(created.id, txn.accountId)
        assertEquals(TxnStatus.PENDING_REVIEW, txn.status)
        assertEquals("new-account-1234", txn.reviewReason)
        db.close()
    }
}
