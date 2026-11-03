package pro.aduki.crypto.keystore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.crypto.cipher.Envelope
import pro.aduki.crypto.cipher.Vault
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The Provider keeps the loaded Keystore and its keys: a sealed field is one cipher operation. */
class CachingTest {

    // A Keystore in memory that counts loads and lookups, standing in for the Android Keystore.
    private class Counting : KeyStoreSpi() {
        val entries = HashMap<String, SecretKey>()
        val lookups = AtomicInteger()

        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            lookups.incrementAndGet()
            return entries[alias]
        }

        override fun engineContainsAlias(alias: String): Boolean {
            lookups.incrementAndGet()
            return entries.containsKey(alias)
        }

        override fun engineDeleteEntry(alias: String) {
            entries.remove(alias)
        }

        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            entries[alias] = key as SecretKey
        }

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys.toList())
        override fun engineSize(): Int = entries.size
        override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    private class Store(spi: KeyStoreSpi) : KeyStore(spi, null, "counting")

    private val spi = Counting()
    private val loads = AtomicInteger()
    private val provider = Provider("counting") { _ ->
        loads.incrementAndGet()
        Store(spi).apply { load(null) }
    }

    private fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun manyFieldsCostOneLoadAndNoFurtherLookups() {
        spi.entries["data_1"] = aes()
        val vault = Vault(provider, "data_")
        val sealed = (1..600).map { vault.sealText("contact $it") }
        val loadsBefore = loads.get()
        val lookupsBefore = spi.lookups.get()

        val opened = sealed.map { vault.openText(it) }

        assertEquals("contact 600", opened.last())
        assertEquals("the Keystore is loaded once", 1, loads.get())
        assertEquals("no Keystore load while opening", loadsBefore, loads.get())
        assertEquals("no Keystore lookup while opening", lookupsBefore, spi.lookups.get())
    }

    @Test
    fun aKeyIsLookedUpOnce() {
        spi.entries["k"] = aes()

        val first = provider.get("k")
        val lookups = spi.lookups.get()
        repeat(100) {
            assertSame(first, provider.get("k"))
            assertSame(first, provider.find("k"))
            assertTrue(provider.has("k"))
        }

        assertEquals(lookups, spi.lookups.get())
        assertEquals(1, loads.get())
    }

    @Test
    fun findNeverCreates() {
        assertNull(provider.find("missing"))
        assertFalse(spi.entries.containsKey("missing"))
    }

    @Test
    fun removeAndForgetDropTheKeptKey() {
        spi.entries["k"] = aes()
        provider.get("k")

        provider.remove("k")

        assertFalse(spi.entries.containsKey("k"))
        assertFalse(provider.has("k"))
        assertNull(provider.find("k"))

        val replaced = aes()
        spi.entries["j"] = aes()
        provider.get("j")
        spi.entries["j"] = replaced
        provider.forget("j")
        assertSame(replaced, provider.get("j"))

        provider.forget()
        provider.get("j")
        assertEquals("forget() drops the loaded Keystore too", 2, loads.get())
    }

    @Test
    fun aStaleKeptKeyIsReadAgainOnce() {
        val stale = aes()
        val real = aes()
        spi.entries["data_1"] = stale
        val vault = Vault(provider, "data_")
        provider.get("data_1") // kept: the stale key
        spi.entries["data_1"] = real // replaced behind the Provider's back

        // A value sealed under the real key, in the vault's layout.
        val body = Envelope.encrypt("hello".toByteArray(), real)
        val header = byteArrayOf(0xAD.toByte(), 0x4B, 1, 0, 0, 0, 1)

        assertArrayEquals("hello".toByteArray(), vault.open(header + body))
    }
}
