package dev.kosha.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kosha.core.database.security.DbKeyManager
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-0 exit gate: encrypted DB round-trips, and the file on disk is not
 * plaintext SQLite (no "SQLite format 3" magic).
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDbRoundTripTest {

    @Test
    fun encryptedRoundTrip() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath("test-kosha.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        System.loadLibrary("sqlcipher")
        val passphrase = DbKeyManager(context).getOrCreateDbPassphrase()
        val db = Room.databaseBuilder(context, KoshaDatabase::class.java, "test-kosha.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()

        db.appMetaDao().put(AppMetaEntity("hello", "kosha"))
        assertEquals("kosha", db.appMetaDao().get("hello"))
        db.close()

        val header = File(dbFile.absolutePath).inputStream().use { it.readNBytes(16) }
        val magic = String(header, Charsets.US_ASCII)
        assertFalse("DB file must not be plaintext SQLite", magic.startsWith("SQLite format 3"))
    }
}
