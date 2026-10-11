package pro.aduki.crypto.dpop

import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Device is how a [Key] is registered as a device credential at Aduki ID
 * (ADK-AUTH-001 section 9): the algorithm name and the public key encoding the
 * server expects for it. Both come from the key, never from a flag, so an
 * ES256 key cannot be sent as Ed25519 or the other way round.
 *
 * - `EdDSA`: the 32-byte Ed25519 public key.
 * - `ES256`: the uncompressed P-256 point `0x04 || x || y` (65 bytes), x and y
 *   left-padded to 32 bytes.
 */
object Device {
    private val UNB64 = Base64.getUrlDecoder()

    /** The registration algorithm of [key]: `ES256` or `EdDSA`. */
    fun alg(key: Key): String = when (key.alg) {
        "ES256", "EdDSA" -> key.alg
        else -> throw IllegalArgumentException("Unsupported device key algorithm: ${key.alg}")
    }

    /** The public key bytes to register for [key], from its JWK (so a Keystore key needs nothing extra). */
    fun public(key: Key): ByteArray = when (alg(key)) {
        "ES256" -> {
            require(key.jwk["kty"] == "EC" && key.jwk["crv"] == "P-256") { "An ES256 key must be an EC P-256 key" }
            val x = coordinate(key.jwk, "x")
            val y = coordinate(key.jwk, "y")
            byteArrayOf(0x04) + x + y
        }
        else -> {
            require(key.jwk["kty"] == "OKP" && key.jwk["crv"] == "Ed25519") { "An EdDSA key must be an Ed25519 key" }
            val x = decode(key.jwk["x"])
            require(x.size == 32) { "An Ed25519 public key is 32 bytes" }
            x
        }
    }

    /** The uncompressed point of a JDK P-256 public key: `0x04 || x || y`, coordinates left-padded to 32 bytes. */
    fun point(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)

    /** The signature [Key.sign] must return for [alg]: 64 bytes (`r || s` for ES256, Ed25519 as is). */
    const val SIGNATURE = 64

    private fun coordinate(jwk: Map<String, String>, name: String): ByteArray {
        val raw = decode(jwk[name])
        require(raw.size == 32) { "A P-256 coordinate is 32 bytes" }
        return raw
    }

    private fun decode(text: String?): ByteArray {
        requireNotNull(text) { "Missing JWK member" }
        return UNB64.decode(text)
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= 32) { "Coordinate does not fit 32 bytes" }
        return ByteArray(32 - raw.size) + raw
    }
}
