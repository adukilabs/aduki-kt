package pro.aduki.crypto.dpop

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals as assertContentEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DerTest {
    @Test
    fun `a DER ECDSA signature becomes 64 raw bytes that verify as P1363`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val data = "proof".toByteArray()
        repeat(200) { // covers short r or s (leading zeros) and the 0x00 sign padding
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
            val raw = Der.toRaw(der, 32)
            assertEquals(64, raw.size)
            val verify = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pair.public); update(data) }
            assertTrue(verify.verify(raw))
        }
    }

    @Test
    fun `known vector with padded and short integers`() {
        // r = 0x80..(32 bytes, needs 00 pad), s = 0x01 (1 byte)
        val r = ByteArray(32) { 0x80.toByte() }
        val der = byteArrayOf(0x30, (2 + 33 + 2 + 1).toByte(), 0x02, 33, 0) + r + byteArrayOf(0x02, 1, 1)
        val raw = Der.toRaw(der, 32)
        assertContentEquals(r, raw.copyOfRange(0, 32))
        assertContentEquals(ByteArray(31) + byteArrayOf(1), raw.copyOfRange(32, 64))
    }

    @Test
    fun `malformed DER is refused`() {
        assertRefused(byteArrayOf(1, 2, 3))
        assertRefused(byteArrayOf(0x30, 3, 0x02, 1, 1))
    }

    private fun assertRefused(der: ByteArray) {
        try { Der.toRaw(der, 32); throw AssertionError("accepted") } catch (_: IllegalArgumentException) { }
    }
}
