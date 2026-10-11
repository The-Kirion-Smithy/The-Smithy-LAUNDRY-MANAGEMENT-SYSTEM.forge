package forge.kirion.laundry.storage

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
 * Wraps a randomly generated SQLCipher passphrase using a non-exportable Android Keystore key.
 * Do not use this class for portable backups: source-device Keystore keys cannot be exported.
 *
 * Any missing/invalid key for an existing database is a recoverable failure, NOT permission
 * to generate an empty replacement database over encrypted business records.
 */
object DeviceDatabaseKey {
    private const val PREFS = "kirion_database_key_metadata"
    private const val KEY_ALIAS = "kirion_lms_dbwrap_v1"
    private const val NONCE = "nonce_b64"
    private const val CIPHERTEXT = "ciphertext_b64"
    private const val DB_FILE = "kirion_lms_secure.db"

    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nonceEncoded = prefs.getString(NONCE, null)
        val wrappedEncoded = prefs.getString(CIPHERTEXT, null)

        if (nonceEncoded != null || wrappedEncoded != null) {
            check(nonceEncoded != null && wrappedEncoded != null) {
                "Incomplete database-key metadata. Restore from an authenticated owner backup."
            }
            // Existing encrypted data may never be unlocked by auto-generating a missing key.
            val key = existingWrappingKey()
                ?: throw IllegalStateException("Database encryption key unavailable. Verified owner recovery required.")
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE, key,
                    GCMParameterSpec(128, Base64.decode(nonceEncoded, Base64.NO_WRAP))
                )
                val plaintext = cipher.doFinal(Base64.decode(wrappedEncoded, Base64.NO_WRAP))
                require(plaintext.size == 32) { "Unexpected wrapped database key size" }
                plaintext
            } catch (_: Exception) {
                throw IllegalStateException("Encrypted database key cannot be recovered. Do not reset local data.")
            }
        }

        check(!app.getDatabasePath(DB_FILE).exists()) {
            "Encrypted database exists without its wrapping metadata; refusing key reset."
        }
        // First initialization is the only point at which a new key is permitted.
        val master = existingWrappingKey() ?: createWrappingKey()
        val freshPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, master)
            val encodedNonce = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val encodedWrapped = Base64.encodeToString(
                cipher.doFinal(freshPassphrase), Base64.NO_WRAP
            )
            check(prefs.edit().putString(NONCE, encodedNonce)
                .putString(CIPHERTEXT, encodedWrapped).commit()) {
                "Could not persist wrapped encryption key"
            }
            return freshPassphrase
        } catch (failure: Exception) {
            freshPassphrase.fill(0)
            throw IllegalStateException("Failed to initialize database encryption key", failure)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingWrappingKey(): SecretKey? =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun createWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
