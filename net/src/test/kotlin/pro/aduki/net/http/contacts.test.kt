package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException

/** Contact writes against the shapes of the server's handlers (`contacts/{add,edit,remove}.rs`). */
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
    fun createPostsTheCardWithoutItsVersionAndAnIdempotencyKey() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":$card}"""))

        val row = api.create(vcard, listOf("friends"), key = "k1")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/user/contacts", req.path)
        assertEquals("k1", req.getHeader("Idempotency-Key"))
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.getString("vcard").contains("VERSION"))
        assertTrue(body.getString("vcard").contains("EMAIL;TYPE=work:ann@x.example"))
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
        assertFalse(body.getString("vcard").contains("VERSION"))
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
}
