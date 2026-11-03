package pro.aduki.sync.http

import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.SyncProgress
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.net.http.Contacts
import pro.aduki.store.box.Factory
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Contact_
import pro.aduki.store.entities.Outbox
import pro.aduki.store.entities.Sync
import pro.aduki.sync.engine.Contact
import pro.aduki.sync.engine.ContactDelta
import pro.aduki.sync.engine.ContactStorage
import pro.aduki.sync.engine.ContactTransport
import pro.aduki.sync.outbox.Book
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * `GET /user/contacts/sync` through `HttpContactTransport` into the engine
 * on a real (sealed) ObjectBox store: paging, the stored token, the
 * fallbacks, the single pass and the progress it reports.
 */
class ContactChangesTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var store: BoxStore
    private lateinit var server: MockWebServer
    private lateinit var engine: Contact
    private val paths = CopyOnWriteArrayList<String>()
    private val replies = LinkedBlockingQueue<MockResponse>()

    // Rows the engine put, by hex, across the test.
    private val puts = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        store = Factory.build(dir.newFolder("db"), Vault(Provider(), "csync_${UUID.randomUUID()}_"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                return when {
                    path.startsWith("/v1/user/contacts/groups") -> ok("""{"items":[{"name":"friends","count":1}]}""")
                    else -> replies.poll() ?: MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        val base = Contact.storage(store)
        val counting = object : ContactStorage by base {
            override fun putContacts(contacts: List<ContactEntity>) {
                puts += contacts.map { it.hex }
                base.putContacts(contacts)
            }
        }
        engine = Contact(counting, HttpContactTransport(Contacts(OkHttpClient(), server.url("/v1").toString()), pageSize = 2))
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
        Sealing.install(null)
    }

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    private fun card(hex: String, etag: String, name: String = "N $hex", photo: String = "null") =
        """{"hex":"$hex","uid":"u-$hex","etag":"$etag","vcard":"","name":"$name","emails":["$hex@example.com"],"phones":[],"groups":["friends"],"meta":{},"created":"2026-11-01T10:00:00Z","updated":"2026-11-01T10:00:00Z","photo":$photo}"""

    private fun page(items: List<String>, token: String, more: Boolean, full: Boolean, removed: List<String> = emptyList(), total: Int? = null) =
        ok(
            """{"items":[${items.joinToString(",")}],"removed":[${removed.joinToString(",") { "\"$it\"" }}],"token":"$token","more":$more,"full":$full${if (total != null) ",\"total\":$total" else ""}}"""
        )

    private fun stored(hex: String): ContactEntity? =
        store.boxFor(ContactEntity::class.java).query(Contact_.hex.equal(hex)).build().use { it.findFirst() }

    private fun token(): String? =
        store.boxFor(Sync::class.java).all.firstOrNull { it.target == "contacts" }?.token

    private fun seed(vararg rows: Pair<String, String>) {
        store.boxFor(ContactEntity::class.java).put(rows.map { (hex, etag) -> ContactEntity(hex = hex, name = "Old $hex", ctag = etag, updated = 1) })
    }

    @Test
    fun aFullListingIsPagedThenOnlyChangesAreRead() = runBlocking {
        replies += page(listOf(card("a1", "e1"), card("a2", "e2")), "f.9.a2", more = true, full = true, total = 3)
        replies += page(listOf(card("a3", "e3", photo = """{"hex":"00ff00ff00ff00ff","updated":"2026-11-02T08:00:00Z"}""")), "d.9", more = false, full = true)

        assertTrue(engine.sync("t1"))

        assertTrue(paths[0], paths[0].startsWith("/v1/user/contacts/sync?limit=2") && "since" !in paths[0])
        assertTrue(paths[1], paths[1].contains("since=f.9.a2"))
        assertEquals("d.9", token())
        assertEquals("N a1", stored("a1")!!.name)
        assertEquals("00ff00ff00ff00ff", stored("a3")!!.photoHex)
        assertTrue(stored("a3")!!.photoUpdated > 0)
        val done = engine.progress.value as SyncProgress.Done
        assertEquals(3, done.changed)

        // The next sync sends the token and gets one change and one removal.
        paths.clear()
        replies += page(listOf(card("a2", "e2b", name = "Changed")), "d.11", more = false, full = false, removed = listOf("a1"))
        assertTrue(engine.sync("t1"))

        assertTrue(paths[0], paths[0].contains("since=d.9"))
        assertNull(stored("a1"))
        assertEquals("Changed", stored("a2")!!.name)
        assertNotNull(stored("a3"))
        assertEquals("d.11", token())
        assertEquals(2, (engine.progress.value as SyncProgress.Done).changed)
    }

    @Test
    fun rowsWhoseEtagIsStoredAreNotPutAgain() = runBlocking {
        seed("a1" to "e1", "a2" to "e2")
        replies += page(listOf(card("a1", "e1"), card("a2", "e2-new")), "d.3", more = false, full = true, total = 2)

        assertTrue(engine.sync("t1"))

        assertEquals(listOf("a2"), puts)
        assertEquals("Old a1", stored("a1")!!.name)
        assertEquals("N a2", stored("a2")!!.name)
        assertEquals(1, (engine.progress.value as SyncProgress.Done).changed)
    }

    @Test
    fun theSweepWaitsForTheLastPageOfTheListing() = runBlocking {
        seed("gone" to "e0")
        replies += page(listOf(card("a1", "e1"), card("a2", "e2")), "f.9.a2", more = true, full = true, total = 3)
        replies += MockResponse().setResponseCode(503)

        assertThrows(AdukiException.Network::class.java) { runBlocking { engine.sync("t1") } }

        // The first page is kept, nothing is swept, and no token is stored.
        assertNotNull(stored("a1"))
        assertNotNull(stored("gone"))
        assertEquals("", token().orEmpty())
        assertTrue(engine.progress.value is SyncProgress.Failed)

        replies += page(listOf(card("a1", "e1"), card("a2", "e2")), "f.9.a2", more = true, full = true, total = 3)
        replies += page(listOf(card("a3", "e3")), "d.9", more = false, full = true)
        assertTrue(engine.sync("t1"))

        assertNull(stored("gone"))
        assertNotNull(stored("a3"))
        assertEquals("d.9", token())
    }

    @Test
    fun anExpiredTokenStartsAFullListing() = runBlocking {
        seed("a1" to "e1", "gone" to "e0")
        store.boxFor(Sync::class.java).put(Sync(target = "contacts", token = "d.5"))
        replies += MockResponse().setResponseCode(410).setBody("""{"success":false,"error":{"status":410,"kind":"sync.reset","message":"start over"}}""")
        replies += page(listOf(card("a1", "e1")), "d.20", more = false, full = true, total = 1)

        assertTrue(engine.sync("t1"))

        assertTrue(paths[0], paths[0].contains("since=d.5"))
        assertTrue(paths[1], "since" !in paths[1])
        assertNull(stored("gone"))
        assertEquals("d.20", token())
    }

    @Test
    fun aBadTokenStartsAFullListing() = runBlocking {
        // A digest stored by the old full walk is not a sync token.
        store.boxFor(Sync::class.java).put(Sync(target = "contacts", token = "ab".repeat(32)))
        replies += MockResponse().setResponseCode(422)
        replies += page(listOf(card("a1", "e1")), "d.2", more = false, full = true, total = 1)

        assertTrue(engine.sync("t1"))
        assertEquals("d.2", token())
        assertNotNull(stored("a1"))
    }

    @Test
    fun aServerWithoutTheRouteIsWalkedTheOldWay() = runBlocking {
        seed("gone" to "e0")
        replies += MockResponse().setResponseCode(404)
        replies += ok("""{"items":[${card("a1", "e1")}],"total":1}""")

        assertTrue(engine.sync("t1"))

        assertTrue(paths[0], paths[0].startsWith("/v1/user/contacts/sync"))
        assertTrue(paths[1], paths[1].startsWith("/v1/user/contacts?limit=2"))
        assertNotNull(stored("a1"))
        assertNull(stored("gone"))
        assertEquals(64, token()!!.length)

        // Remembered: the next sync goes straight to the list.
        paths.clear()
        replies += ok("""{"items":[${card("a1", "e1")}],"total":1}""")
        assertTrue(engine.sync("t1"))
        assertTrue(paths.none { it.startsWith("/v1/user/contacts/sync") })
    }

    @Test
    fun aPendingRowIsLeftAndTheCursorDropped() = runBlocking {
        seed("a1" to "e1")
        store.boxFor(Outbox::class.java).put(Outbox(action = Book.UPDATE, hex = "a1", payload = "{}".toByteArray()))
        replies += page(listOf(card("a1", "e1-server")), "d.4", more = false, full = false)

        assertTrue(engine.sync("t1"))

        assertEquals("Old a1", stored("a1")!!.name)
        assertEquals("", token())
    }

    @Test
    fun progressCountsPagesAndTheTotal() = runBlocking {
        val seen = mutableListOf<SyncProgress>()
        lateinit var observed: Contact
        val transport = object : ContactTransport {
            override suspend fun fetch(tenant: String, ctag: String) = throw UnsupportedOperationException()
            override suspend fun pages(tenant: String, ctag: String, apply: suspend (ContactDelta) -> Unit) {
                seen += observed.progress.value
                apply(ContactDelta(changed = listOf(ContactEntity(hex = "p1", ctag = "1"), ContactEntity(hex = "p2", ctag = "2")), ctag = "f", full = true, more = true, total = 3))
                seen += observed.progress.value
                apply(ContactDelta(changed = listOf(ContactEntity(hex = "p3", ctag = "3")), ctag = "d.1", full = true))
                seen += observed.progress.value
            }
        }
        observed = Contact(store, transport)
        assertEquals(SyncProgress.Idle, observed.progress.value)

        observed.sync("t1")

        assertEquals(listOf(SyncProgress.Running(0, null), SyncProgress.Running(2, 3), SyncProgress.Running(3, 3)), seen)
        val done = observed.progress.value as SyncProgress.Done
        assertEquals(3, done.changed)
        assertTrue(done.at > 0)
    }
}
