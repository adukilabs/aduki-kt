package pro.aduki.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.models.Card
import pro.aduki.core.vcard.Vcard
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** `client.contacts` writes, groups, vCard import/export and duplicates over a local store (#36-#39), and import without one. */
class BookTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()
    private val serial = AtomicInteger()
    private var client: Aduki? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                val path = request.path.orEmpty()
                return when {
                    path == "/v1/user/contacts/import" -> MockResponse().setBody(
                        """{"success":true,"data":{"created":1,"updated":0,"skipped":0,"errors":[{"index":1,"error":"no FN"}]}}"""
                    )
                    path == "/v1/user/contacts/groups" && request.method == "POST" ->
                        MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"name":"g","count":0}}""")
                    path == "/v1/user/contacts/groups" -> MockResponse().setBody("""{"success":true,"data":{"items":[]}}""")
                    path.startsWith("/v1/user/contacts/group/") && request.method == "PATCH" ->
                        MockResponse().setBody("""{"success":true,"data":{"group":"g","changed":1}}""")
                    path.startsWith("/v1/user/contacts/group/") && request.method == "DELETE" ->
                        MockResponse().setBody("""{"success":true,"data":{"changed":1}}""")
                    path.endsWith("/merge") -> MockResponse().setBody(
                        """{"success":true,"data":{"hex":"${path.split("/")[4]}","uid":"","etag":"m","vcard":"","name":"x","emails":[],"phones":[],"groups":[],"meta":{},"created":"2026-10-24T10:00:00Z","updated":"2026-10-24T10:00:00Z"}}"""
                    )
                    request.method == "POST" -> MockResponse().setResponseCode(201).setBody(
                        """{"success":true,"data":{"hex":"srv${serial.incrementAndGet()}","uid":"","etag":"e","vcard":"","name":"x","emails":[],"phones":[],"groups":[],"meta":{},"created":"2026-10-24T10:00:00Z","updated":"2026-10-24T10:00:00Z"}}"""
                    )
                    request.method == "GET" -> MockResponse().setBody("""{"success":true,"data":{"items":[],"total":0,"page":1,"pages":0}}""")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        client?.close()
        server.shutdown()
    }

    private fun build(): Aduki = Aduki.builder()
        .key("opaque")
        .endpoint(server.url("/v1").toString())
        .store(dir.newFolder("db"))
        .build()
        .also { client = it }

    @Test
    fun writesWithoutAStoreThrow() = runBlocking<Unit> {
        val bare = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).build()
        for (write in listOf<suspend () -> Unit>(
            { bare.contacts.create(Card(name = "A")) },
            { bare.contacts.update("c1", Card(name = "A")) },
            { bare.contacts.delete("c1") },
            { bare.contacts.merge("c1", listOf("c2")) },
            { bare.contacts.groups.add("g", listOf("c1")) },
            { bare.contacts.groups.create("g") }
        )) {
            val e = runCatching { write() }.exceptionOrNull()
            assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
            assertEquals("no local store", e?.message)
        }
    }

    @Test
    fun importWithoutAStoreGoesToTheServer() = runBlocking<Unit> {
        val bare = Aduki.builder().key("opaque").endpoint(server.url("/v1").toString()).build()

        val imported = bare.contacts.import("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:A\r\nEND:VCARD\r\n".byteInputStream())

        assertEquals(Imported(created = 1, errors = listOf("card 2: no FN")), imported)
        assertEquals(listOf("POST /v1/user/contacts/import"), paths)
    }

    @Test
    fun createUpdateDeleteReachTheServerThroughTheOutbox() = runBlocking<Unit> {
        val aduki = build()

        val made = aduki.contacts.create(Card(name = "Ann Lee", emails = listOf("ann@x.example")))
        assertTrue(made.hex.startsWith("contact_"))
        assertEquals("Ann Lee", aduki.contacts.get("srv1")?.name)

        val card = aduki.contacts.card("srv1")!!
        aduki.contacts.update("srv1", card.copy(company = "Acme"))
        assertEquals("Acme", aduki.contacts.get("srv1")?.company)

        aduki.contacts.delete("srv1")
        assertNull(aduki.contacts.get("srv1"))
        assertEquals(0, aduki.sync.outbox.value)
        assertEquals(
            listOf(
                "POST /v1/user/contacts",
                "PATCH /v1/user/contacts/srv1/vcard",
                "PATCH /v1/user/contacts/srv1/groups",
                "DELETE /v1/user/contacts/srv1"
            ),
            paths
        )
    }

    @Test
    fun groupsAreTagsKeptOnMembers() = runBlocking<Unit> {
        val aduki = build()
        val a = aduki.contacts.create(Card(name = "Ann")).let { aduki.contacts.get("srv1")!! }
        val b = aduki.contacts.create(Card(name = "Bob")).let { aduki.contacts.get("srv2")!! }

        aduki.contacts.groups.create("empty")
        assertEquals(listOf("empty"), aduki.contacts.groups.list())
        withTimeout(5_000) { aduki.contacts.groups.observe()!!.first { it == listOf("empty") } }
        assertTrue(paths.contains("POST /v1/user/contacts/groups"))
        aduki.contacts.groups.delete("empty")
        assertEquals(emptyList<String>(), aduki.contacts.groups.list())

        aduki.contacts.groups.create("friends", listOf(a.hex, b.hex))
        assertEquals(listOf("friends"), aduki.contacts.groups.list())
        val members = withTimeout(5_000) { aduki.contacts.observe("friends")!!.first { it.size == 2 } }
        assertEquals(listOf("Ann", "Bob"), members.map { it.name })

        aduki.contacts.groups.rename("friends", "close")
        assertEquals(listOf("close"), aduki.contacts.get(a.hex)?.groups)
        aduki.contacts.groups.remove("close", listOf(b.hex))
        assertEquals(emptyList<String>(), aduki.contacts.get(b.hex)?.groups)
        withTimeout(5_000) { aduki.contacts.groups.observe()!!.first { it == listOf("close") } }
        aduki.contacts.groups.delete("close")
        assertEquals(emptyList<String>(), aduki.contacts.groups.list())
        assertEquals(0, aduki.sync.outbox.value)
        assertTrue(paths.contains("PATCH /v1/user/contacts/srv1/groups"))
    }

    @Test
    fun importCountsCreatedUpdatedSkippedAndErrors() = runBlocking<Unit> {
        val aduki = build()
        val file = listOf(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:u-ann\r\nFN:Ann Lee\r\nEMAIL;TYPE=WORK:ann@x.example\r\nEMAIL:ann2@x.example\r\nTEL;TYPE=CELL:+1 555 0100\r\nPHOTO;ENCODING=b;TYPE=PNG:iVBORw0KGgo=\r\nCATEGORIES:friends\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Bob\r\nEMAIL:bob@x.example\r\nEND:VCARD\r\n",
            "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:Old\r\nEND:VCARD\r\n"
        ).joinToString("")

        val first = aduki.contacts.import(file.byteInputStream())
        assertEquals(Imported(created = 2, errors = listOf("card 3: unsupported vCard version '2.1' (3.0 and 4.0 are read)")), first)

        // The same file again changes nothing; a card with more values updates.
        assertEquals(2, aduki.contacts.import(file.byteInputStream()).skipped)
        val edited = file.replace("EMAIL:bob@x.example", "EMAIL:bob@x.example\r\nTEL:+1 555 0199")
        assertEquals(Imported(updated = 1, skipped = 1, errors = first.errors), aduki.contacts.import(edited.byteInputStream()))

        val ann = aduki.contacts.observe()!!.value.ifEmpty { withTimeout(5_000) { aduki.contacts.observe()!!.first { it.size == 2 } } }
            .first { it.name == "Ann Lee" }
        assertEquals(listOf("ann@x.example", "ann2@x.example"), ann.emails)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", ann.photo)
        assertEquals(listOf("friends"), ann.groups)

        val v3 = aduki.contacts.export(listOf(ann.hex), Vcard.V3)
        assertTrue(v3, "EMAIL;TYPE=WORK:ann@x.example" in v3.split("\r\n"))
        assertTrue(v3, "PHOTO;ENCODING=b;TYPE=PNG:iVBORw0KGgo=" in v3.split("\r\n"))
        assertTrue(v3.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        val all = aduki.contacts.export()
        assertEquals(2, Vcard.split(all).size)
        assertTrue(Vcard.parse(Vcard.split(all).first()).same(aduki.contacts.card(ann.hex)!!))
    }

    @Test
    fun duplicatesAreFoundAndMerged() = runBlocking<Unit> {
        val aduki = build()
        aduki.contacts.create(Card(name = "Ann Lee", emails = listOf("ann@x.example"), groups = listOf("friends")))
        aduki.contacts.create(Card(name = "A. Lee", emails = listOf("ANN@x.example "), phones = listOf("+44 7700 900123"), groups = listOf("work")))
        aduki.contacts.create(Card(name = "Annie", phones = listOf("07700 900123")))
        aduki.contacts.create(Card(name = "bob  smith"))
        aduki.contacts.create(Card(name = "Bob Smith"))
        aduki.contacts.create(Card(name = "Carol"))

        val found = aduki.contacts.duplicates()

        assertEquals(2, found.size)
        val (anns, bobs) = found
        assertEquals("email", anns.match)
        assertEquals(setOf("Ann Lee", "A. Lee", "Annie"), anns.contacts.map { it.name }.toSet())
        assertEquals("name", bobs.match)

        val keep = anns.contacts.first { it.name == "Ann Lee" }
        val merged = aduki.contacts.merge(keep.hex, anns.contacts.map { it.hex })
        assertEquals(listOf("ann@x.example"), merged.emails)
        assertEquals(listOf("+44 7700 900123"), merged.phones)
        assertEquals(listOf("friends", "work"), merged.groups)
        assertEquals(1, aduki.contacts.duplicates().size)
        assertEquals(0, paths.count { it.startsWith("DELETE") })
        assertEquals(1, paths.count { it.endsWith("/merge") })
        assertEquals(0, aduki.sync.outbox.value)
    }
}
