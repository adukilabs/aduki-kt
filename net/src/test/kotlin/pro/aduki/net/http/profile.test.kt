package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Profile
import pro.aduki.core.models.Tokens

class ProfilesTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun endpoint() = server.url("/v1").toString()

    @Test
    fun personalProfileIsReadFromTheEnvelope() {
        server.enqueue(MockResponse().setBody(PERSONAL))

        val profile = Profiles.get(http, endpoint())

        assertEquals("/v1/user/profile", server.takeRequest().path)
        assertEquals("00000000000000a1", profile.hex)
        assertEquals("Ada Lovelace", profile.name)
        assertEquals("ada@example.test", profile.email)
        assertEquals(Profile.Kind.PERSONAL, profile.kind)
        assertNull(profile.tenant)
        assertEquals("00000000000000f1", profile.avatar!!.hex)
        assertEquals(1_793_700_000_000L, profile.avatar!!.updated)
        assertEquals("free", profile.plan!!.code)
        assertEquals("active", profile.plan!!.state)
        assertTrue(profile.role.owner)
        assertEquals(1234L, profile.storage.used)
        assertEquals(5_368_709_120L, profile.storage.limit)
        assertFalse(profile.storage.pooled)
        assertTrue(profile.editable)
    }

    @Test
    fun memberProfileCarriesTheTenantAndTheLock() {
        server.enqueue(MockResponse().setBody(MEMBER))

        val profile = Profiles.get(http, endpoint())

        assertEquals(Profile.Kind.MEMBER, profile.kind)
        assertEquals("00000000000000b2", profile.tenant!!.hex)
        assertEquals("Example Co", profile.tenant!!.name)
        assertEquals("example", profile.tenant!!.slug)
        assertEquals("Member", profile.role.label)
        assertFalse(profile.role.owner)
        assertNull(profile.avatar)
        assertNull(profile.plan)
        assertNull("no limit is unlimited", profile.storage.limit)
        assertFalse(profile.editable)
    }

    @Test
    fun profileWithoutHexIsAProtocolError() {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"name":"x"}}"""))
        assertThrows(AdukiException.Protocol::class.java) { Profiles.get(http, endpoint()) }
    }

    @Test
    fun avatarIsDownloadedWithItsEtag() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse().setBody(Buffer().write(bytes))
                .setHeader("Content-Type", "image/webp").setHeader("ETag", "\"00000000000000f1\"")
        )

        val image = Profiles.avatar(http, endpoint())!!

        assertEquals("/v1/user/avatar", server.takeRequest().path)
        assertArrayEquals(bytes, image.bytes)
        assertEquals("00000000000000f1", image.etag)
        assertEquals("image/webp", image.type)
    }

    @Test
    fun avatarOfAnotherAccountIsConditional() {
        server.enqueue(MockResponse().setResponseCode(304))

        val image = Profiles.avatar(http, endpoint(), hex = "00000000000000c3", etag = "00000000000000f1")

        assertNull("304 is unchanged", image)
        val request = server.takeRequest()
        assertEquals("/v1/user/avatar/00000000000000c3", request.path)
        assertEquals("\"00000000000000f1\"", request.getHeader("If-None-Match"))
    }

    @Test
    fun missingAvatarIsNull() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"kind":"entity.missing"}}"""))
        assertNull(Profiles.avatar(http, endpoint()))
    }

    @Test
    fun uploadSendsTheRawBytesAndReturnsTheProfile() {
        server.enqueue(MockResponse().setBody(PERSONAL))
        val bytes = byteArrayOf(-1, -40, -1, -32)

        val profile = Profiles.upload(http, endpoint(), bytes, "image/jpeg")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/user/avatar", request.path)
        assertEquals("image/jpeg", request.getHeader("Content-Type"))
        assertArrayEquals(bytes, request.body.readByteArray())
        assertEquals("00000000000000f1", profile.avatar!!.hex)
    }

    @Test
    fun uploadRefusesOtherTypesBeforeSending() {
        val e = assertThrows(AdukiException.Api::class.java) {
            Profiles.upload(http, endpoint(), byteArrayOf(1), "image/svg+xml")
        }
        assertEquals(415, e.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun lockedUploadIsDistinguishable() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"success":false,"error":{"status":403,"kind":"locked","message":"profiles are locked"}}""")
        )
        val e = assertThrows(AdukiException.Api::class.java) {
            Profiles.upload(http, endpoint(), byteArrayOf(1), "image/png")
        }
        assertEquals(403, e.status)
        assertTrue(e.locked)
    }

    @Test
    fun lockedAsAnErrorCodeIsDistinguishableToo() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"locked"}}"""))
        val e = assertThrows(AdukiException.Api::class.java) { Profiles.remove(http, endpoint()) }
        assertTrue(e.locked)
    }

    @Test
    fun otherForbiddenIsNotLocked() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":{"kind":"auth.scope"}}"""))
        val e = assertThrows(AdukiException.Api::class.java) { Profiles.remove(http, endpoint()) }
        assertFalse(e.locked)
    }

    @Test
    fun tooLargeIs413() {
        server.enqueue(
            MockResponse().setResponseCode(413).setBody("""{"success":false,"error":{"kind":"input.large","message":"too large"}}""")
        )
        val e = assertThrows(AdukiException.Api::class.java) {
            Profiles.upload(http, endpoint(), ByteArray(16), "image/png")
        }
        assertEquals(413, e.status)
        assertEquals("input.large", e.kind)
        assertFalse(e.locked)
    }

    @Test
    fun removeDeletesAndReturnsTheProfile() {
        server.enqueue(MockResponse().setBody(MEMBER))

        val profile = Profiles.remove(http, endpoint())

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/user/avatar", request.path)
        assertNull(profile.avatar)
    }

    @Test
    fun unauthorizedIsUnauthorized() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(AdukiException.Unauthorized::class.java) { Profiles.get(http, endpoint()) }
    }

    @Test
    fun lookupPostsTheAddressesAndKeysByAddress() {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":[
                    {"email":"bo@example.test","hex":"00000000000000c3","avatar":{"hex":"00000000000000f2","updated":"2026-11-03T10:00:00Z"}},
                    {"email":"cy@example.test","hex":"00000000000000c4","avatar":null}
                ]}"""
            )
        )

        val found = Profiles.lookup(http, endpoint(), listOf("bo@example.test", "cy@example.test", "di@example.test"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/user/lookup/avatars", request.path)
        assertEquals(3, JSONObject(request.body.readUtf8()).getJSONArray("emails").length())
        assertEquals(setOf("bo@example.test"), found.keys)
        assertEquals("00000000000000f2", found.getValue("bo@example.test").hex)
        assertEquals("00000000000000c3", found.getValue("bo@example.test").account)
    }

    @Test
    fun lookupReadsANestedDataList() {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"data":[{"email":"bo@example.test","hex":"00000000000000c3","avatar":{"hex":"00000000000000f2","updated":"2026-11-03T10:00:00Z"}}]}}"""
            )
        )
        assertEquals(1, Profiles.lookup(http, endpoint(), listOf("bo@example.test")).size)
    }

    @Test
    fun lookupOfNothingSendsNothing() {
        assertTrue(Profiles.lookup(http, endpoint(), emptyList()).isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun renamePatchesTheAccount() {
        val id = Id(http, server.url("/id/v1").toString())
        id.adopt(Tokens("eyJ.id", "rt_1", "600", "00000000000000ab"), "id")
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"hex":"00000000000000a1","name":"Ada King"}}"""))

        val name = id.rename("00000000000000a1", "  Ada King ")

        assertEquals("Ada King", name)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/id/v1/accounts/00000000000000a1", request.path)
        assertEquals("Bearer eyJ.id", request.getHeader("Authorization"))
        assertEquals("Ada King", JSONObject(request.body.readUtf8()).getString("name"))
    }

    @Test
    fun renameRefusesABadNameLocally() {
        val id = Id(http, server.url("/id/v1").toString())
        assertEquals(422, assertThrows(AdukiException.Api::class.java) { id.rename("00000000000000a1", "   ") }.status)
        assertThrows(AdukiException.Api::class.java) { id.rename("00000000000000a1", "x".repeat(81)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun renameRefusalsFromAdukiIdAreApi() {
        val id = Id(http, server.url("/id/v1").toString())
        id.adopt(Tokens("eyJ.id", "rt_1", "600", "00000000000000ab"), "id")
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"success":false,"error":{"status":422,"kind":"input.invalid","message":"name"}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"status":404,"kind":"entity.missing","message":"no"}}"""))

        val invalid = assertThrows(AdukiException.Api::class.java) { id.rename("00000000000000a1", "Ada") }
        assertEquals(422, invalid.status)
        assertEquals("input.invalid", invalid.kind)
        assertFalse(invalid.locked)
        val missing = assertThrows(AdukiException.Api::class.java) { id.rename("00000000000000a1", "Ada") }
        assertEquals(404, missing.status)
        assertEquals("entity.missing", missing.kind)
    }

    @Test
    fun lockedRenameIsDistinguishable() {
        val id = Id(http, server.url("/id/v1").toString())
        id.adopt(Tokens("eyJ.id", "rt_1", "600", "00000000000000ab"), "id")
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":{"kind":"locked","message":"locked"}}"""))

        val e = assertThrows(AdukiException.Api::class.java) { id.rename("00000000000000a1", "Ada") }
        assertTrue(e.locked)
        assertEquals(403, e.status)
    }

    @Test
    fun quotaReadsThePooledLedger() {
        val id = Id(http, server.url("/id/v1").toString())
        id.adopt(Tokens("eyJ.store", "rt_1", "600", "00000000000000ab"), "store")
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":{"owner":"00000000000000a1","cap":10737418240,"per":null,"used":2048,"mail":1024,"chat":0}}"""
            )
        )

        val storage = Profiles.quota(id, server.url("/store/v1").toString())!!

        val request = server.takeRequest()
        assertEquals("/store/v1/quota", request.path)
        assertEquals("Bearer eyJ.store", request.getHeader("Authorization"))
        assertEquals(2048L, storage.used)
        assertEquals(10_737_418_240L, storage.limit)
        assertTrue(storage.pooled)
    }

    @Test
    fun quotaWithoutADriveIsNull() {
        val id = Id(http, server.url("/id/v1").toString())
        id.adopt(Tokens("eyJ.store", "rt_1", "600", "00000000000000ab"), "store")
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":{"kind":"entity.missing"}}"""))

        assertNull(Profiles.quota(id, server.url("/store/v1").toString()))
    }

    @Test
    fun whoamiReadsTheName() {
        server.enqueue(MockResponse().setBody("""{"hex":"00000000000000a1","email":"ada@example.test","name":"Ada"}"""))
        assertEquals("Ada", Whoami.resolve(http, endpoint()).name)
    }

    private companion object {
        const val PERSONAL = """{"success":true,"data":{
            "hex":"00000000000000a1","name":"Ada Lovelace","email":"ada@example.test",
            "avatar":{"hex":"00000000000000f1","updated":"2026-11-03T10:00:00Z"},
            "kind":"personal","tenant":null,"role":{"label":"Owner","owner":true},
            "plan":{"code":"free","state":"active"},"storage":{"used":1234,"limit":5368709120},"editable":true}}"""

        const val MEMBER = """{"success":true,"data":{
            "hex":"00000000000000a2","name":"Bo","email":"bo@example.test","avatar":null,
            "kind":"member","tenant":{"hex":"00000000000000b2","name":"Example Co","slug":"example"},
            "role":{"label":"Member","owner":false},"plan":null,"storage":{"used":10,"limit":null},"editable":false}}"""
    }
}
