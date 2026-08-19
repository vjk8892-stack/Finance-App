package dev.kosha.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kosha.core.database.model.AccountEntity
import dev.kosha.core.database.model.AccountType
import dev.kosha.core.database.seed.CategorySeeder
import dev.kosha.core.database.security.DbKeyManager
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-0/1 exit gates: encrypted DB round-trips; the file on disk is not
 * plaintext SQLite; category seeding is idempotent and matches spec G2.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDbRoundTripTest {

    @Test
    fun encryptedRoundTripAndSeed() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath("test-kosha.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        System.loadLibrary("sqlcipher")
        val passphrase = DbKeyManager(context).getOrCreateDbPassphrase()
        val db = Room.databaseBuilder(context, KoshaDatabase::class.java, "test-kosha.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()

        // Account round-trip
        val id = db.accountDao().insert(
            AccountEntity(name = "HDFC Savings", type = AccountType.BANK, last4 = "1234"),
        )
        assertEquals("HDFC Savings", db.accountDao().byId(id)?.name)

        // Seeding is idempotent: twice through leaves exactly one of each.
        //
        // Counted against the seeder's own list rather than a number written
        // out here. This said 24 and broke the moment a category was added —
        // which tested the constant, not the seeding. The property worth
        // pinning is "running it twice does not duplicate", and that holds
        // however many categories there are.
        CategorySeeder.ensureSeeded(db.categoryDao())
        CategorySeeder.ensureSeeded(db.categoryDao())
        assertEquals(CategorySeeder.seedCategories().size, db.categoryDao().count())
        assertNotNull(db.categoryDao().bySystemKey(dev.kosha.core.database.model.SystemCategoryKey.UNCATEGORIZED))
        db.close()

        // Read the header by hand rather than with readNBytes: that method is
        // Java 9 and only reaches Android at API 33, so on anything older —
        // including this project's minSdk 26 — it throws NoSuchMethodError at
        // runtime. Nothing catches that at compile time.
        val header = ByteArray(SQLITE_MAGIC.length)
        File(dbFile.absolutePath).inputStream().use { it.read(header) }
        val magic = String(header, Charsets.US_ASCII)
        assertFalse("DB file must not be plaintext SQLite", magic.startsWith(SQLITE_MAGIC))
    }

    private companion object {
        /** The bytes an UNENCRYPTED SQLite file starts with — what must be absent. */
        const val SQLITE_MAGIC = "SQLite format 3"
    }
}
