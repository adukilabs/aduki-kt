package pro.aduki.crypto.tls

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.CertificatePinner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import pro.aduki.core.config.Pin
import javax.net.ssl.SSLPeerUnverifiedException

/** Pinning is opt-in; with pins configured the handshake is checked against a local TLS server. */
class PinningTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCert: HeldCertificate
    private lateinit var clientTls: HandshakeCertificates

    @Before
    fun setUp() {
        serverCert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(serverCert).build()
        // The client trusts the test certificate as a root so only the pin decides.
        clientTls = HandshakeCertificates.Builder().addTrustedCertificate(serverCert.certificate).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(pinner: CertificatePinner?): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .apply { if (pinner != null) certificatePinner(pinner) }
        .build()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/ping")).build()).execute().use { it.code }

    private fun hash(cert: HeldCertificate) = CertificatePinner.pin(cert.certificate)

    @Test
    fun noPinsMeansNoPinner() {
        assertNull(Pinning.pinner(emptyList()))
    }

    @Test
    fun theMatchingPinAllowsTheRequest() {
        server.enqueue(MockResponse().setBody("ok"))
        val backup = HeldCertificate.Builder().build()
        val pinner = Pinning.pinner(listOf(Pin("localhost", hash(serverCert), hash(backup))))
        assertNotNull(pinner)
        assertEquals(200, get(client(pinner)))
    }

    @Test
    fun aWrongPinAbortsTheHandshake() {
        server.enqueue(MockResponse().setBody("ok"))
        val other = HeldCertificate.Builder().build()
        val pinner = Pinning.pinner(listOf(Pin("localhost", hash(other))))
        assertThrows(SSLPeerUnverifiedException::class.java) { get(client(pinner)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPinForAnotherHostDoesNotAffectThisOne() {
        server.enqueue(MockResponse().setBody("ok"))
        val other = HeldCertificate.Builder().build()
        val pinner = Pinning.pinner(listOf(Pin("mail.example.test", hash(other))))
        assertEquals(200, get(client(pinner)))
    }

    @Test
    fun malformedPinsAreRejectedEarly() {
        assertThrows(IllegalArgumentException::class.java) { Pin("mail.example.test", "WoiWRyIOVNa9") }
        assertThrows(IllegalArgumentException::class.java) { Pin("mail.example.test", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Pin(" ", "sha256/abc") }
    }
}
