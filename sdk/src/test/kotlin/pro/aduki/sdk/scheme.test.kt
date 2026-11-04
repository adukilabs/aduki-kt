package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import pro.aduki.core.models.Tokens

/** The `Authorization` scheme follows how the credential was supplied, never its prefix. */
class SchemeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sent(build: Aduki.Builder.() -> Aduki.Builder): String? = runBlocking {
        val client = Aduki.builder().endpoint(server.url("/v1").toString()).build(build)
        client.me()
        server.takeRequest().getHeader("Authorization")
    }

    private fun Aduki.Builder.build(extra: Aduki.Builder.() -> Aduki.Builder): Aduki = extra().build()

    @Test
    fun aKeyFromTheKeyBuilderIsSentAsKey() {
        assertEquals("Key opaque-secret", sent { key("opaque-secret") })
    }

    @Test
    fun aTokenIsSentAsBearerEvenWhenItLooksLikeAKey() {
        assertEquals("Bearer hm_not_a_key", sent { token("hm_not_a_key") })
    }

    @Test
    fun aSessionTokenTakesPrecedenceAndIsBearer() = runBlocking {
        val client = Aduki.builder().key("opaque-secret").endpoint(server.url("/v1").toString()).build()
        client.session.update(Tokens(token = "key_session_token", refresh = "r", expires = "2099-01-01T00:00:00Z"))
        client.me()
        assertEquals("Bearer key_session_token", server.takeRequest().getHeader("Authorization"))
    }
}

class SecureStoreTest {
    @org.junit.After
    fun reset() = pro.aduki.store.box.Sealing.install(null)

    @Test
    fun noVaultIsInstalledOnAPlainJvmByDefault() {
        Aduki.builder().key("k").build()
        org.junit.Assert.assertNull(pro.aduki.store.box.Sealing.vault())
    }

    @Test
    fun secureStoreInstallsAVaultOnTheGivenProvider() {
        Aduki.builder().key("k").secureStore(pro.aduki.crypto.keystore.Provider()).build()
        org.junit.Assert.assertNotNull(pro.aduki.store.box.Sealing.vault())
    }

    @Test
    fun aLaterClientKeepsTheInstalledVaultAndItsIndexKeys() {
        val dir = java.nio.file.Files.createTempDirectory("vault").toFile()
        try {
            val store = pro.aduki.store.box.Factory.build(dir, pro.aduki.crypto.cipher.Vault(pro.aduki.crypto.keystore.Provider()))
            val installed = pro.aduki.store.box.Sealing.vault()!!
            org.junit.Assert.assertTrue(pro.aduki.store.box.Sealing.indexed())
            // What Android does for every build: a platform vault is at hand.
            val fresh = pro.aduki.crypto.cipher.Vault(pro.aduki.crypto.keystore.Provider())
            org.junit.Assert.assertNull(Aduki.vault(null, installed) { fresh })
            Aduki.builder().key("k").build()
            org.junit.Assert.assertSame(installed, pro.aduki.store.box.Sealing.vault())
            org.junit.Assert.assertTrue(pro.aduki.store.box.Sealing.indexed())
            store.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun thePlatformVaultIsInstalledWhenNoneIs() {
        val fresh = pro.aduki.crypto.cipher.Vault(pro.aduki.crypto.keystore.Provider())
        org.junit.Assert.assertSame(fresh, Aduki.vault(null, null) { fresh })
    }

    @Test
    fun anExplicitSecureStoreStillReplacesTheInstalledVault() {
        val installed = pro.aduki.crypto.cipher.Vault(pro.aduki.crypto.keystore.Provider())
        val chosen = Aduki.vault(pro.aduki.crypto.keystore.Provider(), installed) { null }
        org.junit.Assert.assertNotNull(chosen)
        org.junit.Assert.assertNotSame(installed, chosen)
    }
}
