package pro.aduki.sync.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Sync

class ContactTest {

    private class TestStorage : ContactStorage {
        val contacts = mutableMapOf<String, ContactEntity>()
        var syncRecord: Sync? = null

        override fun getSync(target: String): Sync? = syncRecord
        override fun putSync(sync: Sync) { syncRecord = sync }
        override fun getContacts(): List<ContactEntity> = contacts.values.toList()
        override fun putContacts(contacts: List<ContactEntity>) {
            contacts.forEach { this.contacts[it.hex] = it }
        }
        override fun removeContacts(hexes: List<String>) {
            hexes.forEach { contacts.remove(it) }
        }
        override fun <T> tx(block: () -> T): T = block()
    }

    private lateinit var storage: TestStorage

    @Before
    fun setup() {
        storage = TestStorage()
        storage.putContacts(
            listOf(
                ContactEntity(hex = "c1", name = "Alice", email = "alice@example.com", updated = 100L),
                ContactEntity(hex = "c2", name = "Bob", email = "bob@example.com", updated = 100L)
            )
        )
    }

    @Test
    fun anotherAccountOnTheSameStoreStartsWithAFullListing() = runBlocking {
        val records = mutableMapOf<String, Sync>()
        val keyed = object : ContactStorage by storage {
            override fun getSync(target: String): Sync? = records[target]
            override fun putSync(sync: Sync) { records[sync.target] = sync }
        }
        val asked = mutableListOf<String>()
        val transport = ContactTransport { _, ctag ->
            asked += ctag
            if (ctag.isEmpty()) {
                ContactDelta(changed = listOf(ContactEntity(hex = "b1", name = "Bea", updated = 1L)), removed = emptyList(), ctag = "bob_1", full = true)
            } else {
                ContactDelta(changed = emptyList(), removed = emptyList(), ctag = ctag)
            }
        }
        val engine = Contact(keyed, transport)
        records["contacts/ada"] = Sync(target = "contacts/ada", token = "ada_9")

        engine.sync("t", "bob")
        assertEquals("Ada's cursor is not used for Bob", listOf(""), asked)
        assertEquals(setOf("b1"), storage.contacts.keys)
        assertEquals("bob_1", records["contacts/bob"]?.token)
        assertEquals("ada_9", records["contacts/ada"]?.token)

        engine.sync("t", "ada")
        assertEquals(listOf("", "ada_9"), asked)
    }

    @Test
    fun testContactDeltaSync() = runBlocking {
        val transport = ContactTransport { _, ctag ->
            assertEquals("", ctag)
            ContactDelta(
                changed = listOf(
                    ContactEntity(hex = "c2", name = "Robert", email = "robert@example.com", updated = 200L),
                    ContactEntity(hex = "c3", name = "Charlie", email = "charlie@example.com", updated = 200L)
                ),
                removed = listOf("c1"),
                ctag = "ctag_v2"
            )
        }

        val engine = Contact(storage, transport)
        val success = engine.sync("tenant_1")
        assertTrue(success)

        val current = storage.getContacts()
        assertEquals(2, current.size)

        // c1 was removed
        assertNull(storage.contacts["c1"])

        // c2 was updated
        val c2 = storage.contacts["c2"]
        assertNotNull(c2)
        assertEquals("Robert", c2!!.name)

        // c3 was added
        val c3 = storage.contacts["c3"]
        assertNotNull(c3)
        assertEquals("Charlie", c3!!.name)

        // Token was saved
        assertEquals("ctag_v2", storage.getSync("contacts")!!.token)
    }
}

