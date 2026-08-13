package dev.kosha.core.database.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ring 1 key management (spec B4): a random 32-byte SQLCipher passphrase is
 * generated on first run and stored wrapped by an Android Keystore AES-GCM
 * key (StrongBox when available). The Keystore key is NOT auth-bound —
 * background workers (SMS receiver, recurring engine) must be able to write
 * while the phone is locked.
 *
 * Key loss (device lock removed / factory reset) ⇒ Ring-1 data recoverable
 * only from a backup file — surfaced in onboarding copy, by design.
 */
class DbKeyManager(private val context: Context) {

    fun getOrCreateDbPassphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WRAPPED, null)
        val keystoreKey = getOrCreateKeystoreKey()
        if (stored != null) {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
        val wrapped = cipher.iv + cipher.doFinal(passphrase)
        prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
        return passphrase
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        fun generate(strongBox: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (strongBox && android.os.Build.VERSION.SDK_INT >= 28) {
                builder.setIsStrongBoxBacked(true)
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(builder.build())
            return generator.generateKey()
        }

        return try {
            generate(strongBox = true)
        } catch (e: Exception) {
            // StrongBoxUnavailableException (API 28+) or OEM quirks — fall back to TEE.
            generate(strongBox = false)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kosha_ring1_db_key"
        const val PREFS = "kosha_keys"
        const val KEY_WRAPPED = "ring1_wrapped_passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PASSPHRASE_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
