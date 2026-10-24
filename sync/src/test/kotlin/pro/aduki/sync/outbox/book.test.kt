package pro.aduki.sync.outbox

import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher as MockDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.models.Card
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.net.http.Contacts
import pro.aduki.store.box.Factory
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact
import pro.aduki.store.entities.Contact_
import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.sync.http.ContactDispatcher
import pro.aduki.sync.http.HttpContactTransport
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Optimistic contact writes, their outbox actions and `ContactDispatcher` (#36, #37, #39). */
class BookTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var store: BoxStore
    private lateinit var manager: Manager
    private lateinit var book: Book
    private lateinit var server: MockWebServer
    private lateinit var worker: Worker
    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()

    @Volatile private var status = 200
    // The group-wide routes are proposed: 405 stands for a server without them.
    @Volatile private var wide = 405
    @Volatile private var list = """{"items":[],"total":0,"page":1,"pages":0}"""

    @Before
    fun setUp() {
        store = Factory.build(dir.newFolder("db"), Vault(Provider(), "book_${UUID.randomUUID()}_"))
        manager = Manager(store)
        book = Book(store, manager)
        server = MockWebServer()
        server.dispatcher = object : MockDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path} ${request.getHeader("Idempotency-Key") ?: "-"}"
                bodies += request.body.readUtf8()
                if (status != 200) return MockResponse().setResponseCode(status)
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/v1/user/contacts/group/") && request.method != "GET" -> MockResponse().setResponseCode(wide)
                    path.startsWith("/v1/user/contacts/group/") -> MockResponse().setBody(
                        """{"success":true,"data":{"items":[{"hex":"c1","etag":"e","name":"Ann","emails":[],"phones":[],"groups":["friends","x"],"vcard":""},{"hex":"c2","etag":"e","name":"Bo","emails":[],"phones":[],"groups":["friends"],"vcard":""}],"total":2,"page":1,"pages":1}}"""
                    )
                    request.method == "POST" -> MockResponse().setResponseCode(201).setBody(
                        """{"success":true,"data":{"hex":"srv1","uid":"u-srv","etag":"e1","vcard":"","name":"x","emails":[],"phones":[],"groups":[],"meta":{},"created":"2026-10-24T10:00:00Z","updated":"2026-10-24T10:00:01Z"}}"""
                    )
                    request.method == "GET" -> MockResponse().setBody("""{"success":true,"data":$list}""")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
        val api = Contacts(OkHttpClient(), server.url("/v1").toString())
        worker = Worker(manager, ContactDispatcher(api, book::created))
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
        Sealing.install(null)
    }

    private fun stored(hex: String): Contact? = store.boxFor(Contact::class.java).query(Contact_.hex.equal(hex)).build().findFirst()

    private val ann = Card(name = "Ann Lee", emails = listOf("ann@x.example", "a2@x.example"), phones = listOf("+1 555 0100"), company = "Acme", notes = "hi")

    @Test
    fun createIsLocalAtOnceThenTakesTheServersHex() = runBlocking {
        val local = book.create(ann)

        assertTrue(local.hex.startsWith(Book.PLACEHOLDER))
        val row = stored(local.hex)!!
        assertEquals(listOf("ann@x.example", "a2@x.example"), row.emails)
        assertEquals("ann@x.example", row.email)
        assertEquals("Acme", row.company)
        assertTrue(row.uid.isNotBlank())
        assertTrue(row.vcard.contains("UID:${row.uid}"))
        assertEquals(1, manager.count.value)

        // An edit queued before the create is sent follows it to the new hex.
        book.update(local.hex, ann.copy(name = "Ann Park"))
        assertEquals(2, worker.drain())

        assertNull(stored(local.hex))
        assertEquals("Ann Park", stored("srv1")!!.name)
        assertEquals("e1", stored("srv1")!!.ctag)
        assertTrue(requests[0], requests[0].startsWith("POST /v1/user/contacts outbox-"))
        assertEquals("PATCH /v1/user/contacts/srv1/vcard", requests[1].substringBeforeLast(" "))
        assertEquals("PATCH /v1/user/contacts/srv1/groups", requests[2].substringBeforeLast(" "))
        assertTrue(JSONObject(bodies[1]).getString("vcard").contains("FN:Ann Park"))
        assertEquals(0, manager.count.value)
    }

    @Test
    fun aRetryKeepsTheIdempotencyKey() = runBlocking {
        book.create(ann)
        status = 500
        assertEquals(0, worker.drain())
        status = 200
        Thread.sleep(1_200)
        assertEquals(1, worker.drain())
        assertEquals(2, requests.size)
        assertEquals(requests[0].substringAfterLast(" "), requests[1].substringAfterLast(" "))
    }

    @Test
    fun deletingAnUnsentContactDropsItsQueuedActions() = runBlocking {
        val local = book.create(ann)
        book.delete(local.hex)

        assertNull(stored(local.hex))
        assertEquals(0, manager.count.value)
        assertEquals(0, worker.drain())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun deleteOfAMissingContactCompletes() = runBlocking {
        store.boxFor(Contact::class.java).put(Contact(hex = "c1", name = "Gone"))
        book.delete("c1")
        status = 404
        assertEquals(1, worker.drain())
        assertEquals("DELETE /v1/user/contacts/c1 -", requests.single())
        assertEquals(0, manager.count.value)
    }

    @Test
    fun aRefusedWriteIsDropped() = runBlocking {
        store.boxFor(Contact::class.java).put(Contact(hex = "c1", name = "Ann"))
        book.update("c1", ann)
        status = 422
        assertEquals(1, worker.drain())
        assertEquals(0, manager.count.value)
    }

    @Test
    fun groupsAreSetPerContact() = runBlocking {
        store.boxFor(Contact::class.java).put(Contact(hex = "c1", name = "Ann", groups = listOf("old")))
        book.groups(mapOf("c1" to listOf("friends", "work", "friends")))

        assertEquals(listOf("friends", "work"), stored("c1")!!.groups)
        assertTrue(stored("c1")!!.vcard.contains("CATEGORIES:friends,work"))
        worker.drain()
        assertEquals("PATCH /v1/user/contacts/c1/groups", requests.single().substringBeforeLast(" "))
        assertEquals("""{"groups":["friends","work"]}""", bodies.single())
    }

    @Test
    fun renameIsOneActionThatFallsBackToMembers() = runBlocking {
        val box = store.boxFor(Contact::class.java)
        box.put(Contact(hex = "c1", name = "Ann", groups = listOf("friends", "x")))
        box.put(Contact(hex = "c2", name = "Bo", groups = listOf("friends")))

        assertEquals(2, book.rename("friends", "close").size)
        assertEquals(listOf("close", "x"), stored("c1")!!.groups)
        assertEquals(listOf(Book.RENAME), manager.pending().map { it.action })
        assertEquals(1, worker.drain())

        assertEquals(
            listOf("PATCH /v1/user/contacts/group/friends", "GET /v1/user/contacts/group/friends?limit=200&page=1", "PATCH /v1/user/contacts/c1/groups", "PATCH /v1/user/contacts/c2/groups"),
            requests.map { it.substringBeforeLast(" ") }
        )
        assertEquals("""{"name":"close"}""", bodies[0])
        assertEquals("""{"groups":["close","x"]}""", bodies[2])
        assertEquals("""{"groups":["close"]}""", bodies[3])
    }

    @Test
    fun dissolveUsesTheGroupRouteWhenTheServerHasIt() = runBlocking {
        wide = 200
        store.boxFor(Contact::class.java).put(Contact(hex = "c1", name = "Ann", groups = listOf("friends", "x")))

        book.dissolve("friends")

        assertEquals(listOf("x"), stored("c1")!!.groups)
        assertEquals(1, worker.drain())
        assertEquals(listOf("DELETE /v1/user/contacts/group/friends"), requests.map { it.substringBeforeLast(" ") })
    }

    @Test
    fun aQueuedGroupWideWriteHoldsTheSyncBack() = runBlocking {
        store.boxFor(Contact::class.java).put(Contact(hex = "c1", name = "Ann", groups = listOf("friends")))
        book.dissolve("friends")
        val engine = ContactEngine(store, HttpContactTransport(Contacts(OkHttpClient(), server.url("/v1").toString())))

        assertTrue(engine.sync("t1"))

        assertTrue(requests.isEmpty())
        assertEquals("Ann", stored("c1")!!.name)
    }

    @Test
    fun mergeCombinesFieldsAndDeletesTheOthers() = runBlocking {
        val box = store.boxFor(Contact::class.java)
        box.put(Contact(hex = "a", name = "Ann Lee", emails = listOf("ann@x.example"), email = "ann@x.example", groups = listOf("friends")))
        box.put(Contact(hex = "b", name = "ann lee", emails = listOf("ANN@x.example", "b@x.example"), phones = listOf("+1 555"), company = "Acme", notes = "met at", groups = listOf("work")))

        val kept = book.merge("a", listOf("b", "a"))

        assertEquals(listOf("ann@x.example", "b@x.example"), kept.emails)
        assertEquals(listOf("+1 555"), kept.phones)
        assertEquals("Acme", kept.company)
        assertEquals(listOf("friends", "work"), kept.groups)
        assertNull(stored("b"))
        assertEquals(listOf(Book.UPDATE, Book.DELETE), manager.pending().map { it.action })
    }

    @Test
    fun aNameIsRequired() {
        assertThrows(IllegalArgumentException::class.java) { book.create(Card(emails = listOf("x@y.z"))) }
        assertEquals(0, manager.count.value)
    }

    @Test
    fun syncLeavesContactsWithQueuedWritesAlone() = runBlocking {
        val box = store.boxFor(Contact::class.java)
        box.put(Contact(hex = "c1", name = "Ann", ctag = "e0"))
        val local = book.create(ann)
        book.update("c1", ann.copy(name = "Ann Edited"))
        list = """{"items":[{"hex":"c1","etag":"e9","name":"Server Ann","emails":[],"phones":[],"groups":[],"vcard":"","created":"2026-10-24T10:00:00Z","updated":"2026-10-24T10:00:00Z"}],"total":1,"page":1,"pages":1}"""
        val engine = ContactEngine(store, HttpContactTransport(Contacts(OkHttpClient(), server.url("/v1").toString())))

        assertTrue(engine.sync("t1"))

        assertEquals("Ann Edited", stored("c1")!!.name)
        assertEquals("Ann Lee", stored(local.hex)!!.name)
        val token = store.boxFor(pro.aduki.store.entities.Sync::class.java).all.single().token
        assertEquals("", token)
        assertFalse(requests.any { it.startsWith("POST") })
    }
}
