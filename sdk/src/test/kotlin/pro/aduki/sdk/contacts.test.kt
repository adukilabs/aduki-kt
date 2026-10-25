package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Sync
import pro.aduki.sync.engine.ContactStorage

/** `client.contacts.sync()` reads the address book over REST once a storage is given. */
class ContactsSyncTest {

    private lateinit var server: MockWebServer
    private val paths = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when {
                    request.path == "/v1/user" ->
                        MockResponse().setBody("""{"success":true,"data":{"hex":"u1","tenant":"t1","email":"a@aduki.pro","owner":false}}""")
                    request.path!!.startsWith("/v1/user/contacts") ->
                        MockResponse().setBody("""{"success":true,"data":{"items":[{"hex":"c1","etag":"e1","name":"Ada","emails":["ada@example.com"],"phones":null,"groups":null,"created":"2026-09-26T09:02:20"}],"total":1}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
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
    fun syncReadsTheAddressBookIntoTheStorage() = runBlocking {
        val book = Book()
        val client = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).contactStorage(book).build()

        assertTrue(client.contacts.sync())

        assertEquals("Ada", book.contacts["c1"]?.name)
        assertEquals("ada@example.com", book.contacts["c1"]?.email)
        assertTrue(paths.any { it.startsWith("/v1/user/contacts") })
    }

    @Test
    fun withoutAStorageOrEngineSyncIsFalse() = runBlocking {
        val client = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).build()
        assertEquals(false, client.contacts.sync())
    }

    @Test
    fun hitIdentifiesMatchedFieldAndRange() {
        val client = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).build()
        val contact = ContactEntity(
            hex = "c1",
            name = "Ann Lee",
            email = "ann@example.com",
            emails = listOf("ann@example.com", "second@test.pro"),
            notes = "Met in Mombasa"
        )

        val hitName = client.contacts.hit(contact, "Lee")
        assertEquals("name", hitName?.field)
        assertEquals(4..6, hitName?.range)

        val hitSecondEmail = client.contacts.hit(contact, "test.pro")
        assertEquals("email", hitSecondEmail?.field)
        assertEquals(7..14, hitSecondEmail?.range)

        val hitNotes = client.contacts.hit(contact, "mombasa")
        assertEquals("notes", hitNotes?.field)
        assertEquals(7..13, hitNotes?.range)
    }
}
