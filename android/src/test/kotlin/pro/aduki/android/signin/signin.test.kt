package pro.aduki.android.signin

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pro.aduki.android.softVault
import pro.aduki.core.errors.AdukiException
import pro.aduki.net.http.Oidc
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInTest {
    private lateinit var server: MockWebServer
    private lateinit var issuer: String
    private lateinit var app: Application
    private lateinit var pending: PendingStore
    private val redirect = "pro.aduki.app:/callback"
    private val vault = softVault()
    private var now = 1_800_000_000_000L
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().also { it.start() }
        issuer = server.url("/").toString().trimEnd('/')
        pending = PendingStore(app, vault)
    }

    @After fun teardown() { server.shutdown() }

    private fun flow() = SignIn(Oidc(OkHttpClient(), issuer, "client1", redirect, clock = { now }), redirect, pending, clock = { now })

    private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun discovery() = MockResponse().setBody(
        """{"issuer":"$issuer","authorization_endpoint":"$issuer/oauth/authorize","token_endpoint":"$issuer/oauth/token",
        "userinfo_endpoint":"$issuer/oauth/userinfo","jwks_uri":"$issuer/.well-known/jwks.json",
        "code_challenge_methods_supported":["S256"]}"""
    )

    private fun tokens(nonce: String): MockResponse {
        val access = "adka_1"
        val atHash = enc(MessageDigest.getInstance("SHA-256").digest(access.toByteArray()).copyOf(16))
        val claims = JSONObject().put("iss", issuer).put("sub", "acc1").put("aud", "client1").put("azp", "client1")
            .put("iat", now / 1000 - 5).put("exp", now / 1000 + 600).put("nonce", nonce).put("at_hash", atHash)
        val head = enc("""{"alg":"EdDSA","kid":"k1"}""".toByteArray())
        val body = enc(claims.toString().toByteArray())
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update("$head.$body".toByteArray()); sign() }
        return MockResponse().setBody(
            JSONObject().put("access_token", access).put("token_type", "Bearer").put("expires_in", 600).put("scope", "openid")
                .put("refresh_token", "adkr_1").put("id_token", "$head.$body.${enc(sig)}").toString()
        )
    }

    private fun jwks() = MockResponse().setBody(
        """{"keys":[{"kty":"OKP","crv":"Ed25519","kid":"k1","alg":"EdDSA","use":"sig","x":"${enc(pair.public.encoded.takeLast(32).toByteArray())}"}]}"""
    )

    private fun callback(begin: Oidc.Begin, code: String = "code1", state: String = begin.state) =
        Uri.parse("$redirect?code=$code&state=$state&iss=$issuer")

    @Test fun startLaunchesACustomTabAtTheAuthorizeUrlAndRemembersTheRequest() {
        server.enqueue(discovery())
        val begin = flow().start(app)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(begin.url, started.data.toString())
        assertTrue("custom tab extra", started.hasExtra("android.support.customtabs.extra.SESSION"))
        assertTrue("from a non-activity context", started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val q = started.data!!
        assertEquals("code", q.getQueryParameter("response_type"))
        assertEquals("S256", q.getQueryParameter("code_challenge_method"))
        assertEquals(redirect, q.getQueryParameter("redirect_uri"))
        assertEquals(1, pending.size())
    }

    @Test fun theVerifierIsSealedAtRestAndSurvivesAProcessRestart() {
        server.enqueue(discovery())
        val begin = flow().start(app)
        val raw = app.getSharedPreferences("pro.aduki.signin", 0).getString(begin.state, "")!!
        assertFalse(raw.contains(begin.verifier))
        // a new store over the same file and vault models the restarted process
        val taken = PendingStore(app, vault).take(begin.state, now)
        assertEquals(begin.verifier, taken!!.verifier)
    }

    @Test fun redirectMatchingIsExactOnSchemeAuthorityAndPath() {
        val m = { u: String -> SignIn.matches(redirect, Uri.parse(u)) }
        assertTrue(m("pro.aduki.app:/callback?code=1"))
        assertTrue(m("PRO.ADUKI.APP:/callback?code=1"))
        assertFalse("other path", m("pro.aduki.app:/callback/evil?code=1"))
        assertFalse("other scheme", m("evil.app:/callback?code=1"))
        assertFalse("https look-alike", m("https://id.aduki.pro/callback?code=1"))
        assertFalse("with authority", m("pro.aduki.app://host/callback?code=1"))
        assertFalse("fragment response", m("pro.aduki.app:/callback#code=1"))
        val https = "https://app.aduki.pro/signin/callback"
        assertTrue(SignIn.matches(https, Uri.parse("https://APP.aduki.pro/signin/callback?code=1")))
        assertFalse(SignIn.matches(https, Uri.parse("https://app.aduki.pro.evil.com/signin/callback")))
    }

    @Test fun completeExchangesTheCodeOnce() {
        server.enqueue(discovery())
        val flow = flow()
        val begin = flow.start(app)
        server.takeRequest()
        server.enqueue(tokens(begin.nonce)); server.enqueue(jwks())
        val grant = flow.complete(callback(begin))
        assertEquals("adka_1", grant.access)
        assertEquals("adkr_1", grant.refresh)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("code_verifier=${begin.verifier}") && form.contains("code=code1"))
        assertEquals("spent", 0, pending.size())
        try { flow.complete(callback(begin)); fail("replay accepted") } catch (_: AdukiException.Auth) {}
    }

    @Test fun strayRedirectsAreRefusedWithoutCancellingTheRealSignIn() {
        server.enqueue(discovery())
        val flow = flow()
        val begin = flow.start(app)
        for (bad in listOf(
            Uri.parse("evil.app:/callback?code=1&state=${begin.state}"),
            Uri.parse("$redirect?code=1"),
            Uri.parse("$redirect?code=1&state=unknown")
        )) {
            try { flow.complete(bad); fail("accepted $bad") } catch (_: AdukiException.Auth) {}
        }
        assertEquals("the real request is still waiting", 1, pending.size())
    }

    @Test fun anExpiredSignInIsRefused() {
        server.enqueue(discovery())
        val flow = flow()
        val begin = flow.start(app)
        now += PendingStore.LIFETIME + 1
        try { flow.complete(callback(begin)); fail() } catch (_: AdukiException.Auth) {}
        assertEquals(0, pending.size())
    }

    @Test fun aDeniedRedirectSurfacesAsUnauthorizedAndSpendsTheRequest() {
        server.enqueue(discovery())
        val flow = flow()
        val begin = flow.start(app)
        try {
            flow.complete(Uri.parse("$redirect?error=access_denied&state=${begin.state}"))
            fail()
        } catch (e: AdukiException.Unauthorized) {
            assertTrue(e.message!!.startsWith("access_denied"))
        }
        assertEquals(0, pending.size())
    }

    @Test fun aStateMismatchedRedirectDoesNotExchangeAnything() {
        server.enqueue(discovery())
        val flow = flow()
        val begin = flow.start(app)
        val before = server.requestCount
        try { flow.complete(callback(begin, state = "other")); fail() } catch (_: AdukiException.Auth) {}
        assertEquals(before, server.requestCount)
    }

    @Test fun theRedirectActivityHandlesOnlyOurUriAndFinishes() {
        var result: Result<Oidc.Grant>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        server.enqueue(discovery())
        val flow = flow()
        val begin = flow.start(app)
        server.takeRequest()
        server.enqueue(tokens(begin.nonce)); server.enqueue(jwks())
        SignInConfig.flow = flow
        SignInConfig.done = { result = it; latch.countDown() }
        val intent = Intent(Intent.ACTION_VIEW, callback(begin)).setClass(app, RedirectActivity::class.java)
        val controller = Robolectric.buildActivity(RedirectActivity::class.java, intent).create()
        assertTrue(controller.get().isFinishing)
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("adka_1", result!!.getOrThrow().access)
        SignInConfig.flow = null; SignInConfig.done = null
    }

    @Test fun theManifestRoutesOurSchemeToTheRedirectActivity() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("$redirect?code=1")).addCategory(Intent.CATEGORY_BROWSABLE)
        val found = app.packageManager.queryIntentActivities(view, 0)
        assertEquals(listOf(RedirectActivity::class.java.name), found.map { it.activityInfo.name })
        assertNotNull(app.packageManager.resolveActivity(view, 0))
        assertTrue(found.single().activityInfo.exported)
        val other = Intent(Intent.ACTION_VIEW, Uri.parse("evil.app:/callback?code=1")).addCategory(Intent.CATEGORY_BROWSABLE)
        assertNull(app.packageManager.resolveActivity(other, 0))
    }
}
