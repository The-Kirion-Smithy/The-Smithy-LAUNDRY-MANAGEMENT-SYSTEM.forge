package forge.kirion.laundry.storage

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, portable authenticated envelope: KIRIONLMSBACKUP1.
 * All customer records are encrypted; clear header has only salt, nonce and KDF parameters.
 * Authentication happens before any restore / database mutation.
 */
object PortableBackupCipher {
    private val magic = "KIRIONLMSBACKUP1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val KEY_SIZE = 32
    private const val HEADER_SIZE = 16 + 1 + 4 + 4 + SALT_SIZE + NONCE_SIZE + 4
    private const val MEMORY_KIB = 65536
    private const val ITERATIONS = 3
    private const val MAX_DATA = 16 * 1024 * 1024

    fun encrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        require(data.size in 1..MAX_DATA) { "Backup payload size out of range" }
        require(passphrase.size >= 12) { "Use a recovery passphrase of at least 12 characters" }
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(magic).put(VERSION).putInt(MEMORY_KIB).putInt(ITERATIONS)
            .put(salt).put(nonce).putInt(data.size).array()
        val key = derive(passphrase, salt, MEMORY_KIB, ITERATIONS)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(data)
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        require(envelope.size in (HEADER_SIZE + 17)..(HEADER_SIZE + MAX_DATA + 16)) {
            "Invalid or oversized backup"
        }
        val header = envelope.copyOfRange(0, HEADER_SIZE)
        val reader = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val tag = ByteArray(magic.size).also { reader.get(it) }
        require(tag.contentEquals(magic) && reader.get() == VERSION) {
            "Unsupported backup format"
        }
        val memory = reader.int; val iterations = reader.int
        require(memory == MEMORY_KIB && iterations == ITERATIONS) {
            "Unsupported backup key-derivation policy"
        }
        val salt = ByteArray(SALT_SIZE).also { reader.get(it) }
        val nonce = ByteArray(NONCE_SIZE).also { reader.get(it) }
        val expected = reader.int
        require(expected in 1..MAX_DATA && envelope.size == HEADER_SIZE + expected + 16) {
            "Invalid ciphertext size"
        }
        val key = derive(passphrase, salt, memory, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(envelope, HEADER_SIZE, envelope.size - HEADER_SIZE)
        } finally { key.fill(0) }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, memory: Int, iterations: Int): ByteArray {
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        val key = ByteArray(KEY_SIZE)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt).withMemoryAsKB(memory).withIterations(iterations)
                .withParallelism(1).build()
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(bytes, key)
            return key
        } finally { bytes.fill(0) }
    }
}
