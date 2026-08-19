package dev.kosha.feature.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.database.KoshaDatabase
import dev.kosha.core.database.security.DbKeyManager
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encrypted backup/restore (spec G1).
 *
 * A `.kosha` file is a ZIP containing manifest.json + a SQLite dump +
 * evidence images, the whole ZIP encrypted with AES-256-GCM.
 *
 * KEY, AND ITS HONEST LIMIT. The key is PBKDF2-stretched from a secret that
 * ships inside Kosha plus a per-file random salt. No passphrase is asked for,
 * because the previous design asked for one, disabled both buttons until it
 * was typed twice, and so did nothing at all for anyone who did not — a backup
 * feature that silently no-ops is worse than none. The cost of dropping it is
 * real and worth stating plainly: a secret compiled into a downloadable APK
 * can be extracted by anyone willing to open the APK. This protects a backup
 * sitting in cloud storage or a shared folder from casual reading and from
 * being opened by other apps; it is NOT protection against someone determined
 * to read that specific file. [passphrase] is still honoured when supplied,
 * and mixing one in restores full strength — the header records which was
 * used, so restore never has to guess.
 *
 * The key deliberately does NOT come from the Android Keystore, which would be
 * stronger: a Keystore key dies with the app install, so backups would be
 * unrestorable after exactly the events — lost phone, reinstall — that make
 * people take backups.
 *
 * VAULT EXCLUSION (spec B4): `vault_entries` rows are dropped from the dump
 * unless the user explicitly opts in, and the manifest records which choice
 * was made so restore never silently surprises them.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: KoshaDatabase,
    private val keyManager: DbKeyManager,
) {
    @Serializable
    data class Manifest(
        val schemaVersion: Int,
        val appVersion: String,
        val createdAt: Long,
        val checksum: String,
        val includesVault: Boolean,
    )

    class RestoreFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun backup(
        destination: Uri,
        passphrase: CharArray? = null,
        includeVault: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        val dbBytes = snapshotDatabase(includeVault)

        val payload = ByteArrayOutputStream()
        ZipOutputStream(payload).use { zip ->
            val manifest = Manifest(
                schemaVersion = SCHEMA_VERSION,
                appVersion = appVersion(),
                createdAt = System.currentTimeMillis(),
                checksum = dbBytes.sha256(),
                includesVault = includeVault,
            )
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(json.encodeToString(manifest).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_DATABASE))
            zip.write(dbBytes)
            zip.closeEntry()

            // Evidence images live in app-private storage.
            val evidenceDir = java.io.File(context.filesDir, "evidence")
            evidenceDir.listFiles()?.forEach { file ->
                zip.putNextEntry(ZipEntry("$ENTRY_EVIDENCE_DIR/${file.name}"))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(payload.toByteArray())

        context.contentResolver.openOutputStream(destination)?.use { out ->
            out.write(MAGIC)
            out.write(byteArrayOf(FORMAT_VERSION))
            // Which key this file needs. Without it, restore would have to try
            // both and report "damaged" for what is really "needs your
            // passphrase" — two very different things to tell someone holding
            // their only copy of their records.
            out.write(byteArrayOf(if (passphrase == null) KEY_APP else KEY_PASSPHRASE))
            out.write(salt)
            out.write(cipher.iv)
            out.write(ciphertext)
        } ?: throw RestoreFailed("Could not open the chosen location for writing")
    }

    /** True when this file cannot be opened without the user's own passphrase. */
    suspend fun needsPassphrase(source: Uri): Boolean = withContext(Dispatchers.IO) {
        val head = context.contentResolver.openInputStream(source)?.use { input ->
            ByteArray(HEADER_BYTES).let { buffer ->
                val read = input.read(buffer)
                if (read < HEADER_BYTES) null else buffer
            }
        } ?: return@withContext false
        head[MAGIC.size + 1] == KEY_PASSPHRASE
    }

    suspend fun restore(source: Uri, passphrase: CharArray? = null): Manifest = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw RestoreFailed("Could not read the backup file")

        val magicEnd = MAGIC.size
        if (bytes.size < magicEnd + 1 || !bytes.copyOfRange(0, magicEnd).contentEquals(MAGIC)) {
            throw RestoreFailed("That does not look like a Kosha backup")
        }

        // Version 1 had no key-kind byte and was always passphrase-derived.
        // Files written by an older Kosha have to keep restoring, or upgrading
        // the app would strand the backups taken before it.
        val version = bytes[magicEnd]
        val saltStart = if (version >= FORMAT_VERSION) magicEnd + 2 else magicEnd + 1
        val keyKind = if (version >= FORMAT_VERSION) bytes[magicEnd + 1] else KEY_PASSPHRASE
        if (bytes.size < saltStart + SALT_BYTES + GCM_IV_BYTES) {
            throw RestoreFailed("That does not look like a Kosha backup")
        }

        val salt = bytes.copyOfRange(saltStart, saltStart + SALT_BYTES)
        val ivStart = saltStart + SALT_BYTES
        val iv = bytes.copyOfRange(ivStart, ivStart + GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(ivStart + GCM_IV_BYTES, bytes.size)

        if (keyKind == KEY_PASSPHRASE && passphrase == null) {
            throw RestoreFailed("This backup was saved with a passphrase — enter it to restore")
        }

        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(if (keyKind == KEY_PASSPHRASE) passphrase else null, salt),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw RestoreFailed(
                if (keyKind == KEY_PASSPHRASE) {
                    "Wrong passphrase, or the file is damaged"
                } else {
                    "That file is damaged, or was not written by Kosha"
                },
                e,
            )
        }

        var manifest: Manifest? = null
        var dbBytes: ByteArray? = null
        ZipInputStream(plaintext.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == ENTRY_MANIFEST ->
                        manifest = json.decodeFromString<Manifest>(zip.readBytes().decodeToString())
                    entry.name == ENTRY_DATABASE -> dbBytes = zip.readBytes()
                    entry.name.startsWith(ENTRY_EVIDENCE_DIR) -> {
                        val target = java.io.File(context.filesDir, "evidence/${entry.name.substringAfterLast('/')}")
                        target.parentFile?.mkdirs()
                        target.writeBytes(zip.readBytes())
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val resolvedManifest = manifest ?: throw RestoreFailed("Backup is missing its manifest")
        val resolvedDb = dbBytes ?: throw RestoreFailed("Backup is missing its data")

        if (resolvedManifest.schemaVersion > SCHEMA_VERSION) {
            throw RestoreFailed("This backup came from a newer version of Kosha")
        }
        if (resolvedDb.sha256() != resolvedManifest.checksum) {
            throw RestoreFailed("Backup failed its integrity check")
        }

        // Overwriting the database file is not enough on its own. SQLite in
        // WAL mode keeps recent writes in a side journal, and those files sit
        // next to the database with a life of their own: leaving them means
        // the NEXT open replays the old journal over the freshly restored
        // file, which is a silent partial restore — the worst possible outcome
        // for the one feature whose entire job is getting your data back.
        database.close()
        val dbFile = context.getDatabasePath(KoshaDatabase.NAME)
        dbFile.writeBytes(resolvedDb)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            java.io.File(dbFile.parentFile, dbFile.name + suffix).delete()
        }
        resolvedManifest
    }

    /**
     * Takes a consistent copy of the database and, unless the user opted in,
     * DELETES the vault table from that copy before it is ever written to the
     * backup (spec B4). Deleting from a copy — not the live database — is
     * what makes exclusion real: the backup archives a file, so filtering has
     * to happen inside the file itself.
     */
    private fun snapshotDatabase(includeVault: Boolean): ByteArray {
        val snapshotFile = java.io.File(context.cacheDir, "backup-snapshot.db")
        snapshotFile.delete()

        // VACUUM INTO writes a consistent copy (WAL included) under the same
        // SQLCipher key, without disturbing the live database.
        database.query("VACUUM INTO ?", arrayOf<Any>(snapshotFile.absolutePath)).use { it.moveToFirst() }

        try {
            if (!includeVault) {
                System.loadLibrary("sqlcipher")
                val copy = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                    snapshotFile.absolutePath,
                    keyManager.getOrCreateDbPassphrase(),
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                    null,
                    null,
                )
                copy.use {
                    it.execSQL("DELETE FROM vault_entries")
                    it.execSQL("VACUUM")
                }
            }
            return snapshotFile.readBytes()
        } finally {
            snapshotFile.delete()
        }
    }

    /**
     * With no passphrase the input is Kosha's own high-entropy secret, so the
     * iteration count is doing nothing useful — stretching only buys time
     * against guessing, and there is nothing here to guess. It stays low so a
     * backup is instant; the 600k count still applies to a real passphrase,
     * where guessing is exactly the threat.
     */
    private fun deriveKey(passphrase: CharArray?, salt: ByteArray): SecretKeySpec {
        val material = passphrase ?: APP_SECRET
        val iterations = if (passphrase == null) APP_KEY_ITERATIONS else PBKDF2_ITERATIONS
        val spec = PBEKeySpec(material, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun ByteArray.sha256(): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val FILE_EXTENSION = "kosha"
        const val SCHEMA_VERSION = 1
        const val PBKDF2_ITERATIONS = 600_000
        private const val APP_KEY_ITERATIONS = 10_000

        /**
         * Kosha's own key material. Not a secret from anyone holding the APK —
         * see the class comment — but it does mean a `.kosha` file is opaque to
         * every other app and to anyone browsing the folder it sits in.
         */
        private val APP_SECRET = (
            "kosha.backup.v2/" +
                "6c1f9a4d3b7e2058:" +
                "offline-first.no-network.on-device-only"
            ).toCharArray()

        /** Which key opens the file: Kosha's own, or the user's passphrase. */
        private const val KEY_APP: Byte = 0
        private const val KEY_PASSPHRASE: Byte = 1
        private val HEADER_BYTES = MAGIC_SIZE + 2
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_BITS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 2
        private val MAGIC = "KOSHA1".toByteArray(Charsets.US_ASCII)
        private const val MAGIC_SIZE = 6
        private const val ENTRY_MANIFEST = "manifest.json"
        private const val ENTRY_DATABASE = "kosha.db"
        private const val ENTRY_EVIDENCE_DIR = "evidence"
    }
}
