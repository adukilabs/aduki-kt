package pro.aduki.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.crypto.keystore.Provider
import java.util.UUID

/** Vault on the software Provider (JVM). The Android Keystore path is unverified. */
class VaultTest {

    private fun vault() = Vault(Provider(), "test_${UUID.randomUUID()}_")

    @Test
    fun roundTripsTextAndBytesAndDoesNotKeepThePlaintext() {
        val v = vault()
        val sealed = v.sealText("BEGIN:VCARD secret")
        assertTrue(sealed.startsWith(Vault.TEXT))
        assertFalse(sealed.contains("secret"))
        assertEquals("BEGIN:VCARD secret", v.openText(sealed))

        val bytes = v.sealBytes("payload".toByteArray())
        assertTrue(Vault.isSealed(bytes))
        assertArrayEquals("payload".toByteArray(), v.openBytes(bytes))
        assertNotEquals(sealed, v.sealText("BEGIN:VCARD secret")) // fresh IV each time
    }

    @Test
    fun emptyValuesStayEmpty() {
        val v = vault()
        assertEquals("", v.sealText(""))
        assertEquals(0, v.sealBytes(ByteArray(0)).size)
    }

    @Test
    fun legacyPlaintextPassesThroughOnRead() {
        val v = vault()
        assertEquals("old plain text", v.openText("old plain text"))
        assertArrayEquals("{\"a\":1}".toByteArray(), v.openBytes("{\"a\":1}".toByteArray()))
    }

    @Test
    fun aTamperedValueFailsClosed() {
        val v = vault()
        val sealed = v.seal("hello".toByteArray())
        for (i in listOf(sealed.size - 1, sealed.size / 2, 8)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(SealException::class.java) { v.open(bad) }
        }
        assertThrows(SealException::class.java) { v.openText(Vault.TEXT + "!!!not base64") }
    }

    @Test
    fun anUnknownKeyIdOrVersionFailsClosed() {
        val v = vault()
        val sealed = v.seal("hello".toByteArray())
        val otherKey = sealed.copyOf().also { it[6] = 99 }
        assertThrows(SealException::class.java) { v.open(otherKey) }
        val otherVersion = sealed.copyOf().also { it[2] = 2 }
        assertThrows(SealException::class.java) { v.open(otherVersion) }
    }

    @Test
    fun aValueSealedByAnotherVaultKeyDoesNotOpen() {
        val sealed = vault().seal("hello".toByteArray())
        assertThrows(SealException::class.java) { vault().open(sealed) } // different alias prefix: key id 1 unknown or wrong key
    }

    @Test
    fun rotationSealsUnderTheNewKeyAndStillOpensOldValues() {
        val v = vault()
        assertEquals(1, v.keyId())
        val old = v.sealText("before")
        assertEquals(2, v.rotate())
        val fresh = v.sealText("after")
        assertEquals("before", v.openText(old))
        assertEquals("after", v.openText(fresh))
        assertEquals(2, java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(fresh.removePrefix(Vault.TEXT)), 3, 4).int)
        assertEquals(1, java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(old.removePrefix(Vault.TEXT)), 3, 4).int)
    }
}
