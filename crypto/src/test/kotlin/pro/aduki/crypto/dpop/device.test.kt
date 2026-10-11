package pro.aduki.crypto.dpop

import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceTest {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** The X.509 SubjectPublicKeyInfo of a P-256 key ends with the uncompressed point: an encoder-independent expectation. */
    private fun spkiPoint(key: java.security.PublicKey) = key.encoded.takeLast(65).toByteArray()

    @Test
    fun `the P-256 point is 0x04 x y and matches the X509 encoding, including short coordinates`() {
        var short = 0
        var tries = 0
        while (short < 3 && tries < 20000) { // a coordinate with a leading zero byte shows up about once in 128 keys
            tries++
            val key = pair().public as ECPublicKey
            val point = Device.point(key)
            assertEquals(65, point.size)
            assertEquals(0x04.toByte(), point[0])
            assertArrayEquals(spkiPoint(key), point)
            if (key.w.affineX.toByteArray().dropWhile { it == 0.toByte() }.size < 32 ||
                key.w.affineY.toByteArray().dropWhile { it == 0.toByte() }.size < 32) short++
        }
        assertTrue("found no short coordinate in $tries keys", short > 0)
    }

    @Test
    fun `the public key derived from a Key is the same point and the algorithm follows the key type`() {
        val p256 = Software.p256()
        assertEquals("ES256", Device.alg(p256))
        val point = Device.public(p256)
        assertEquals(65, point.size)
        assertEquals(0x04.toByte(), point[0])
        assertEquals(Software.b64(point.copyOfRange(1, 33)), p256.jwk["x"])
        assertEquals(Software.b64(point.copyOfRange(33, 65)), p256.jwk["y"])

        val ed = Software.ed25519()
        assertEquals("EdDSA", Device.alg(ed))
        assertEquals(32, Device.public(ed).size)
    }

    @Test
    fun `a key that is neither P-256 nor Ed25519 is refused`() {
        val odd = object : Key {
            override val alg = "RS256"
            override val jwk = mapOf("kty" to "RSA")
            override fun sign(input: ByteArray) = ByteArray(64)
        }
        try { Device.alg(odd); fail() } catch (_: IllegalArgumentException) {}
        val lying = object : Key {
            override val alg = "ES256"
            override val jwk = mapOf("kty" to "OKP", "crv" to "Ed25519", "x" to "AAAA")
            override fun sign(input: ByteArray) = ByteArray(64)
        }
        try { Device.public(lying); fail() } catch (_: IllegalArgumentException) {}
    }
}
