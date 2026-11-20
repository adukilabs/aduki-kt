package pro.aduki.net.http

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Software
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** The Aduki Chats HTTP client against a fake Chats server and a fake Aduki ID, with DPoP-bound `chat` tokens. */
class ChatsTest {
    private lateinit var server: MockWebServer
    private val key = Software.p256()
    private val dpop = Dpop(key)
    private val http = OkHttpClient.Builder().addInterceptor(dpop).build()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val audiences = CopyOnWriteArrayList<String>()
    private var issued = 0
    @Volatile private var answers = ArrayDeque<MockResponse>()

    private fun jwt(n: Int): String {
        val claims = """{"sub":"a1","aud":"chat","jti":"$n","cnf":{"jkt":"${key.thumbprint()}"}}"""
        return "${b64.encodeToString("""{"alg":"EdDSA"}""".toByteArray())}.${b64.encodeToString(claims.toByteArray())}.sig"
    }

    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/id/v1/sessions") || path.startsWith("/id/v1/tokens")) {
                    issued++
                    audiences += JSONObject(request.body.readUtf8()).optString("audience")
                    val code = if (path.startsWith("/id/v1/sessions")) 201 else 200
                    return MockResponse().setResponseCode(code)
                        .setBody("""{"success":true,"data":{"session":"s1","access":"${jwt(issued)}","refresh":"adkr_$issued","expires":600}}""")
                }
                seen += request
                synchronized(this@ChatsTest) { return answers.removeFirst() }
            }
        }
        server.start()
    }
    @After fun teardown() { server.shutdown() }

    private fun chats(): Chats {
        val id = Id(http, server.url("/id/v1").toString())
        id.signIn("ada@acme.example", "pw", code = "123456", audience = "chat")
        val bearer = Bearer { stale -> if (stale == null) id.token("chat") else id.rotate("chat", stale).token }
        return Chats(http, bearer, server.url("/chat").toString())
    }

    private fun answer(vararg responses: MockResponse) = synchronized(this) { answers = ArrayDeque(responses.toList()) }
    private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")
    private fun failure(code: Int, kind: String, details: String = "null") = MockResponse().setResponseCode(code)
        .setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m","details":$details}}""")

    private fun claims(proof: String) = JSONObject(String(Base64.getUrlDecoder().decode(proof.split('.')[1])))
    private fun ath(token: String) = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private val thread = "0195f000-0000-7000-8000-00000000aaaa"
    private val device = "0195f000-0000-7000-8000-00000000dddd"
    private val amina = "0195f000-0000-7000-8000-000000000a01"
    private val baraka = "0195f000-0000-7000-8000-000000000b02"

    @Test
    fun theSpaceIsReadWithADpopBoundChatTokenAndAProofWithoutTheQuery() {
        val client = chats()
        answer(ok("""{"tenant":"$thread","kind":"org","home":null,"workspace":"$thread"}"""))
        val space = client.space()
        assertEquals(thread, space.tenant)
        assertEquals("org", space.kind)
        assertNull(space.home)
        assertEquals(thread, space.workspace)

        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("/chat/_ae09/space", request.path)
        val token = request.getHeader("Authorization")!!
        assertTrue(token.startsWith("DPoP "))
        val proof = claims(request.getHeader("DPoP")!!)
        assertEquals("GET", proof.getString("htm"))
        assertEquals(server.url("/chat/_ae09/space").toString(), proof.getString("htu"))
        assertEquals(ath(token.removePrefix("DPoP ")), proof.getString("ath"))
        assertEquals(listOf("chat"), audiences)
    }

    @Test
    fun devicesAreListedRenamedAndRevoked() {
        val client = chats()
        answer(
            ok("""{"devices":[{"id":"$device","name":"Pixel","platform":null,"created":10,"seen":20,"group":"g1","current":true}]}"""),
            ok("""{"device":"$device","name":"Work phone"}"""),
            ok("""{"device":"$device","revoked":true}""")
        )
        val one = client.devices().single()
        assertEquals(device, one.id)
        assertEquals("Pixel", one.name)
        assertNull(one.platform)
        assertEquals(20L, one.seen)
        assertTrue(one.current)
        assertEquals("Work phone", client.renameDevice(device, "  Work phone "))
        assertTrue(client.revokeDevice(device))

        assertEquals(listOf("/chat/_ae09/devices", "/chat/_ae09/devices/name", "/chat/_ae09/devices/revoke"), seen.map { it.path })
        val renamed = JSONObject(seen[1].body.readUtf8())
        assertEquals("Work phone", renamed.getString("name"))
        assertEquals(device, renamed.getString("device"))
        assertEquals(device, JSONObject(seen[2].body.readUtf8()).getString("device"))
    }

    @Test
    fun contactsArePagedUntilTheLastPage() {
        val client = chats()
        answer(
            ok("""{"contacts":[{"account":"$amina","handle":"amina","name":"Amina","consentedAt":5,"source":"act","presence":"online","dm":"$thread"}],"next":"$amina"}"""),
            ok("""{"contacts":[{"account":"$baraka","handle":null,"name":null,"consentedAt":6,"source":"contacts","dm":null}],"next":null}""")
        )
        val all = client.allContacts()
        assertEquals(listOf(amina, baraka), all.map { it.account })
        assertEquals("online", all[0].presence)
        assertEquals(thread, all[0].dm)
        assertNull(all[1].name)
        assertNull(all[1].presence)
        assertEquals("contacts", all[1].source)
        assertEquals("/chat/_ae09/contacts?limit=200", seen[0].path)
        assertEquals("/chat/_ae09/contacts?after=$amina&limit=200", seen[1].path)
        // the proof signs the URL without its query
        assertEquals(server.url("/chat/_ae09/contacts").toString(), claims(seen[1].getHeader("DPoP")!!).getString("htu"))
    }

    @Test
    fun aGroupsSealedInfoIsReadAndWrittenAsBytes() {
        val client = chats()
        val sealed = byteArrayOf(1, 2, 3, -4)
        answer(
            ok("""{"thread":"$thread","version":2,"blob":"${b64.encodeToString(sealed)}","size":4,"by":"$amina","at":99}"""),
            ok("""{"thread":"$thread","version":3,"size":4,"at":100}"""),
            ok("""{"thread":"$thread","version":0,"blob":null,"size":0,"by":null,"at":null}""")
        )
        val info = client.threadInfo(thread)
        assertEquals(2L, info.version)
        assertArrayEquals(sealed, info.blob)
        assertEquals(amina, info.by)

        val put = client.putThreadInfo(thread, 3, sealed)
        assertEquals(3L, put.version)
        assertEquals(100L, put.at)
        assertEquals("PUT", seen[1].method)
        assertEquals("/chat/_ae09/threads/meta?thread=$thread&version=3", seen[1].path)
        assertEquals("application/octet-stream", seen[1].getHeader("Content-Type"))
        assertArrayEquals(sealed, seen[1].body.readByteArray())

        val none = client.threadInfo(thread)
        assertEquals(0L, none.version)
        assertNull(none.blob)
    }

    @Test
    fun aThreadsCallIsStartedOnceAndItsKeyGroupAsked() {
        val client = chats()
        val call = "04e61991c100cebb"
        val idempotency = "0195f000-0000-7000-8000-0000000000ff"
        answer(
            ok("""{"available":true,"items":[]}"""),
            ok("""{"call":"$call","kind":"thread","state":"open","join":{"signal":"wss://x/v1/signal","room":"$call"},"thread":"$thread","ringing":{"people":1,"devices":2},"started":true}""", 201),
            ok("""{"call":"$call","kind":"thread","state":"open","join":{"signal":"wss://x/v1/signal","room":"$call"},"thread":"$thread","started":false}"""),
            ok("""{"thread":"$thread","call":"$call","founder":true,"founded":false,"members":["$amina","$baraka"]}""")
        )
        val state = client.calls(thread)
        assertTrue(state.available)
        assertTrue(state.items.isEmpty())

        val started = client.startCall(thread, idempotency)
        assertEquals(call, started.call)
        assertEquals("wss://x/v1/signal", started.signal)
        assertEquals(call, started.room)
        assertTrue(started.started)
        assertEquals(2, started.devices)
        assertEquals(idempotency, seen[1].getHeader("Idempotency-Key"))
        assertEquals(thread, JSONObject(seen[1].body.readUtf8()).getString("thread"))

        val again = client.startCall(thread)
        assertFalse(again.started)
        assertEquals(0, again.people)
        assertNull(seen[2].getHeader("Idempotency-Key"))

        val group = client.callGroup(call)
        assertTrue(group.founder)
        assertFalse(group.founded)
        assertEquals(listOf(amina, baraka), group.members)
        assertEquals("/chat/_ae09/calls/groups", seen[3].path)
    }

    @Test
    fun mediaIsUploadedSignedAndFetchedByItsTokenAlone() {
        val client = chats()
        val bytes = byteArrayOf(9, 8, 7)
        answer(
            ok("""{"id":"blob1","token":"t0k/en+","ttl":3600}"""),
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(bytes)),
            ok("""{"id":"blob1","deleted":true}"""),
            ok("""{"bytes":3,"files":1,"limit":null}"""),
            ok("""{"media":[{"id":"blob1","bytes":3,"at":7}],"next":null}"""),
            ok("""{"id":"blob1","deleted":true}""")
        )
        val up = client.upload(bytes)
        assertEquals("blob1", up.id)
        assertEquals(3600L, up.ttl)
        assertEquals("PUT", seen[0].method)
        assertTrue(seen[0].getHeader("Authorization")!!.startsWith("DPoP "))
        assertArrayEquals(bytes, seen[0].body.readByteArray())

        assertArrayEquals(bytes, client.download(up.token))
        assertEquals("/chat/_ae09/media?token=t0k%2Fen%2B", seen[1].path)
        assertNull("the media token is the whole credential", seen[1].getHeader("Authorization"))

        assertEquals("blob1", client.delete(up.token))
        assertEquals("DELETE", seen[2].method)
        assertNull(seen[2].getHeader("Authorization"))

        val usage = client.usage()
        assertEquals(3L, usage.bytes)
        assertNull(usage.limit)
        assertEquals(7L, client.ownMedia().media.single().at)
        client.deleteOwnMedia("blob1")
        assertEquals("/chat/_ae09/media/mine?id=blob1", seen[5].path)
        assertTrue(seen[5].getHeader("Authorization")!!.startsWith("DPoP "))
    }

    @Test
    fun theHomeSpaceIsReadWithoutSigningIn() {
        val client = chats()
        answer(ok("""{"server":"chat.test","tenant":"$thread","keys":{}}"""), ok("""{"server":"chat.test","tenant":null}"""))
        assertEquals(thread, client.home())
        assertNull(client.home())
        assertEquals("/chat/.well-known/ae09/server", seen[0].path)
        assertNull(seen[0].getHeader("Authorization"))
    }

    @Test
    fun a401RenewsTheTokenOnceThenFailsAsUnauthorized() {
        val client = chats()
        answer(failure(401, "auth"), ok("""{"devices":[]}"""))
        assertTrue(client.devices().isEmpty())
        assertEquals(2, seen.size)
        assertFalse("the second try carries a renewed token", seen[0].getHeader("Authorization") == seen[1].getHeader("Authorization"))

        answer(failure(401, "auth"), failure(401, "auth"))
        try {
            client.devices()
            fail("a second 401 is final")
        } catch (e: AdukiException.Unauthorized) {
            assertEquals("auth", e.kind)
        }
        assertEquals(4, seen.size)
    }

    @Test
    fun aRefusalCarriesTheServersKindAndDetails() {
        val client = chats()
        answer(failure(403, "forbidden", """{"reason":"binding"}"""), failure(409, "conflict", """{"version":4}"""))
        try {
            client.contacts()
            fail("an account without a binding is refused")
        } catch (e: AdukiException.Api) {
            assertEquals(403, e.status)
            assertEquals("forbidden", e.kind)
            assertEquals("binding", JSONObject(e.details!!).getString("reason"))
        }
        try {
            client.putThreadInfo(thread, 2, byteArrayOf(1))
            fail("a stale version is a conflict")
        } catch (e: AdukiException.Api) {
            assertEquals(409, e.status)
            assertEquals(4, JSONObject(e.details!!).getInt("version"))
        }
    }

    @Test
    fun badArgumentsAreRefusedBeforeAnyRequest() {
        val client = chats()
        val bad = listOf<() -> Any?>(
            { client.renameDevice("not-a-uuid", "x") },
            { client.renameDevice(device, "   ") },
            { client.renameDevice(device, "x".repeat(65)) },
            { client.contacts(limit = 0) },
            { client.contacts(limit = 201) },
            { client.contacts(after = "abc") },
            { client.threadInfo("abc") },
            { client.putThreadInfo(thread, 0, byteArrayOf(1)) },
            { client.putThreadInfo(thread, 1, ByteArray(0)) },
            { client.putThreadInfo(thread, 1, ByteArray(256 * 1024 + 1)) },
            { client.startCall(thread, "not-a-uuid") },
            { client.callGroup("XYZ") },
            { client.upload(ByteArray(0)) },
            { client.download(" ") },
            { client.deleteOwnMedia("a/b") }
        )
        for ((i, call) in bad.withIndex()) {
            try {
                call()
                fail("argument $i was accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue("nothing was sent", seen.isEmpty())
    }
}
