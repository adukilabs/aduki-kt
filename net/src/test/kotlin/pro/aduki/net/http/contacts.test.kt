package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.GroupRow
import pro.aduki.core.models.ImportReport

/** Contact writes against the shapes of the server's handlers (`contacts/{add,edit,remove,groups,merge,import}.rs`). */
class ContactsTest {

    private lateinit var server: MockWebServer
    private lateinit var api: Contacts

    private val vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ann Lee\r\nEMAIL;TYPE=work:ann@x.example\r\nEND:VCARD\r\n"

    // A card as `POST /user/contacts` and the list return it (captured live).
    private val card = """{"hex":"8d012b837f9fbbaf","uid":"u-1","href":"/carddav/a/b/default/u-1.vcf","etag":"7e29",
        "vcard":"BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ann Lee\r\nEND:VCARD\r\n","name":"Ann Lee","emails":["ann@x.example"],
        "phones":[],"groups":["friends"],"meta":{},"version":"4.0","size":64,
        "created":"2026-10-24T15:40:42.574184Z","updated":"2026-10-24T15:42:29.310916Z"}"""

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = Contacts(OkHttpClient(), server.url("/v1").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun createPostsTheCardWithItsVersionAndAnIdempotencyKey() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":$card}"""))

        val row = api.create(vcard, listOf("friends"), key = "k1")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/user/contacts", req.path)
        assertEquals("k1", req.getHeader("Idempotency-Key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(vcard, body.getString("vcard"))
        assertEquals("Ann Lee", body.getString("name"))
        assertEquals("friends", body.getJSONArray("groups").getString(0))
        assertTrue(body.isNull("meta"))
        assertEquals("8d012b837f9fbbaf", row.hex)
        assertEquals("u-1", row.uid)
        assertTrue(row.vcard.startsWith("BEGIN:VCARD"))
        assertTrue(row.updated > row.created)
    }

    @Test
    fun replaceGroupsAndDeleteUseTheirRoutes() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }

        api.replace("c1", vcard, key = "k2")
        api.groups("c1", emptyList(), key = "k2")
        api.delete("c1")

        val replace = server.takeRequest()
        assertEquals("PATCH /v1/user/contacts/c1/vcard", "${replace.method} ${replace.path}")
        val body = JSONObject(replace.body.readUtf8())
        assertEquals("Ann Lee", body.getString("name"))
        assertTrue(body.getString("vcard").contains("VERSION:4.0"))
        val groups = server.takeRequest()
        assertEquals("PATCH /v1/user/contacts/c1/groups", "${groups.method} ${groups.path}")
        assertEquals(0, JSONObject(groups.body.readUtf8()).getJSONArray("groups").length())
        val delete = server.takeRequest()
        assertEquals("DELETE /v1/user/contacts/c1", "${delete.method} ${delete.path}")
    }

    @Test
    fun failuresCarryTheStatus() {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"success":false,"error":{"status":404,"kind":"entity.missing","message":"not found"}}""")
        )
        try {
            api.delete("gone")
            fail("expected a failure")
        } catch (e: AdukiException.Network) {
            assertEquals(404, e.code)
            assertTrue(e.message!!.contains("entity.missing"))
        }
    }

    @Test
    fun listReadsPageModeAndTheVcard() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"items":[$card],"total":1,"page":2,"pages":3}}"""))

        val listing = api.list(limit = 50, page = 2)

        assertEquals("/v1/user/contacts?limit=50&page=2", server.takeRequest().path)
        assertEquals(2, listing.page)
        assertEquals(3, listing.pages)
        assertEquals(listOf("friends"), listing.items.single().groups)
        assertTrue(listing.items.single().vcard.contains("FN:Ann Lee"))
    }

    @Test
    fun groupsAreListedCreatedRenamedAndDissolved() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"items":[{"name":"empty","count":0},{"name":"friends","count":2}]}}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"name":"new","count":0}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"group":"close","changed":2}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"changed":0}}"""))

        assertEquals(listOf(GroupRow("empty", 0), GroupRow("friends", 2)), api.groups())
        assertEquals(GroupRow("new", 0), api.createGroup("new", key = "k3"))
        assertEquals(2, api.rename("friends", "close", key = "k4"))
        assertEquals(0, api.dissolve("empty"))

        assertEquals("GET /v1/user/contacts/groups", server.takeRequest().let { "${it.method} ${it.path}" })
        val create = server.takeRequest()
        assertEquals("POST /v1/user/contacts/groups", "${create.method} ${create.path}")
        assertEquals("k3", create.getHeader("Idempotency-Key"))
        assertEquals("new", JSONObject(create.body.readUtf8()).getString("name"))
        val rename = server.takeRequest()
        assertEquals("PATCH /v1/user/contacts/group/friends", "${rename.method} ${rename.path}")
        assertEquals("close", JSONObject(rename.body.readUtf8()).getString("name"))
        assertEquals("DELETE /v1/user/contacts/group/empty", server.takeRequest().let { "${it.method} ${it.path}" })
    }

    @Test
    fun aGroupReplyWithoutItsCountIsRefused() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"ok":true}}"""))
        assertThrows(AdukiException.Protocol::class.java) { api.dissolve("x") }
    }

    @Test
    fun mergePostsTheOthersAndTheVcard() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":$card}"""))

        val row = api.merge("8d012b837f9fbbaf", listOf("00000000000000bb"), vcard, key = "k5")

        val req = server.takeRequest()
        assertEquals("POST /v1/user/contacts/8d012b837f9fbbaf/merge", "${req.method} ${req.path}")
        assertEquals("k5", req.getHeader("Idempotency-Key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("00000000000000bb", body.getJSONArray("others").getString(0))
        assertEquals(vcard, body.getString("vcard"))
        assertEquals("8d012b837f9fbbaf", row.hex)
        assertThrows(IllegalArgumentException::class.java) { api.merge("a", emptyList()) }
    }

    @Test
    fun importSendsTheFileAsVcardAndReadsTheReport() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"created":1,"updated":0,"skipped":1,"errors":[{"index":2,"error":"no FN"}]}}"""))

        val report = api.import(vcard + vcard + "BEGIN:VCARD\r\nEND:VCARD\r\n")

        val req = server.takeRequest()
        assertEquals("POST /v1/user/contacts/import", "${req.method} ${req.path}")
        assertTrue(req.getHeader("Content-Type")!!.startsWith("text/vcard"))
        assertTrue(req.body.readUtf8().startsWith("BEGIN:VCARD"))
        assertEquals(ImportReport(created = 1, skipped = 1, errors = listOf(2 to "no FN")), report)
    }

    @Test
    fun listFollowsTheAfterCursor() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"items":[$card],"total":2,"next":"c2"}}"""))

        val listing = api.list(after = "c1", limit = 1)

        assertEquals("/v1/user/contacts?limit=1&after=c1", server.takeRequest().path)
        assertEquals("c2", listing.next)
    }
}
