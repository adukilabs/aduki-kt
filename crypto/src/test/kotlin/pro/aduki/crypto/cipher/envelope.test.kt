package pro.aduki.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import pro.aduki.crypto.keystore.Provider
import javax.crypto.Cipher
import java.security.GeneralSecurityException

class EnvelopeTest {

    private val provider = Provider()

    @Test
    fun testRoundtrip() {
        val master = provider.get("test_key")
        val plaintext = "Sensitive token: hm_live_1234567890".toByteArray(Charsets.UTF_8)

        val encrypted = Envelope.encrypt(plaintext, master)
        val decrypted = Envelope.decrypt(encrypted, master)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testUniqueIV() {
        val master = provider.get("test_key_iv")
        val plaintext = "Payload".toByteArray(Charsets.UTF_8)

        val enc1 = Envelope.encrypt(plaintext, master)
        val enc2 = Envelope.encrypt(plaintext, master)

        val iv1 = enc1.copyOfRange(0, 12)
        val iv2 = enc2.copyOfRange(0, 12)
        assertFalse(iv1.contentEquals(iv2))
    }

    @Test
    fun testTamperDetection() {
        val master = provider.get("test_key_tamper")
        val plaintext = "Payload".toByteArray(Charsets.UTF_8)
        val encrypted = Envelope.encrypt(plaintext, master)

        // Corrupt one byte of ciphertext
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1] + 1).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            Envelope.decrypt(encrypted, master)
        }
    }

    @Test
    fun testTruncatedCiphertextRejection() {
        val master = provider.get("test_key_short")
        assertThrows(IllegalArgumentException::class.java) {
            Envelope.decrypt(ByteArray(20), master)
        }
    }
}

/**
 * A JCE provider that behaves like the Android Keystore for keys that require
 * randomized encryption: encryption with a caller-supplied IV is refused, the
 * cipher generates the IV and reports it through `getIV()`. Decryption takes
 * the IV as a parameter. It is selected only for [KeystoreLikeKey].
 */
class KeystoreLikeKey(private val raw: ByteArray) : javax.crypto.SecretKey {
    override fun getAlgorithm() = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null // non-exportable, like a Keystore key
    fun material(): ByteArray = raw
}

class KeystoreLikeProvider : java.security.Provider("KeystoreLike", "1.0", "test double for randomized-encryption keys") {
    init {
        put("Cipher.AES/GCM/NoPadding", KeystoreLikeCipher::class.java.name)
        put("Cipher.AES/GCM/NoPadding SupportedKeyClasses", KeystoreLikeKey::class.java.name)
    }
}

class KeystoreLikeCipher : javax.crypto.CipherSpi() {
    private val inner = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")
    private fun spec(key: java.security.Key) = javax.crypto.spec.SecretKeySpec((key as KeystoreLikeKey).material(), "AES")

    override fun engineSetMode(mode: String?) {}
    override fun engineSetPadding(padding: String?) {}
    override fun engineGetBlockSize() = inner.blockSize
    override fun engineGetOutputSize(inputLen: Int) = inner.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = inner.iv
    override fun engineGetParameters(): java.security.AlgorithmParameters? = inner.parameters
    override fun engineInit(opmode: Int, key: java.security.Key, random: java.security.SecureRandom?) =
        inner.init(opmode, spec(key))
    override fun engineInit(opmode: Int, key: java.security.Key, params: java.security.spec.AlgorithmParameterSpec?, random: java.security.SecureRandom?) {
        if (opmode == Cipher.ENCRYPT_MODE) throw java.security.InvalidAlgorithmParameterException("Caller-provided IV not permitted")
        inner.init(opmode, spec(key), params)
    }
    override fun engineInit(opmode: Int, key: java.security.Key, params: java.security.AlgorithmParameters?, random: java.security.SecureRandom?) {
        if (opmode == Cipher.ENCRYPT_MODE) throw java.security.InvalidAlgorithmParameterException("Caller-provided IV not permitted")
        inner.init(opmode, spec(key), params)
    }
    override fun engineUpdate(input: ByteArray, off: Int, len: Int): ByteArray? = inner.update(input, off, len)
    override fun engineUpdate(input: ByteArray, off: Int, len: Int, output: ByteArray, outOff: Int) = inner.update(input, off, len, output, outOff)
    override fun engineUpdateAAD(src: ByteArray, off: Int, len: Int) = inner.updateAAD(src, off, len)
    override fun engineDoFinal(input: ByteArray?, off: Int, len: Int): ByteArray = inner.doFinal(input ?: ByteArray(0), off, len)
    override fun engineDoFinal(input: ByteArray?, off: Int, len: Int, output: ByteArray, outOff: Int) =
        inner.doFinal(input ?: ByteArray(0), off, len, output, outOff)
}

class EnvelopeRandomizedKeyTest {
    private val provider = KeystoreLikeProvider()

    @org.junit.Before fun install() { java.security.Security.insertProviderAt(provider, 1) }
    @org.junit.After fun uninstall() { java.security.Security.removeProvider(provider.name) }

    private fun key() = KeystoreLikeKey(ByteArray(32) { it.toByte() })

    @Test
    fun theTestDoubleRefusesACallerSuppliedIvLikeTheKeystore() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        assertThrows(java.security.InvalidAlgorithmParameterException::class.java) {
            cipher.init(Cipher.ENCRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, ByteArray(12)))
        }
    }

    @Test
    fun envelopeRoundTripsUnderAKeyThatRequiresRandomizedEncryption() {
        val plain = "sealed under a keystore-like key".toByteArray()
        val sealed = Envelope.encrypt(plain, key())
        assertArrayEquals(plain, Envelope.decrypt(sealed, key()))
        assertFalse(sealed.copyOfRange(0, 12).contentEquals(Envelope.encrypt(plain, key()).copyOfRange(0, 12)))
    }

    @Test
    fun theStoredIvIsTheOneTheCipherGeneratedAndOldFormatStillOpens() {
        val sw = Provider().get("test_key_format")
        val plain = "old and new share one layout".toByteArray()
        // A blob written by the previous implementation (caller-chosen IV, same layout) still decrypts.
        val iv = ByteArray(12) { (it + 1).toByte() }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sw, javax.crypto.spec.GCMParameterSpec(128, iv))
        val legacy = iv + c.doFinal(plain)
        assertArrayEquals(plain, Envelope.decrypt(legacy, sw))
        // A new blob carries the cipher's own IV in its first 12 bytes.
        val fresh = Envelope.encrypt(plain, sw)
        val d = Cipher.getInstance("AES/GCM/NoPadding")
        d.init(Cipher.DECRYPT_MODE, sw, javax.crypto.spec.GCMParameterSpec(128, fresh.copyOfRange(0, 12)))
        assertArrayEquals(plain, d.doFinal(fresh.copyOfRange(12, fresh.size)))
    }
}
