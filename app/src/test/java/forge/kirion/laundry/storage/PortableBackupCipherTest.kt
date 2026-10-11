package forge.kirion.laundry.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupCipherTest {
    @Test fun roundTripAndRandomizedCiphertext() {
        val source = """{"orders":1,"payment_cents":12525}""".toByteArray()
        val pass = "a deliberately long owner passphrase".toCharArray()
        val a = PortableBackupCipher.encrypt(source, pass)
        val b = PortableBackupCipher.encrypt(source, pass)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(source, PortableBackupCipher.decrypt(a, pass))
        assertArrayEquals(source, PortableBackupCipher.decrypt(b, pass))
    }

    @Test fun tamperingOrWrongPassphraseIsRejected() {
        val pass = "a long owner passphrase".toCharArray()
        val data = PortableBackupCipher.encrypt("private shop record".toByteArray(), pass)
        val tampered = data.clone()
        tampered[tampered.lastIndex - 5] = (tampered[tampered.lastIndex - 5].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            PortableBackupCipher.decrypt(tampered, pass)
        }
        assertThrows(Exception::class.java) {
            PortableBackupCipher.decrypt(data, "wrong owner's passphrase".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupCipher.encrypt("test".toByteArray(), "short".toCharArray())
        }
    }
}
