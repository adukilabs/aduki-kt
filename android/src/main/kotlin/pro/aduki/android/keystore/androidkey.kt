package pro.aduki.android.keystore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import pro.aduki.crypto.dpop.Der
import pro.aduki.crypto.dpop.Device
import pro.aduki.crypto.dpop.Key
import pro.aduki.crypto.dpop.Software
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * AndroidKey is the DPoP device key (ADK-AUTH-001 section 9, K5) held in the
 * Android Keystore: a non-extractable P-256 key that signs `ES256` proofs and
 * never leaves the keystore ([PrivateKey.getEncoded] is null; there is no way
 * to read `d`). Use it as the [Key] of `net.http.Dpop`.
 *
 * Where the key lives (secure hardware, StrongBox or software-only, as on an
 * emulator) is reported by [protection]; it is the platform's answer, so show
 * it instead of assuming hardware.
 */
class AndroidKey private constructor(
    /** The Keystore alias. */
    val alias: String,
    private val private: PrivateKey,
    publicKey: ECPublicKey
) : Key {

    override val alg: String = "ES256"

    override val jwk: Map<String, String> = Software.jwkOf(publicKey)

    override fun sign(input: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA") // DER on every API level
        signature.initSign(private)
        signature.update(input)
        return Der.toRaw(signature.sign(), 32)
    }

    /** What the platform says about this key (never key material). */
    class Protection(
        /** `KeyInfo.isInsideSecureHardware`: true for the TEE or StrongBox, false for a software keystore. */
        val hardware: Boolean,
        /** `KeyInfo.getSecurityLevel` (API 31+): `software`, `trusted_environment`, `strongbox` or `unknown`. */
        val level: String,
        /** True when the key was requested on StrongBox and got it. */
        val strongBox: Boolean
    )

    /** Asks the Keystore where the key is held. */
    fun protection(): Protection {
        val info = KeyFactory.getInstance(private.algorithm, PROVIDER).getKeySpec(private, KeyInfo::class.java)
        val level = if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "trusted_environment"
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                else -> "unknown"
            }
        } else if (info.isInsideSecureHardware) "trusted_environment" else "software"
        return Protection(info.isInsideSecureHardware, level, level == "strongbox")
    }

    /** True when the private key can be read as bytes (always false for a Keystore key). */
    fun extractable(): Boolean = private.encoded != null

    /**
     * The public key as the device-credential API wants it for `ES256`: the uncompressed point
     * `0x04 || x || y` (65 bytes; `Device.public`, unit-tested on the JVM).
     */
    fun devicePublic(): ByteArray = Device.public(this)

    override fun toString() = "AndroidKey($alias)" // never prints key material

    companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** The alias of the device DPoP key unless the app picks another. */
        const val DEFAULT = "aduki_dpop"

        /**
         * The alias of the device credential key (ES256 center unlock, `Accounts.registerDevice`). UNVERIFIED on
         * a device. Kept apart from [DEFAULT] so revoking the credential does not change the DPoP thumbprint.
         */
        const val DEVICE = "aduki_device"

        /** Whether [alias] exists in the Keystore. */
        fun exists(alias: String = DEFAULT): Boolean = store().containsAlias(alias)

        /** Removes [alias]; its thumbprint is gone with it and sessions bound to it cannot refresh. */
        fun delete(alias: String = DEFAULT) {
            val keyStore = store()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }

        /**
         * Opens the key at [alias], creating it when missing.
         *
         * @param strongBox ask for StrongBox (API 28+); when the device has none the key is made in the TEE or
         * software keystore instead (check [protection]).
         * @param authentication require the user (biometric or device credential) for every signature; needs
         * API 30+ for the per-use form used here and is off by default (the server binds to the key, not to the user).
         */
        fun open(alias: String = DEFAULT, strongBox: Boolean = false, authentication: Boolean = false): AndroidKey {
            val keyStore = store()
            if (!keyStore.containsAlias(alias)) generate(alias, strongBox, authentication)
            val private = keyStore.getKey(alias, null) as? PrivateKey
                ?: throw IllegalStateException("Keystore entry $alias is not a private key")
            val public = keyStore.getCertificate(alias).publicKey as ECPublicKey
            return AndroidKey(alias, private, public)
        }

        private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

        private fun generate(alias: String, strongBox: Boolean, authentication: Boolean) {
            if (strongBox && Build.VERSION.SDK_INT >= 28) {
                try {
                    pair(alias, true, authentication)
                    return
                } catch (_: StrongBoxUnavailableException) {
                    // fall through to the default keystore
                }
            }
            pair(alias, false, authentication)
        }

        private fun pair(alias: String, strongBox: Boolean, authentication: Boolean) {
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
            if (strongBox && Build.VERSION.SDK_INT >= 28) builder.setIsStrongBoxBacked(true)
            if (authentication) {
                builder.setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setUserAuthenticationParameters(
                        0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                }
            }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(builder.build()) }.generateKeyPair()
        }
    }
}
