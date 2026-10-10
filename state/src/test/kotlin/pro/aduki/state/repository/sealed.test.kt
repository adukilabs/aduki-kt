package pro.aduki.state.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.store.box.Factory
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact as ContactEntity
import java.util.UUID

/** Repositories over a real store with sealed personal columns: lookups and search keep working. */
class SealedRepositoryTest {

    @get:Rule
    val dir = TemporaryFolder()

    @After
    fun reset() = Sealing.install(null)

    @Test
    fun lookupsAndSearchWorkOverSealedColumns() = runBlocking {
        val vault = Vault(Provider(), "state_${UUID.randomUUID()}_")
        Factory.build(dir.newFolder("db"), vault).use { store ->
            val box = store.boxFor(ContactEntity::class.java)
            box.put(ContactEntity(hex = "a", name = "Ada Lovelace", email = "ada@example.com", phone = "+254 700 111 222"))
            box.put(ContactEntity(hex = "b", name = "Bob Builder", email = "bob@example.com", phone = "+254700333444"))

            val repo = Contact(store, CoroutineScope(Dispatchers.Default))
            assertEquals("a", repo.byEmail("ADA@example.com")?.hex)
            assertEquals("b", repo.byPhone("+254-700-333-444")?.hex)
            assertNull(repo.byEmail("nobody@example.com"))

            val hits = repo.search("lovelace")
            withTimeout(5000) { while (hits.value.isEmpty()) delay(20) }
            assertEquals(listOf("a"), hits.value.map { it.hex })
            val all = repo.observe()
            withTimeout(5000) { while (all.value.size < 2) delay(20) }
            assertEquals(listOf("Ada Lovelace", "Bob Builder"), all.value.map { it.name })
        }
    }

    @Test
    fun withoutAVaultLookupsFallBackToAScan() = runBlocking {
        Factory.build(dir.newFolder("plain")).use { store ->
            store.boxFor(ContactEntity::class.java).put(ContactEntity(hex = "a", email = "ada@example.com", phone = "0700"))
            val repo = Contact(store, CoroutineScope(Dispatchers.Default))
            assertEquals("a", repo.byEmail("Ada@Example.com")?.hex)
            assertEquals("a", repo.byPhone("0700")?.hex)
        }
    }
}
