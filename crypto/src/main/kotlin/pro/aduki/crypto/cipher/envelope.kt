package pro.aduki.crypto.cipher

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM Envelope cipher for encrypting database keys and sensitive attachments.
 */
object Envelope {
    private const val TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    /**
     * Encrypts plaintext using AES-256-GCM with a random 12-byte IV.
     */
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(plain)

        val result = ByteArray(IV_LENGTH + encrypted.size)
        System.arraycopy(iv, 0, result, 0, IV_LENGTH)
        System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.size)
        return result
    }

    /**
     * Decrypts AES-256-GCM envelope ciphertext and verifies AEAD authentication tag.
     */
    fun decrypt(cipherText: ByteArray, key: SecretKey): ByteArray {
        require(cipherText.size >= IV_LENGTH + (TAG_LENGTH / 8)) { "Ciphertext too short to contain IV and tag" }
        val iv = ByteArray(IV_LENGTH)
        System.arraycopy(cipherText, 0, iv, 0, IV_LENGTH)

        val bodyLength = cipherText.size - IV_LENGTH
        val body = ByteArray(bodyLength)
        System.arraycopy(cipherText, IV_LENGTH, body, 0, bodyLength)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH, iv))
        return cipher.doFinal(body)
    }
}
