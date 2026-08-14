package dev.kosha.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.model.TransactionEntity
import dev.kosha.core.database.model.TxnSource
import dev.kosha.core.database.model.TxnStatus
import dev.kosha.core.database.model.TxnType
import dev.kosha.core.database.repo.TransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Undo has to put things back EXACTLY, or it is worse than no undo — a user
 * who taps it and gets an almost-restored row has lost data without being told.
 */
@RunWith(AndroidJUnit4::class)
class UndoRestoreTest {

    private fun db(context: Context) = Room.inMemoryDatabaseBuilder(context, KoshaDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private fun txn(accountId: Long, paise: Long, parentId: Long? = null) = TransactionEntity(
        accountId = accountId,
        amountPaise = paise,
        type = TxnType.DEBIT,
        merchantRaw = "SHOP",
        timestampMillis = 1_800_000_000_000L,
        source = TxnSource.MANUAL,
        confidence = 1.0,
        status = TxnStatus.COMMITTED,
        parentTransactionId = parentId,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    @Test
    fun deleteThenRestoreKeepsTheSameIdAndBalance() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = db(context)
        val repo = TransactionRepository(database.transactionDao(), database.accountDao())
        val accountId = database.accountDao().insert(
            AccountEntity(name = "Bank", type = AccountType.BANK, openingBalancePaise = 100_000),
        )

        val id = repo.add(txn(accountId, 25_000))
        assertEquals(75_000L, database.accountDao().byId(accountId)!!.currentBalancePaise)

        val captured = repo.deleteCapturing(id)
        assertNotNull(captured)
        assertNull(database.transactionDao().byId(id))
        assertEquals(100_000L, database.accountDao().byId(accountId)!!.currentBalancePaise)

        repo.restore(captured!!)
        // Same id back: anything still referencing this row stays valid.
        assertNotNull(database.transactionDao().byId(id))
        assertEquals(75_000L, database.accountDao().byId(accountId)!!.currentBalancePaise)
        database.close()
    }

    @Test
    fun restoringASplitBringsItsChildrenBack() = runBlocking {
        // deleteWithChildren removes the split lines too, so capturing only the
        // parent would quietly drop them and leave the category mix wrong.
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = db(context)
        val repo = TransactionRepository(database.transactionDao(), database.accountDao())
        val accountId = database.accountDao().insert(AccountEntity(name = "Bank", type = AccountType.BANK))

        val parentId = repo.add(txn(accountId, 40_000))
        database.transactionDao().insert(txn(accountId, 25_000, parentId = parentId))
        database.transactionDao().insert(txn(accountId, 15_000, parentId = parentId))
        assertEquals(2, database.transactionDao().childrenOf(parentId).size)

        val captured = repo.deleteCapturing(parentId)!!
        assertEquals(0, database.transactionDao().childrenOf(parentId).size)

        repo.restore(captured)
        assertEquals(2, database.transactionDao().childrenOf(parentId).size)
        database.close()
    }

    @Test
    fun undoingABulkApprovalPutsRowsBackInTheQueue() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = db(context)
        val repo = TransactionRepository(database.transactionDao(), database.accountDao())
        val accountId = database.accountDao().insert(AccountEntity(name = "Bank", type = AccountType.BANK))

        val ids = (1..3).map { i ->
            database.transactionDao().insert(
                txn(accountId, 1_000L * i).copy(
                    status = TxnStatus.PENDING_REVIEW,
                    reviewReason = "account-unknown",
                ),
            )
        }

        val before = repo.approveAllCapturing(ids)
        assertEquals(3, before.size)
        assertEquals(TxnStatus.COMMITTED, database.transactionDao().byId(ids.first())!!.status)

        repo.restoreReviewStates(before)
        val restored = database.transactionDao().byId(ids.first())!!
        assertEquals(TxnStatus.PENDING_REVIEW, restored.status)
        // The REASON matters: without it the row loses why it was waiting and
        // the grouped queue cannot file it.
        assertEquals("account-unknown", restored.reviewReason)
        database.close()
    }
}
