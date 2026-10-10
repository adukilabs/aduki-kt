package pro.aduki.crypto.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderTest {

    private val provider = Provider()

    @Test
    fun testGetGeneratesKey() {
        val key = provider.get("alias_alpha")
        assertNotNull(key)
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun testCreateNewKey() {
        val key = provider.create("alias_beta")
        assertNotNull(key)
        assertEquals(32, key.encoded.size) // 256 bits = 32 bytes
    }

    @Test
    fun testKeyPersistenceForSameAlias() {
        val key1 = provider.get("alias_persistent")
        val key2 = provider.get("alias_persistent")
        assertEquals(key1, key2)
    }

    @Test
    fun testMasterAliasIsAduki() {
        assertEquals("aduki_master", Provider.MASTER)
        assertEquals("hermes_master", Provider.LEGACY_MASTER)
    }

    @Test
    fun testMasterFallsBackToLegacyAlias() {
        val p = Provider()
        p.remove(Provider.MASTER)
        val legacy = p.get(Provider.LEGACY_MASTER)
        // An install that only has the old alias keeps getting its existing key.
        assertEquals(legacy, p.get(Provider.MASTER))
        assertEquals(legacy, p.get())
        p.remove(Provider.MASTER)
    }

    @Test
    fun testNewMasterKeyUsesNewAlias() {
        val p = Provider()
        p.remove(Provider.MASTER)
        val key = p.get(Provider.MASTER)
        assertEquals(key, p.get(Provider.MASTER))
        assertEquals(false, p.has(Provider.LEGACY_MASTER))
        p.remove(Provider.MASTER)
    }
}
