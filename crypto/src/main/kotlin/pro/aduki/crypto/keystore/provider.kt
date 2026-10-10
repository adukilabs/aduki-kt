package pro.aduki.crypto.keystore

import java.security.KeyStore
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provider abstracts hardware-backed KeyStore access (StrongBox / TEE) with JVM fallback.
 */
class Provider(private val type: String = "AndroidKeyStore") {

    companion object {
        /** Alias used for newly created master keys. */
        const val MASTER = "aduki_master"

        private val cache = ConcurrentHashMap<String, SecretKey>()
    }

    /**
     * Retrieves an existing key or creates a new one.
     */
    fun get(alias: String = MASTER): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(type).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.getKey(alias, null) as SecretKey
            } else {
                create(alias)
            }
        } catch (_: Exception) {
            // JVM fallback for unit tests and local execution
            cache.getOrPut(alias) { createSoftware(alias) }
        }
    }

    /**
     * Generates a new AES-256 key in the KeyStore.
     */
    fun create(alias: String): SecretKey {
        return try {
            if (type == "AndroidKeyStore") {
                createAndroidKey(alias)
            } else {
                val keyGen = KeyGenerator.getInstance("AES", type)
                keyGen.init(256)
                keyGen.generateKey()
            }
        } catch (_: Exception) {
            val key = createSoftware(alias)
            cache[alias] = key
            key
        }
    }

    /**
     * Checks if a key alias exists.
     */
    fun has(alias: String): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(type).apply { load(null) }
            keyStore.containsAlias(alias)
        } catch (_: Exception) {
            cache.containsKey(alias)
        }
    }

    /**
     * Removes a key alias from the KeyStore.
     */
    fun remove(alias: String) {
        cache.remove(alias)
        try {
            val keyStore = KeyStore.getInstance(type).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (_: Exception) {
            // Ignored on software fallback
        }
    }

    private fun createAndroidKey(alias: String): SecretKey {
        val specClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val propsClass = Class.forName("android.security.keystore.KeyProperties")
        val purposeEncrypt = propsClass.getField("PURPOSE_ENCRYPT").getInt(null)
        val purposeDecrypt = propsClass.getField("PURPOSE_DECRYPT").getInt(null)
        val blockModeGcm = propsClass.getField("BLOCK_MODE_GCM").get(null) as String
        val encryptionPaddingsNone = propsClass.getField("ENCRYPTION_PADDING_NONE").get(null) as String

        val builder = specClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(alias, purposeEncrypt or purposeDecrypt)
        specClass.getMethod("setBlockModes", Array<String>::class.java)
            .invoke(builder, arrayOf(blockModeGcm))
        specClass.getMethod("setEncryptionPaddings", Array<String>::class.java)
            .invoke(builder, arrayOf(encryptionPaddingsNone))
        specClass.getMethod("setKeySize", Int::class.javaPrimitiveType)
            .invoke(builder, 256)
        val spec = specClass.getMethod("build").invoke(builder) as AlgorithmParameterSpec

        val keyGen = KeyGenerator.getInstance("AES", type)
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun createSoftware(alias: String): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }
}
