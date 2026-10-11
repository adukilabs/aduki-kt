package pro.aduki.android.passkey

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasskeysTest {
    private val options = """{"challenge":"AAAA","rp":{"id":"aduki.pro","name":"Aduki"},"user":{"id":"AQ","name":"ada","displayName":"Ada"},"pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
    private val request = """{"challenge":"AAAA","rpId":"aduki.pro","userVerification":"required"}"""
    private fun begun(opts: String) = """{"ceremony":"c0ffee","expires":"2026-10-11T12:05:00Z","options":$opts}"""

    private class Fake(val created: String = "", val asserted: String = "", val error: Exception? = null) : PlatformAuthenticator {
        var seen: String? = null
        override suspend fun create(options: String): String { seen = options; error?.let { throw it }; return created }
        override suspend fun get(options: String): String { seen = options; error?.let { throw it }; return asserted }
    }

    private val registration = """{"id":"AbC-_1","type":"public-key","rawId":"AbC-_1","response":{"clientDataJSON":"e30","attestationObject":"o2M"},"clientExtensionResults":{}}"""
    private val assertion = """{"id":"AbC-_1","type":"public-key","response":{"clientDataJSON":"e30","authenticatorData":"AQ","signature":"MEU","userHandle":"AQ"}}"""

    @Test fun requestsCarryAdukisOptionsJsonUnchanged() {
        assertEquals(options, CredentialManagerAuthenticator.request(options).requestJson)
        val get = CredentialManagerAuthenticator.assertion(request)
        assertEquals(1, get.credentialOptions.size)
        assertEquals(request, (get.credentialOptions.single() as androidx.credentials.GetPublicKeyCredentialOption).requestJson)
    }

    @Test fun platformFailuresMapToThreeKinds() {
        assertTrue(CredentialManagerAuthenticator.map(CreateCredentialCancellationException()) is PasskeyFailure.Cancelled)
        assertTrue(CredentialManagerAuthenticator.map(GetCredentialCancellationException()) is PasskeyFailure.Cancelled)
        assertTrue(CredentialManagerAuthenticator.map(NoCredentialException()) is PasskeyFailure.NoPasskey)
        assertTrue(CredentialManagerAuthenticator.map(GetCredentialUnknownException()) is PasskeyFailure.Platform)
        assertTrue(CredentialManagerAuthenticator.map(CreateCredentialUnknownException()) is PasskeyFailure.Platform)
    }

    @Test fun registerRunsBeginPlatformFinish() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":${begun(options)}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"passkey":"pk1","label":"Pixel","eligible":true,"synced":true,"format":"none"}}"""))
            val fake = Fake(created = registration)
            val done = PasskeyFlow(OkHttpClient(), server.url("/v1").toString(), fake).register("bearer", "Pixel")
            assertEquals("pk1", done.passkey)
            assertEquals("AAAA", org.json.JSONObject(fake.seen!!).getString("challenge"))
            assertEquals("/v1/passkeys/registrations", server.takeRequest().path)
            val finish = server.takeRequest()
            assertEquals("/v1/passkeys/registrations/c0ffee/verifications", finish.path)
            assertTrue(finish.body.readUtf8().contains("\"attestationObject\":\"o2M\""))
        }
    }

    @Test fun stepUpAndSignInUseTheAssertion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":${begun(request)}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"stepped":1800000000}}"""))
            val flow = PasskeyFlow(OkHttpClient(), server.url("/v1").toString(), Fake(asserted = assertion))
            assertEquals(1800000000L, flow.stepUp("bearer"))
            server.enqueue(MockResponse().setBody("""{"data":${begun(request)}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"access":"a","refresh":"r","session":"s","expires":600}}"""))
            val tokens = flow.signIn()
            assertEquals("a", tokens.token)
            assertEquals("r", tokens.refresh)
        }
    }

    @Test fun aCancelledSheetSpendsNothingAndNothingIsFinished() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":${begun(options)}}"""))
            val flow = PasskeyFlow(OkHttpClient(), server.url("/v1").toString(), Fake(error = PasskeyFailure.Cancelled()))
            try { flow.register("bearer"); fail() } catch (_: PasskeyFailure.Cancelled) {}
            assertEquals("begin only, no verification call", 1, server.requestCount)
        }
    }

    @Test fun aMalformedPlatformCredentialIsRefusedBeforeTheNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":${begun(options)}}"""))
            val flow = PasskeyFlow(OkHttpClient(), server.url("/v1").toString(), Fake(created = """{"id":"x","type":"public-key","response":{}}"""))
            try { flow.register("bearer"); fail() } catch (_: IllegalArgumentException) {}
            assertEquals(1, server.requestCount)
        }
    }
}
