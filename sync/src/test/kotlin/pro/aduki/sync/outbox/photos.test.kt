package pro.aduki.sync.outbox

import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher as MockDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.net.http.Contacts
import pro.aduki.store.box.Factory
import pro.aduki.store.box.Files
import pro.aduki.store.box.Sealing
import pro.aduki.store.entities.Contact
import pro.aduki.store.entities.Contact_
import pro.aduki.sync.http.ContactDispatcher
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** `contact.photo` / `contact.unphoto`: the sealed spool, `ContactDispatcher` and the cache. */
class PhotosTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var store: BoxStore
    private lateinit var manager: Manager
    private lateinit var photos: Photos
    private lateinit var files: File
    private lateinit var book: Book
    private lateinit var server: MockWebServer
    private lateinit var worker: Worker
    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<ByteArray>()

    @Volatile private var status = 200

    // A JPEG as far as the magic bytes go.
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3, 4)

    private fun card(photo: String) =
        """{"success":true,"data":{"hex":"c1","uid":"u1","etag":"e2","vcard":"","name":"Ann","emails":[],"phones":[],"groups":[],"meta":{},"created":"2026-11-01T10:00:00Z","updated":"2026-11-02T10:00:00Z","photo":$photo}}"""

    @Before
    fun setUp() {
        store = Factory.build(dir.newFolder("db"), Vault(Provider(), "photos_${UUID.randomUUID()}_"))
        manager = Manager(store)
        files = dir.newFolder("photos")
        photos = Photos(Files(files))
        book = Book(store, manager, photos)
        server = MockWebServer()
        server.dispatcher = object : MockDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path} ${request.getHeader("Content-Type") ?: "-"}"
                bodies += request.body.readByteArray()
                if (status != 200) return MockResponse().setResponseCode(status)
                return when (request.method) {
                    "PUT" -> MockResponse().setBody(card("""{"hex":"0123456789abcdef","updated":"2026-11-02T10:00:00Z"}"""))
                    "DELETE" -> MockResponse().setBody(card("null"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val api = Contacts(OkHttpClient(), server.url("/v1").toString())
        worker = Worker(manager, ContactDispatcher(api, book::created, null, photos, book::photographed))
        store.boxFor(Contact::class.java).put(Contact(hex = "c1", name = "Ann", ctag = "e1"))
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
        Sealing.install(null)
    }

    private fun stored(hex: String): Contact = store.boxFor(Contact::class.java).query(Contact_.hex.equal(hex)).build().use { it.findFirst()!! }

    @Test
    fun aPhotoIsLocalAtOnceThenUploadedAndCached() = runBlocking {
        val local = book.photo("c1", jpeg)

        assertTrue(local.photoHex.startsWith(Photos.LOCAL))
        val spool = local.photoHex.removePrefix(Photos.LOCAL)
        assertArrayEquals(jpeg, photos.spooled(spool))
        // Sealed on disk: the file is not the plain bytes.
        assertFalse(File(files, spool).readBytes().contentEquals(jpeg))
        assertEquals(1, manager.count.value)

        assertEquals(1, worker.drain())

        assertEquals("PUT /v1/user/contacts/c1/photo image/jpeg", requests.single())
        assertArrayEquals(jpeg, bodies.single())
        assertEquals("0123456789abcdef", stored("c1").photoHex)
        assertArrayEquals(jpeg, photos.cached("0123456789abcdef"))
        assertNull(photos.spooled(spool))
        assertEquals(1, photos.done.value)
        assertEquals(0, manager.count.value)
    }

    @Test
    fun aRefusedPhotoFailsAndKeepsItsSpool() = runBlocking {
        val spool = book.photo("c1", jpeg).photoHex.removePrefix(Photos.LOCAL)
        status = 413

        assertEquals(1, worker.drain())

        assertEquals(0, manager.count.value)
        assertEquals(Book.PHOTO, manager.failed.value.single().action)
        assertArrayEquals(jpeg, photos.spooled(spool))
        assertEquals(0, photos.done.value)
    }

    @Test
    fun removingThePhotoIsQueuedAndSent() = runBlocking {
        val contact = stored("c1")
        contact.photoHex = "0123456789abcdef"
        store.boxFor(Contact::class.java).put(contact)

        assertEquals("", book.unphoto("c1").photoHex)
        assertEquals(1, worker.drain())

        assertEquals("DELETE /v1/user/contacts/c1/photo -", requests.single())
        assertEquals("", stored("c1").photoHex)
        assertEquals(1, photos.done.value)
    }

    @Test
    fun anInlinePhotoIsRemovedFromTheVcardToo() = runBlocking {
        book.update("c1", pro.aduki.core.models.Card(name = "Ann", photo = "data:image/jpeg;base64,/9j/4AAQ"))
        assertTrue(stored("c1").vcard.contains("PHOTO"))

        book.unphoto("c1")

        assertEquals("", stored("c1").photo)
        assertFalse(stored("c1").vcard.contains("PHOTO"))
        assertEquals(listOf(Book.UPDATE, Book.UPDATE, Book.UNPHOTO), manager.pending().map { it.action })
    }

    @Test
    fun aLaterPhotoIsNotOverwrittenByAnEarlierReply() = runBlocking {
        book.photo("c1", jpeg)
        val second = book.photo("c1", jpeg + byteArrayOf(9)).photoHex

        // The first reply arrives while the second photo is still queued.
        assertTrue(worker.process(manager.pending().first()))
        assertEquals(second, stored("c1").photoHex)

        assertEquals(1, worker.drain())
        assertEquals("0123456789abcdef", stored("c1").photoHex)
    }

    @Test
    fun onlyImagesAreTaken() {
        assertThrows(IllegalArgumentException::class.java) { book.photo("c1", "not an image".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { book.photo("c1", jpeg + ByteArray(Photos.LIMIT)) }
        assertEquals(0, manager.count.value)
        assertTrue(files.list()!!.isEmpty())
        assertEquals("image/png", Photos.type(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("image/webp", Photos.type("RIFF0000WEBPVP8 ".toByteArray()))
    }

    @Test
    fun theSweepDropsSpoolsOfGoneEntries() {
        val kept = book.photo("c1", jpeg).photoHex.removePrefix(Photos.LOCAL)
        val orphan = photos.spool(jpeg)

        photos.sweep(setOf(kept))

        assertArrayEquals(jpeg, photos.spooled(kept))
        assertNull(photos.spooled(orphan))
    }
}
