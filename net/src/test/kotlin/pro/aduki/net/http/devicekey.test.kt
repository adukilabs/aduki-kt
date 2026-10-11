package pro.aduki.net.http

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import pro.aduki.core.errors.AdukiException
import pro.aduki.crypto.dpop.Der
import pro.aduki.crypto.dpop.Device
import pro.aduki.crypto.dpop.Key
import pro.aduki.crypto.dpop.Software
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * The ES256 device credential flow (register, challenge, sign, unlock) against a
 * fake Aduki ID that follows the server contract of Aduki ID PR 35. The fake
 * verifies with java.security only (P1363 format and, separately, DER), never
 * with the client's own code, so the client and the check are independent.
 */
class DeviceKeyTest {
    private lateinit var id: FakeId
    private lateinit var base: String
    private val http = OkHttpClient()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val json = "application/json; charset=utf-8".toMediaType()

    @Before fun setup() {
        id = FakeId().also { it.server.start() }
        base = id.server.url("/v1").toString()
    }
    @After fun teardown() { id.server.shutdown() }

    // ---- independent crypto helpers (JDK only) ----

    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** A key like AndroidKey: SHA256withECDSA gives DER, converted to r||s. */
    private class DerKey(val pair: KeyPair, private val pick: (ByteArray) -> Boolean = { true }) : Key {
        override val alg = "ES256"
        override val jwk = Software.jwkOf(pair.public as ECPublicKey)
        override fun sign(input: ByteArray): ByteArray {
            while (true) { // ECDSA is randomised: draw until the wanted S form comes out
                val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(input); sign() }
                val raw = Der.toRaw(der, 32)
                if (pick(raw)) return raw
            }
        }
    }

    private val order: BigInteger by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java).order
    }
    private fun highS(raw: ByteArray) = BigInteger(1, raw.copyOfRange(32, 64)) > order.shiftRight(1)
    private fun lowS(raw: ByteArray) = !highS(raw)

    /** The other S form of the same signature: s' = n - s. */
    private fun flipS(raw: ByteArray): ByteArray {
        val s = order.subtract(BigInteger(1, raw.copyOfRange(32, 64)))
        return raw.copyOfRange(0, 32) + fixed32(s)
    }
    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    /** Hand-rolled DER encoder (not the client's): r || s to SEQUENCE { INTEGER r, INTEGER s }. */
    private fun toDer(raw: ByteArray): ByteArray {
        fun int(bytes: ByteArray): ByteArray {
            var b = bytes.dropWhile { it == 0.toByte() }.toByteArray()
            if (b.isEmpty()) b = byteArrayOf(0)
            if (b[0] < 0) b = byteArrayOf(0) + b
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    // ---- the fake id server ----

    private class Credential(val alg: String, val public: PublicKey?)

    private inner class FakeId : Dispatcher() {
        val server = MockWebServer().also { it.dispatcher = this }
        var now = 1_000_000L // seconds, moved by tests
        private val credentials = HashMap<String, Credential>()
        private val nonces = HashMap<String, Pair<String, Long>>() // nonce -> (credential, expires)
        private var counter = 0
        val unlocks = ArrayList<JSONObject>()
        val verified = ArrayList<String>() // which verifier form accepted each unlock

        private fun ok(data: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody("""{"success":true,"data":$data}""")
        private fun failure(code: Int, kind: String) =
            MockResponse().setResponseCode(code).setBody("""{"success":false,"error":{"status":$code,"kind":"$kind","message":"m"}}""")

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            val body = request.body.clone().readUtf8()
            return when {
                path == "/v1/credentials" -> register(request, body)
                path.endsWith("/challenges") -> challenge(path.split('/')[3])
                path.endsWith("/unlock") -> unlock(path.split('/')[3], body)
                else -> failure(404, "entity.missing")
            }
        }

        private fun register(request: RecordedRequest, body: String): MockResponse {
            if (request.getHeader("Authorization") != "Bearer tok") return failure(401, "auth.invalid")
            val o = JSONObject(body)
            if (o.getString("code") != "123456") return failure(401, "auth.invalid")
            // alg left out means EdDSA: a P-256 point then fails the Ed25519 length rule
            val alg = if (o.has("alg")) o.getString("alg") else "EdDSA"
            val raw = runCatching { unb64.decode(o.getString("public")) }.getOrNull() ?: return failure(422, "input.invalid")
            val credential = when (alg) {
                "ES256" -> {
                    if (raw.size != 65 || raw[0] != 0x04.toByte()) return failure(422, "input.invalid")
                    val spec = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                        .getParameterSpec(ECParameterSpec::class.java)
                    val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
                    val key = runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, spec)) }.getOrNull()
                        ?: return failure(422, "input.invalid")
                    Credential("ES256", key)
                }
                "EdDSA" -> {
                    if (raw.size != 32) return failure(422, "input.invalid")
                    val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + raw
                    val key = runCatching { KeyFactory.getInstance("Ed25519").generatePublic(java.security.spec.X509EncodedKeySpec(spki)) }.getOrNull()
                        ?: return failure(422, "input.invalid")
                    Credential("EdDSA", key)
                }
                else -> return failure(422, "input.invalid")
            }
            val hex = "cred${++counter}"
            credentials[hex] = credential
            return ok("""{"credential":"$hex","alg":"$alg","trust_days":60}""", 201)
        }

        private fun challenge(hex: String): MockResponse {
            if (hex !in credentials) return failure(404, "entity.missing")
            val nonce = b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            nonces[nonce] = hex to now + 120
            return ok("""{"nonce":"$nonce","expires":"x"}""", 201)
        }

        private fun unlock(hex: String, body: String): MockResponse {
            val credential = credentials[hex] ?: return failure(404, "entity.missing")
            val o = JSONObject(body)
            val nonce = o.optString("nonce")
            val held = nonces[nonce]
            if (held == null || held.first != hex || held.second < now) return failure(401, "auth.invalid")
            val signature = runCatching { unb64.decode(o.optString("signature")) }.getOrNull() ?: return failure(401, "auth.invalid")
            val message = unb64.decode(nonce) // the RAW bytes, not the base64 text
            val form = verify(credential, message, signature) ?: return failure(401, "auth.invalid") // a refusal does not spend the nonce
            nonces.remove(nonce)
            unlocks += o
            verified += form
            return ok("""[{"account":"a1","session":"s1","access":"adka_1","refresh":"adkr_1","expires":600}]""")
        }

        /** Verifies two independent ways for ES256: P1363 as is, and after a hand-made DER encoding. */
        private fun verify(credential: Credential, message: ByteArray, signature: ByteArray): String? {
            if (signature.size != 64) return null
            if (credential.alg == "EdDSA") {
                val ok = Signature.getInstance("Ed25519").run { initVerify(credential.public); update(message); verify(signature) }
                return if (ok) "ed25519" else null
            }
            val p1363 = Signature.getInstance("SHA256withECDSAinP1363Format").run {
                initVerify(credential.public); update(message); runCatching { verify(signature) }.getOrDefault(false)
            }
            val der = Signature.getInstance("SHA256withECDSA").run {
                initVerify(credential.public); update(message); runCatching { verify(toDer(signature)) }.getOrDefault(false)
            }
            check(p1363 == der) { "the two verifiers disagree" }
            return if (p1363) "p1363+der" else null
        }

        fun spent(nonce: String) = nonce !in nonces
    }

    // ---- raw calls that bypass the client, to replay and tamper ----

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val request = Request.Builder().url("$base/$path").post(body.toString().toRequestBody(json)).build()
        http.newCall(request).execute().use { return it.code to JSONObject(it.body!!.string()) }
    }
    private fun nonce(credential: String) = post("credentials/$credential/challenges", JSONObject()).second.getJSONObject("data").getString("nonce")
    private fun rawUnlock(credential: String, nonce: String, signature: ByteArray) =
        post("credentials/$credential/unlock", JSONObject().put("nonce", nonce).put("signature", b64.encodeToString(signature)).put("audience", "id")).first

    private fun register(key: Key, center: Boolean = false) = Center.register(http, base, "tok", "Pixel", key, "123456", center)

    // ---- tests ----

    @Test
    fun registerChallengeSignUnlockRoundTripWithAnAndroidStyleKey() {
        val key = DerKey(pair())
        val credential = register(key, center = true)
        assertEquals("cred1", credential)

        val sent = id.server.takeRequest()
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("ES256", body.getString("alg"))
        assertTrue(body.getBoolean("center"))
        val point = unb64.decode(body.getString("public"))
        assertFalse("no padding", body.getString("public").contains('='))
        assertEquals(65, point.size)
        // Encoder-independent expectation: the tail of the X.509 SubjectPublicKeyInfo is the uncompressed point.
        assertArrayEquals(key.pair.public.encoded.takeLast(65).toByteArray(), point)

        val opened = Center.unlock(http, base, credential, key, audience = "id")
        assertEquals(listOf("a1"), opened.map { it.account })
        assertEquals("adka_1", opened[0].tokens.token)
        assertEquals(listOf("p1363+der"), id.verified)
        assertEquals("id", id.unlocks.single().getString("audience"))
        assertEquals(64, unb64.decode(id.unlocks.single().getString("signature")).size)
    }

    @Test
    fun aSoftwareP256KeyAndAnEd25519KeyBothWork() {
        val p256 = Software.p256()
        val c1 = register(p256)
        assertEquals(1, Center.unlock(http, base, c1, p256).size)
        val ed = Software.ed25519()
        val c2 = register(ed)
        assertEquals(1, Center.unlock(http, base, c2, ed).size)
        assertEquals(listOf("p1363+der", "ed25519"), id.verified)
        val bodies = generateSequence { id.server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
        val registrations = bodies.filter { it.path == "/v1/credentials" }.map { JSONObject(it.body.readUtf8()) }
        assertEquals(listOf("ES256", "EdDSA"), registrations.map { it.getString("alg") })
        assertEquals(32, unb64.decode(registrations[1].getString("public")).size)
    }

    @Test
    fun derToRawAndRawToDerAgreeBothWays() {
        val pair = pair()
        val data = ByteArray(32) { it.toByte() }
        repeat(300) {
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
            val raw = Der.toRaw(der, 32)
            assertArrayEquals(der, toDer(raw)) // DER -> raw -> DER is the identity
            val p1363 = Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(pair.public); update(data); verify(raw) }
            assertTrue(p1363)
            val native = Signature.getInstance("SHA256withECDSAinP1363Format").run { initSign(pair.private); update(data); sign() }
            val back = Signature.getInstance("SHA256withECDSA").run { initVerify(pair.public); update(data); verify(toDer(native)) }
            assertTrue(back) // raw -> DER verifies as DER
        }
    }

    @Test
    fun aWrongKeyIsRefusedWithAUniform401AndNotRetried() {
        val credential = register(DerKey(pair()))
        id.server.takeRequest()
        val stranger = DerKey(pair())
        try { Center.unlock(http, base, credential, stranger); fail() } catch (_: AdukiException.Unauthorized) {}
        // exactly one challenge and one unlock: the client did not retry, nor ask for a second nonce
        assertEquals("/v1/credentials/$credential/challenges", id.server.takeRequest().path)
        assertEquals("/v1/credentials/$credential/unlock", id.server.takeRequest().path)
        assertEquals(2, id.server.requestCount - 1)
        assertTrue(id.unlocks.isEmpty())
    }

    @Test
    fun aTamperedSignatureIsRefusedAndDoesNotSpendTheNonce() {
        val key = DerKey(pair())
        val credential = register(key)
        val nonce = nonce(credential)
        val good = key.sign(unb64.decode(nonce))
        val tampered = good.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(401, rawUnlock(credential, nonce, tampered))
        assertEquals(401, rawUnlock(credential, nonce, good.copyOf(63))) // wrong length
        assertFalse("a refused attempt keeps the nonce", id.spent(nonce))
        assertEquals(200, rawUnlock(credential, nonce, good))
        assertTrue("the first valid signature spends it", id.spent(nonce))
    }

    @Test
    fun aSignatureOverTheBase64TextOfTheNonceIsRefused() {
        val key = DerKey(pair())
        val credential = register(key)
        val nonce = nonce(credential)
        assertEquals(401, rawUnlock(credential, nonce, key.sign(nonce.toByteArray())))
    }

    @Test
    fun anExpiredNonceIsRefused() {
        val key = DerKey(pair())
        val credential = register(key)
        try {
            Center.unlock(http, base, credential, object : Key by key { override fun sign(input: ByteArray): ByteArray { id.now += 121; return key.sign(input) } })
            fail()
        } catch (_: AdukiException.Unauthorized) {}
        // Within the two minutes the same flow works.
        assertEquals(1, Center.unlock(http, base, credential, key).size)
    }

    @Test
    fun aReplayedNonceIsRefused() {
        val key = DerKey(pair())
        val credential = register(key)
        val nonce = nonce(credential)
        val signature = key.sign(unb64.decode(nonce))
        assertEquals(200, rawUnlock(credential, nonce, signature))
        assertEquals(401, rawUnlock(credential, nonce, signature))
        assertEquals(401, rawUnlock(credential, nonce, flipS(signature))) // the other S form is the same message, same spent nonce
    }

    @Test
    fun aP256KeyRegisteredWithoutAlgIsA422AndTheClientNeverOmitsIt() {
        val key = DerKey(pair())
        val point = b64.encodeToString(Device.public(key))
        fun raw(withAlg: Boolean, bearer: String): Int {
            val body = JSONObject().put("device", "Pixel").put("public", point).put("code", "123456")
            if (withAlg) body.put("alg", "ES256")
            val request = Request.Builder().url("$base/credentials").header("Authorization", "Bearer $bearer")
                .post(body.toString().toRequestBody(json)).build()
            return http.newCall(request).execute().use { it.code }
        }
        assertEquals(422, raw(withAlg = false, bearer = "tok")) // the algorithm is never guessed from the key
        assertEquals(201, raw(withAlg = true, bearer = "tok"))
        assertEquals(401, raw(withAlg = true, bearer = "nope"))
        // The client always names the algorithm.
        register(key)
        val sent = generateSequence { id.server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }.last()
        assertEquals("ES256", JSONObject(sent.body.readUtf8()).getString("alg"))
    }

    @Test
    fun aServerThatDoesNotKnowEs256IsAClearErrorNot422Silence() {
        val old = MockWebServer().also { it.start() }
        try {
            old.enqueue(MockResponse().setResponseCode(422).setBody("""{"success":false,"error":{"status":422,"kind":"input.invalid","message":"public must be a base64url Ed25519 key"}}"""))
            try { Center.register(http, old.url("/v1").toString(), "tok", "Pixel", DerKey(pair()), "123456"); fail() } catch (e: AdukiException.Network) { assertEquals(422, e.code) }
            old.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"credential":"c","trust_days":60}}"""))
            try { Center.register(http, old.url("/v1").toString(), "tok", "Pixel", DerKey(pair()), "123456"); fail() } catch (_: AdukiException.Protocol) {}
        } finally { old.shutdown() }
    }

    @Test
    fun registerWithAWrongCodeIs401() {
        try { Center.register(http, base, "tok", "Pixel", DerKey(pair()), "000000"); fail() } catch (_: AdukiException.Unauthorized) {}
    }

    @Test
    fun highSAndLowSSignaturesAreBothAccepted() {
        val pair = pair()
        val low = DerKey(pair, ::lowS)
        val high = DerKey(pair, ::highS)
        val credential = register(low)
        assertEquals(1, Center.unlock(http, base, credential, low).size)
        assertEquals(1, Center.unlock(http, base, credential, high).size)
        // And one signature in both S forms, each on its own nonce.
        val n1 = nonce(credential)
        val s1 = low.sign(unb64.decode(n1))
        assertTrue(lowS(s1)); assertTrue(highS(flipS(s1)))
        assertEquals(200, rawUnlock(credential, n1, s1))
        val n2 = nonce(credential)
        val s2 = high.sign(unb64.decode(n2))
        assertEquals(200, rawUnlock(credential, n2, flipS(s2)))
        assertEquals(4, id.unlocks.size)
    }

    @Test
    fun aDerSignatureFromTheSignerIsRejectedLocallyAndNeverSent() {
        val key = DerKey(pair())
        val credential = register(key)
        id.server.takeRequest()
        val der = object : Key by key {
            override fun sign(input: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(key.pair.private); update(input); sign() }
        }
        try { Center.unlock(http, base, credential, der); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(id.unlocks.isEmpty())
        assertEquals(1, id.server.requestCount - 1) // only the challenge went out
    }
}
