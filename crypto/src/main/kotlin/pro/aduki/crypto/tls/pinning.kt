package pro.aduki.crypto.tls

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import pro.aduki.core.config.Pin

/**
 * TLS connection settings and opt-in SPKI certificate pinning.
 *
 * The SDK ships no pins: pinning is off unless the app passes its own
 * (`Aduki.Builder.pins(...)`). A pin that does not match what the server
 * presents breaks every request to that host, so only add pins you took from
 * the live certificate chain, plus a backup.
 */
object Pinning {

    /**
     * Builds a [CertificatePinner] from [pins], or returns null when there are
     * none (no pinning).
     */
    fun pinner(pins: List<Pin>): CertificatePinner? {
        if (pins.isEmpty()) return null
        val builder = CertificatePinner.Builder()
        pins.forEach { builder.add(it.host, *it.hashes.toTypedArray()) }
        return builder.build()
    }

    /**
     * Enforces restricted TLS 1.3 / 1.2 with modern forward-secret cipher suites.
     */
    fun specs(): List<ConnectionSpec> {
        val spec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
        return listOf(spec)
    }
}
