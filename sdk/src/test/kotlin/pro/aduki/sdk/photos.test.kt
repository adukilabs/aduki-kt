package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.models.PhotoProgress
import pro.aduki.core.models.SyncProgress
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact as ContactEntity
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** `Contacts.progress`, `photo`, `setPhoto` and `photoUploads` on a client with a store. */
class ContactPhotosTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var client: Aduki? = null
    private val requests = CopyOnWriteArrayList<String>()
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 7, 7)
    private val blob = "0123456789abcdef"

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    private val card = """{"hex":"c1","uid":"u1","etag":"e1","vcard":"","name":"Ada","emails":[],"phones":[],"groups":[],"created":"2026-11-01T10:00:00Z","updated":"2026-11-01T10:00:00Z","photo":{"hex":"$blob","updated":"2026-11-01T10:00:00Z"}}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path ${request.getHeader("If-None-Match") ?: "-"}"
                return when {
                    path == "/v1/user" -> ok("""{"hex":"u1","tenant":"t1","email":"a@aduki.pro","owner":false}""")
                    path.startsWith("/v1/user/contacts/sync") -> ok("""{"items":[$card],"removed":[],"token":"d.1","more":false,"full":true,"total":1}""")
                    path == "/v1/user/contacts/groups" -> ok("""{"items":[]}""")
                    path == "/v1/user/contacts/c1/photo" && request.method == "GET" ->
                        if (request.getHeader("If-None-Match") == "\"$blob\"") MockResponse().setResponseCode(304)
                        else MockResponse().setBody(Buffer().write(jpeg)).setHeader("ETag", "\"$blob\"").setHeader("Content-Type", "image/jpeg")
                    path == "/v1/user/contacts/c1/photo" && request.method == "PUT" -> ok(card.replace(blob, "fedcba9876543210"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        client?.close()
        server.shutdown()
        Sealing.install(null)
    }

    private fun build(db: File = dir.newFolder("db")): Aduki =
        Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).store(db).build().also { client = it }

    @Test
    fun syncReportsProgressAndPhotosAreCached() = runBlocking {
        val db = dir.newFolder("db")
        val client = build(db)
        assertEquals(SyncProgress.Idle, client.contacts.progress.value)

        assertTrue(client.contacts.sync())

        assertTrue(client.contacts.progress.value is SyncProgress.Done)
        assertEquals(client.contacts.progress.value, client.sync.contacts.value)
        val contact = client.contacts.get("c1")!!
        assertEquals(blob, contact.photoHex)

        // Downloaded once, then from the sealed cache under `photos`.
        assertArrayEquals(jpeg, client.contacts.photo("c1"))
        assertArrayEquals(jpeg, client.contacts.photo(contact))
        assertEquals(1, requests.count { it.startsWith("GET /v1/user/contacts/c1/photo") })
        assertTrue(File(db, "photos").list()!!.any { it == "blob-$blob" })

        // A refresh asks with the blob id and keeps the copy on a 304.
        assertArrayEquals(jpeg, client.contacts.photo("c1", refresh = true))
        assertTrue(requests.last(), requests.last().endsWith("\"$blob\""))
    }

    @Test
    fun aPhotoSetHereIsShownAtOnceAndCounted() = runBlocking {
        val client = build()
        client.contacts.sync()

        val local = client.contacts.setPhoto("c1", jpeg)

        assertEquals("fedcba9876543210", client.contacts.get("c1")!!.photoHex)
        assertTrue(local.photoHex.isNotEmpty())
        assertArrayEquals(jpeg, client.contacts.photo("c1"))
        val progress = withTimeout(5_000) { client.contacts.photoUploads.first { it.done == 1 } }
        assertEquals(PhotoProgress(queued = 0, done = 1, failed = 0), progress)
    }

    @Test
    fun anInlinePhotoIsDecoded() = runBlocking {
        val client = build()
        val contact = ContactEntity(hex = "x", photo = "data:image/jpeg;base64,/9j/4A==")
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()), client.contacts.photo(contact))
        assertNull(client.contacts.photo(ContactEntity(hex = "y")))
    }
}
