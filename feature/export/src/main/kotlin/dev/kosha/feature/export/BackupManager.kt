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
 * evidence images, the whole ZIP encrypted with AES-256-GCM under a key
 * derived by PBKDF2 (600k iterations) from a user-chosen passphrase. That
 * passphrase is NOT the app lock — it is shown once with a "write this down"
 * screen, because nothing else can recover the file.
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
        passphrase: CharArray,
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
            out.write(salt)
            out.write(cipher.iv)
            out.write(ciphertext)
        } ?: throw RestoreFailed("Could not open the chosen location for writing")
    }

    suspend fun restore(source: Uri, passphrase: CharArray): Manifest = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw RestoreFailed("Could not read the backup file")

        val magicEnd = MAGIC.size
        if (bytes.size < magicEnd + 1 + SALT_BYTES + GCM_IV_BYTES ||
            !bytes.copyOfRange(0, magicEnd).contentEquals(MAGIC)
        ) {
            throw RestoreFailed("That does not look like a Kosha backup")
        }

        val salt = bytes.copyOfRange(magicEnd + 1, magicEnd + 1 + SALT_BYTES)
        val ivStart = magicEnd + 1 + SALT_BYTES
        val iv = bytes.copyOfRange(ivStart, ivStart + GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(ivStart + GCM_IV_BYTES, bytes.size)

        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw RestoreFailed("Wrong passphrase, or the file is damaged", e)
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

        database.close()
        context.getDatabasePath(KoshaDatabase.NAME).writeBytes(resolvedDb)
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

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
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
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_BITS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
        private val MAGIC = "KOSHA1".toByteArray(Charsets.US_ASCII)
        private const val ENTRY_MANIFEST = "manifest.json"
        private const val ENTRY_DATABASE = "kosha.db"
        private const val ENTRY_EVIDENCE_DIR = "evidence"
    }
}
