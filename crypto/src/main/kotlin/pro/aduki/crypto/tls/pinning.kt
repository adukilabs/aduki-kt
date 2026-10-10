package pro.aduki.crypto.tls

import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion

/**
 * TLS 1.3 transport security configuration and SPKI certificate pinning.
 */
object Pinning {

    const val REST_HOST = "mail.aduki.pro"
    const val GRPC_HOST = "grpc.aduki.pro"

    // Primary SPKI SHA-256 pin
    const val PIN_PRIMARY = "sha256/WoiWRyIOVNa9ihaBciRSC7XHjliYS9VwUGOIud4PB18="

    // Backup pin for key rotation
    const val PIN_BACKUP = "sha256/2k2i402K90558661mndnnd901002872365287293848="

    /**
     * Builds CertificatePinner enforced on Aduki production domains.
     */
    fun pinner(): CertificatePinner {
        return CertificatePinner.Builder()
            .add(REST_HOST, PIN_PRIMARY, PIN_BACKUP)
            .add(GRPC_HOST, PIN_PRIMARY, PIN_BACKUP)
            .build()
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

