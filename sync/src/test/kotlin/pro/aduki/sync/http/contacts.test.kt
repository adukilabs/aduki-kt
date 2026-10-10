package pro.aduki.sync.http

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.net.http.Contacts
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Sync
import pro.aduki.sync.engine.Contact
import pro.aduki.sync.engine.ContactStorage

/** The contacts transport against the shape of `GET /user/contacts` (aduki docs/http/src/user/contacts.md). */
class HttpContactTransportTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: HttpContactTransport

    private fun row(hex: String, etag: String, name: String? = "N $hex", emails: String = "[\"$hex@example.com\"]") =
        """{"hex":"$hex","etag":"$etag","name":${if (name == null) "null" else "\"$name\""},"emails":$emails,"phones":null,"groups":["friends"],"created":"2026-09-26T09:02:20.658460","total":3}"""

    private fun page(rows: List<String>, next: String? = null) = """{"success":true,"data":{"items":[${rows.joinToString(",")}],"total":3${if (next != null) ",\"next\":\"$next\"" else ""}}}"""

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        transport = HttpContactTransport(Contacts(OkHttpClient(), server.url("/v1").toString()), pageSize = 2)
    }

    @After
    fun tearDown() = server.shutdown()

    private class Book : ContactStorage {
        val contacts = mutableMapOf<String, ContactEntity>()
        var record: Sync? = null
        override fun getSync(target: String) = record
        override fun putSync(sync: Sync) { record = sync }
        override fun getContacts() = contacts.values.toList()
        override fun putContacts(contacts: List<ContactEntity>) { contacts.forEach { this.contacts[it.hex] = it } }
        override fun removeContacts(hexes: List<String>) { hexes.forEach { contacts.remove(it) } }
        override fun <T> tx(block: () -> T): T = block()
    }

    @Test
    fun readsEveryPageByCursorAndMapsRows() = runBlocking {
        reply(page(listOf(row("c1", "e1"), row("c2", "e2", name = null, emails = "null")), next = "c2"))
        reply(page(listOf(row("c3", "e3"))))

        val delta = transport.fetch("t1", "")

        assertTrue(delta.full)
        assertEquals(listOf("c1", "c2", "c3"), delta.changed.map { it.hex })
        assertEquals("c1@example.com", delta.changed[0].email)
        assertEquals("", delta.changed[1].name)
        assertEquals("", delta.changed[1].email)
        assertEquals("e1", delta.changed[0].ctag)
        assertTrue(delta.changed[0].updated > 0)

        val first = server.takeRequest()
        assertEquals("/v1/user/contacts?limit=2", first.path)
        assertEquals("/v1/user/contacts?limit=2&after=c2", server.takeRequest().path)
    }

    @Test
    fun anUnchangedBookIsAnEmptyDelta() = runBlocking {
        reply(page(listOf(row("c1", "e1"), row("c2", "e2"))))
        val first = transport.fetch("t1", "")
        reply(page(listOf(row("c2", "e2"), row("c1", "e1"))))
        val second = transport.fetch("t1", first.ctag)

        assertFalse(second.full)
        assertTrue(second.changed.isEmpty())
        assertEquals(first.ctag, second.ctag)
    }

    @Test
    fun anEtagChangeChangesTheCtag() = runBlocking {
        reply(page(listOf(row("c1", "e1"))))
        val a = transport.fetch("t1", "")
        reply(page(listOf(row("c1", "e9"))))
        val b = transport.fetch("t1", a.ctag)
        assertTrue(b.full)
        assertFalse(a.ctag == b.ctag)
    }

    @Test
    fun theEngineRemovesContactsTheServerNoLongerListsAndKeepsStoredVcards() = runBlocking {
        val book = Book()
        book.putContacts(listOf(
            ContactEntity(hex = "c1", name = "Old", vcard = "BEGIN:VCARD", updated = 1),
            ContactEntity(hex = "gone", name = "Gone", updated = 1)
        ))
        reply(page(listOf(row("c1", "e1"), row("c2", "e2"))))

        assertTrue(Contact(book, transport).sync("t1"))

        assertEquals(setOf("c1", "c2"), book.contacts.keys)
        assertEquals("N c1", book.contacts["c1"]!!.name)
        assertEquals("BEGIN:VCARD", book.contacts["c1"]!!.vcard)
        assertTrue(book.record!!.token.isNotEmpty())

        // Second run: nothing changed, nothing touched.
        reply(page(listOf(row("c1", "e1"), row("c2", "e2"))))
        assertTrue(Contact(book, transport).sync("t1"))
        assertEquals(setOf("c1", "c2"), book.contacts.keys)
    }

    @Test
    fun unauthorizedIsAnAuthError() {
        reply("""{"success":false,"error":{"status":401,"kind":"auth.unauthorized","message":"no"}}""", 401)
        assertThrows(AdukiException.Auth::class.java) { runBlocking { transport.fetch("t1", "") } }
    }

    @Test
    fun aRepeatedCursorIsAProtocolError() {
        reply(page(listOf(row("c1", "e1")), next = "x"))
        reply(page(listOf(row("c2", "e2")), next = "x"))
        assertThrows(AdukiException.Protocol::class.java) { runBlocking { transport.fetch("t1", "") } }
    }

    @Test
    fun serverErrorIsANetworkError() {
        reply("oops", 500)
        val e = assertThrows(AdukiException.Network::class.java) { runBlocking { transport.fetch("t1", "") } }
        assertEquals(500, e.code)
        assertNull(null)
    }
}
