package pro.aduki.android

import android.security.keystore.KeyInfo
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.aduki.android.keystore.AndroidKey
import pro.aduki.crypto.cipher.Envelope
import pro.aduki.crypto.cipher.SealException
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.net.http.Dpop
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.security.AlgorithmParameters
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Behaviour of the REAL AndroidKeyStore provider on the device or emulator the tests run on.
 * On an emulator without secure hardware the keys are software-backed inside keystore2: these tests prove the
 * API contract (non-extractable, Keystore-generated IV, signatures verify) and report where the key lives, but
 * they cannot prove hardware backing or StrongBox: see ANDROID-TESTS.md.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val b64 = Base64.getUrlDecoder()
    private val created = mutableListOf<String>()
    private val aliases = mutableListOf<String>()

    @After fun cleanup() {
        aliases.forEach { runCatching { AndroidKey.delete(it) } }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        created.forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
    }

    private fun alias(prefix: String) = "${prefix}_${System.nanoTime()}".also { aliases += it; created += it }

    private fun publicOf(key: AndroidKey) = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(
            ECPoint(BigInteger(1, b64.decode(key.jwk.getValue("x"))), BigInteger(1, b64.decode(key.jwk.getValue("y")))),
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        )
    )

    /** Verifies a JWS ES256 (raw r||s) signature: Android has no P1363 verifier, so it is turned into DER first. */
    private fun verifies(key: AndroidKey, input: ByteArray, sig: ByteArray): Boolean {
        require(sig.size == 64)
        fun integer(raw: ByteArray): ByteArray {
            var v: ByteArray = raw.dropWhile { it == 0.toByte() }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = integer(sig.copyOfRange(0, 32)) + integer(sig.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, body.size.toByte()) + body
        return Signature.getInstance("SHA256withECDSA").run { initVerify(publicOf(key)); update(input); verify(der) }
    }

    // ---- K5: DPoP key ------------------------------------------------------

    @Test fun dpopKeyIsP256NonExtractableAndItsSignaturesVerify() {
        val key = AndroidKey.open(alias("dpop"))
        assertEquals("ES256", key.alg)
        assertEquals("EC", key.jwk["kty"]); assertEquals("P-256", key.jwk["crv"])
        assertFalse("private key bytes must not be readable", key.extractable())
        assertFalse(key.jwk.containsKey("d"))
        val input = "header.payload".toByteArray()
        val sig = key.sign(input)
        assertEquals(64, sig.size)
        assertTrue("signature verifies with the public JWK", verifies(key, input, sig))
        assertFalse("and not for other input", verifies(key, "other".toByteArray(), sig))
        assertEquals(43, key.thumbprint().length)
    }

    @Test fun dpopKeyReportsWhereItLivesAndNeverClaimsMoreThanThePlatform() {
        val key = AndroidKey.open(alias("dpop"))
        val p = key.protection()
        Log.i("AdukiKeystore", "DPoP key hardware=${p.hardware} level=${p.level} strongBox=${p.strongBox} sdk=${android.os.Build.VERSION.SDK_INT}")
        assertTrue(p.level in listOf("software", "trusted_environment", "strongbox"))
        assertEquals(p.level != "software", p.hardware || p.level == "trusted_environment")
        val requested = AndroidKey.open(alias("dpopsb"), strongBox = true) // falls back when there is no StrongBox
        Log.i("AdukiKeystore", "StrongBox requested: got level=${requested.protection().level}")
        assertTrue(requested.sign("x".toByteArray()).size == 64)
    }

    @Test fun dpopProofSignedByTheKeystoreVerifies() {
        val key = AndroidKey.open(alias("dpop"))
        val proof = Dpop(key).proof("GET", "https://space.aduki.pro/v1/tasks?x=1", access = "adka_token")
        val (head, body, sig) = proof.split(".")
        assertEquals("ES256", org.json.JSONObject(String(b64.decode(head))).getString("alg"))
        assertEquals("https://space.aduki.pro/v1/tasks", org.json.JSONObject(String(b64.decode(body))).getString("htu"))
        assertTrue(verifies(key, "$head.$body".toByteArray(), b64.decode(sig)))
    }

    @Test fun reopeningTheAliasGivesTheSameKeyAndDeleteRemovesIt() {
        val a = alias("dpop")
        val first = AndroidKey.open(a)
        val second = AndroidKey.open(a)
        assertEquals(first.thumbprint(), second.thumbprint())
        assertTrue(verifies(first, "m".toByteArray(), second.sign("m".toByteArray())))
        AndroidKey.delete(a)
        assertFalse(AndroidKey.exists(a))
        assertNotEquals(first.thumbprint(), AndroidKey.open(a).thumbprint())
    }

    // ---- B14: Envelope on the real provider ---------------------------------

    @Test fun masterKeysLiveInTheRealKeystoreAndAreNotExtractable() {
        val a = alias("b14")
        val key = Provider().create(a)
        assertTrue("not the silent software fallback", key.javaClass.name.contains("AndroidKeyStore"))
        assertNull("SecretKey.getEncoded must be null", key.encoded)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(ks.containsAlias(a))
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        Log.i("AdukiKeystore", "AES key insideSecureHardware=${info.isInsideSecureHardware}")
    }

    @Test fun keystoreRefusesACallerSuppliedIvWhichIsWhyEnvelopeUsesTheGeneratedOne() {
        val key = Provider().create(alias("b14"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
            fail("a Keystore key with randomized encryption must refuse a caller IV")
        } catch (_: java.security.InvalidAlgorithmParameterException) {
        } catch (_: java.security.InvalidKeyException) {
        }
    }

    @Test fun envelopeUsesTheKeystoreGeneratedIvNeverRepeatsAndRoundTrips() {
        val key: SecretKey = Provider().create(alias("b14"))
        val seen = HashSet<String>()
        repeat(300) {
            val sealed = Envelope.encrypt("secret-$it".toByteArray(), key)
            assertTrue(seen.add(Base64.getEncoder().encodeToString(sealed.copyOfRange(0, 12))))
            assertEquals("secret-$it", String(Envelope.decrypt(sealed, key)))
        }
        val sealed = Envelope.encrypt("x".toByteArray(), key)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        try { Envelope.decrypt(sealed, key); fail("tampered ciphertext opened") } catch (_: java.security.GeneralSecurityException) {}
    }

    // ---- sealed columns on the real provider ---------------------------------

    @Test fun vaultSealsAndOpensTextBytesAndRotatesOnTheRealKeystore() {
        val prefix = "inst_vault_${System.nanoTime()}_"
        val vault = Vault(Provider(), prefix)
        (1..2).forEach { created += "$prefix$it" }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("key 1 is in the Keystore", ks.containsAlias("${prefix}1"))
        val sealed = vault.sealText("ada@example.com")
        assertTrue(sealed.startsWith("aduki:1:") && !sealed.contains("ada@example.com"))
        assertEquals("ada@example.com", vault.openText(sealed))
        assertNotEquals("randomized: same text, different sealed value", sealed, vault.sealText("ada@example.com"))
        assertEquals("legacy plaintext passes through", "plain", vault.openText("plain"))
        val blob = vault.sealBytes(byteArrayOf(1, 2, 3))
        assertTrue(Vault.isSealed(blob)); assertEquals(listOf<Byte>(1, 2, 3), vault.openBytes(blob).toList())
        assertEquals(2, vault.rotate())
        assertTrue(ks.containsAlias("${prefix}2"))
        assertEquals("old values still open after rotation", "ada@example.com", vault.openText(sealed))
        assertEquals(2, java.nio.ByteBuffer.wrap(vault.seal(byteArrayOf(9)), 3, 4).int)
        val tampered = vault.seal(byteArrayOf(5, 6)).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { vault.open(tampered); fail() } catch (_: SealException) {}
    }

    @Test fun blindIndexSeedsAreSealedInTheirFileAndStable() {
        val prefix = "inst_idx_${System.nanoTime()}_"
        created += "${prefix}1"
        val vault = Vault(Provider(), prefix)
        val file = File(context.cacheDir, "idx_${System.nanoTime()}")
        vault.attachIndex(file)
        val a = vault.blind("email", "ada@example.com")
        assertFalse("seed file is sealed", String(file.readBytes(), Charsets.ISO_8859_1).matches(Regex("(?s)\\d+:[0-9a-f]{64}.*")))
        val again = Vault(Provider(), prefix).also { it.attachIndex(file) }
        assertEquals(a, again.blind("email", "ada@example.com"))
        assertNotEquals(a, vault.blind("phone", "ada@example.com"))
        file.delete()
    }

    // ---- survival across a process restart (two phases, see ANDROID-TESTS.md) --------

    private val state get() = File(context.filesDir, "survival.state")

    @Test fun survivalPhase1CreateKeysAndData() {
        val dpop = AndroidKey.open("aduki_survival_dpop")
        val prefix = "inst_survival_"
        val vault = Vault(Provider(), prefix)
        val sealed = vault.sealText("survives-restart")
        state.writeText(listOf(dpop.thumbprint(), sealed).joinToString("\n"))
        Log.i("AdukiKeystore", "phase1 written")
    }

    @Test fun survivalPhase2AfterForceStopKeysStillWork() {
        assumeTrue("run phase 1, force-stop the app, then run phase 2", state.exists())
        val (thumb, sealed) = state.readText().split("\n")
        val dpop = AndroidKey.open("aduki_survival_dpop") // must open the SAME key, not create a new one
        assertEquals("same key after restart", thumb, dpop.thumbprint())
        assertTrue(verifies(dpop, "m".toByteArray(), dpop.sign("m".toByteArray())))
        assertEquals("survives-restart", Vault(Provider(), "inst_survival_").openText(sealed))
        AndroidKey.delete("aduki_survival_dpop")
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias("inst_survival_1")) ks.deleteEntry("inst_survival_1")
        state.delete()
    }
}
