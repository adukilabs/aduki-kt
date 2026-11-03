package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Identity
import pro.aduki.core.models.Tokens
import java.util.Collections

/** The profile facade: pooled storage, rename through Aduki ID, photos and lookups. */
class ProfileTest {

    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    // Path to answer; a missing path is a 404.
    private val routes = HashMap<String, MockResponse>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val key = "${request.method} ${request.path}"
                synchronized(routes) { routes[key] }?.let { return it }
                if (request.path == "/id/tokens") {
                    val audience = JSONObject(request.body.readUtf8()).optString("audience")
                    return MockResponse().setBody(
                        """{"success":true,"data":{"access":"eyJ.$audience","refresh":"rt_${requests.size}","expires":600}}"""
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun route(method: String, path: String, response: MockResponse) = synchronized(routes) {
        routes["$method $path"] = response
    }

    private fun client(signedIn: Boolean = true): Aduki {
        val client = Aduki.builder()
            .token("eyJ.mail")
            .endpoint(server.url("/v1").toString())
            .identity(server.url("/id").toString())
            .drive(server.url("/store/v1").toString())
            .build()
        if (signedIn) client.session.update(Tokens("eyJ.mail", "rt_1", "600", "00000000000000ab"))
        return client
    }

    @Test
    fun profileTakesThePooledStoreQuota() = runBlocking {
        route("GET", "/v1/user/profile", MockResponse().setBody(PROFILE))
        route(
            "GET", "/store/v1/quota",
            MockResponse().setBody("""{"success":true,"data":{"owner":"00000000000000a1","cap":10737418240,"per":null,"used":4096,"mail":1234,"chat":0}}""")
        )

        val profile = client().profile()!!

        assertEquals(4096L, profile.storage.used)
        assertEquals(10_737_418_240L, profile.storage.limit)
        assertTrue(profile.storage.pooled)
        val quota = requests.first { it.path == "/store/v1/quota" }
        assertEquals("Bearer eyJ.store", quota.getHeader("Authorization"))
    }

    @Test
    fun profileFallsBackToMailStorageWithoutADrive() = runBlocking {
        route("GET", "/v1/user/profile", MockResponse().setBody(PROFILE))
        // No /store/v1/quota route: 404, no drive yet.

        val profile = client().profile()!!

        assertEquals(1234L, profile.storage.used)
        assertEquals(5_368_709_120L, profile.storage.limit)
        assertFalse(profile.storage.pooled)
    }

    @Test
    fun profileOfAnApiKeyClientUsesMailStorage() = runBlocking {
        route("GET", "/v1/user/profile", MockResponse().setBody(PROFILE))

        val profile = client(signedIn = false).profile()!!

        assertFalse(profile.storage.pooled)
        assertTrue("no Aduki ID call without a session", requests.none { it.path!!.startsWith("/id") || it.path!!.startsWith("/store") })
    }

    @Test
    fun profileNeverThrows() = runBlocking {
        route("GET", "/v1/user/profile", MockResponse().setResponseCode(500))
        assertNull(client().profile())
    }

    @Test
    fun renameGoesToAdukiIdAndForgetsTheIdentity() = runBlocking {
        route("PATCH", "/id/accounts/00000000000000a1", MockResponse().setBody("""{"success":true,"data":{"hex":"00000000000000a1","name":"Ada King"}}"""))
        val client = client()
        client.session.update(Identity(user = "00000000000000a1", name = "Ada"))

        assertTrue(client.rename("Ada King"))

        val patch = requests.first { it.method == "PATCH" }
        assertEquals("/id/accounts/00000000000000a1", patch.path)
        assertEquals("Bearer eyJ.id", patch.getHeader("Authorization"))
        assertEquals("Ada King", JSONObject(patch.body.readUtf8()).getString("name"))
        assertNull("the cached identity is cleared", client.session.identity.value)
        assertNotNull("the rotated refresh token is kept", client.session.tokens.value?.refresh)
    }

    @Test
    fun lockedRenameThrowsLocked() = runBlocking {
        route("PATCH", "/id/accounts/00000000000000a1", MockResponse().setResponseCode(403).setBody("""{"success":false,"error":{"kind":"locked","message":"locked"}}"""))
        val client = client()
        client.session.update(Identity(user = "00000000000000a1"))

        val e = assertThrows(AdukiException.Api::class.java) { runBlocking { client.rename("Ada") } }
        assertTrue(e.locked)
        assertNotNull("a refused rename keeps the identity", client.session.identity.value)
    }

    @Test
    fun renameWithoutASessionIsFalse() = runBlocking {
        assertFalse(client(signedIn = false).rename("Ada"))
        assertEquals(0, requests.size)
    }

    @Test
    fun uploadAvatarReturnsTheProfileAndForgetsTheIdentity() = runBlocking {
        route("PUT", "/v1/user/avatar", MockResponse().setBody(PROFILE))
        val client = client()
        client.session.update(Identity(user = "00000000000000a1"))

        val profile = client.uploadAvatar(byteArrayOf(1, 2, 3), "image/png")

        assertEquals("00000000000000f1", profile!!.avatar!!.hex)
        assertNull(client.session.identity.value)
    }

    @Test
    fun tooLargeUploadThrowsApi() = runBlocking {
        route("PUT", "/v1/user/avatar", MockResponse().setResponseCode(413).setBody("""{"success":false,"error":{"kind":"input.large","message":"too large"}}"""))

        val e = assertThrows(AdukiException.Api::class.java) { runBlocking { client().uploadAvatar(ByteArray(8), "image/jpeg") } }
        assertEquals(413, e.status)
    }

    @Test
    fun removeAvatarIsTrue() = runBlocking {
        route("DELETE", "/v1/user/avatar", MockResponse().setBody(PROFILE))
        assertTrue(client().removeAvatar())
    }

    @Test
    fun avatarIsNullWhenUnchangedOrFailing() = runBlocking {
        route("GET", "/v1/user/avatar", MockResponse().setResponseCode(304))
        route("GET", "/v1/user/avatar/00000000000000c3", MockResponse().setResponseCode(500))
        val client = client()

        assertNull(client.avatar(etag = "00000000000000f1"))
        assertNull(client.avatar("00000000000000c3"))
    }

    @Test
    fun avatarsAskInBatchesOfFifty() = runBlocking {
        route(
            "POST", "/v1/user/lookup/avatars",
            MockResponse().setBody("""{"success":true,"data":[{"email":"bo@example.test","hex":"00000000000000c3","avatar":{"hex":"00000000000000f2","updated":"2026-11-03T10:00:00Z"}}]}""")
        )
        val emails = (1..120).map { "p$it@example.test" } + "bo@example.test"

        val found = client().avatars(emails)

        assertEquals("00000000000000c3", found.getValue("bo@example.test").account)
        val lookups = requests.filter { it.path == "/v1/user/lookup/avatars" }
        assertEquals(3, lookups.size)
        assertEquals(50, JSONObject(lookups[0].body.readUtf8()).getJSONArray("emails").length())
    }

    private companion object {
        const val PROFILE = """{"success":true,"data":{
            "hex":"00000000000000a1","name":"Ada Lovelace","email":"ada@example.test",
            "avatar":{"hex":"00000000000000f1","updated":"2026-11-03T10:00:00Z"},
            "kind":"personal","tenant":null,"role":{"label":"Owner","owner":true},
            "plan":{"code":"free","state":"active"},"storage":{"used":1234,"limit":5368709120},"editable":true}}"""
    }
}
