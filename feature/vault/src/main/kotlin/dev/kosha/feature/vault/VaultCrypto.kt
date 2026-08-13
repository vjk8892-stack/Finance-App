package dev.kosha.feature.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Ring 2 (spec B4): vault fields are encrypted with a SEPARATE Keystore key
 * from the Ring-1 database key, and that key REQUIRES user authentication
 * with a 20-second validity window.
 *
 * Consequences, stated plainly because the spec requires the UI to say them:
 *  - a vault breach is not a ledger breach, and vice versa;
 *  - if the user removes their device lock screen the Keystore invalidates
 *    this key and Ring-2 data is UNRECOVERABLE by design.
 */
@Singleton
class VaultCrypto @Inject constructor() {

    class VaultKeyInvalidated(cause: Throwable) : Exception(
        "Vault key invalidated — vault data is unrecoverable by design",
        cause,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Fields are stored as a JSON map, encrypted whole. */
    fun encrypt(fields: Map<String, String>): ByteArray {
        val plaintext = json.encodeToString(fields).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    /**
     * Requires a fresh authentication: the Keystore itself throws when the
     * 20-second window has lapsed, so the reveal flow can prompt again.
     */
    fun decrypt(blob: ByteArray): Map<String, String> {
        try {
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            return json.decodeFromString<Map<String, String>>(plaintext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultKeyInvalidated(e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
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
                // The Ring-2 difference: auth required, 20s validity (B4).
                .setUserAuthenticationRequired(true)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
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
            generate(strongBox = false)
        }
    }

    companion object {
        const val AUTH_VALIDITY_SECONDS = 20
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "kosha_ring2_vault_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
