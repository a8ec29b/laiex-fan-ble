package dev.laiex.fan.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.Assert.assertThrows
import org.bouncycastle.crypto.InvalidCipherTextException

/** Synthetic vectors generated independently with Python cryptography. */
class CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun loginDerivationMatchesIndependentVector() {
        assertArrayEquals(hex("18eea5008b0cee3744c215c7cd9ead268670b5056f4a71ffd1d4ea419e3c84be2a03093f8eb73a36cf1abcf9d346e44edd6594b602b018ac98d72cca94609c2a"),
            Crypto.derive(ByteArray(12) { it.toByte() }, ByteArray(32) { it.toByte() }))
    }
    @Test fun commandEncryptionMatchesIndependentVector() {
        assertArrayEquals(hex("2beefa3a2f80e5cfd6834143b1"), Crypto.ccm(true,
            ByteArray(16) { it.toByte() }, hex("000102030000000007000000"), hex("092007000201020200")))
    }
    @Test fun commandDecryptionAcceptsIndependentVector() {
        assertArrayEquals(hex("092007000201020200"), Crypto.ccm(false,
            ByteArray(16) { it.toByte() }, hex("000102030000000007000000"), hex("2beefa3a2f80e5cfd6834143b1")))
    }
    @Test fun modifiedAuthenticationTagIsRejected() {
        val ciphertext = hex("2beefa3a2f80e5cfd6834143b1")
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        assertThrows(InvalidCipherTextException::class.java) {
            Crypto.ccm(false, ByteArray(16) { it.toByte() }, hex("000102030000000007000000"), ciphertext)
        }
    }
}
