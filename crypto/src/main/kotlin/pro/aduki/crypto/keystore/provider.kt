package pro.aduki.crypto.keystore

import java.security.KeyStore
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Provider abstracts hardware-backed KeyStore access (StrongBox / TEE) with JVM fallback.
 *
 * The loaded KeyStore and every key read from it are kept for the life of
 * the Provider: loading the Android Keystore and looking a key up cost
 * milliseconds each, and sealed columns are opened per field. [remove] and
 * [forget] drop what is kept.
 */
class Provider internal constructor(
    private val type: String,
    private val loader: (String) -> KeyStore
) {
    constructor(type: String = "AndroidKeyStore") : this(type, ::load)

    companion object {
        /** Alias used for newly created master keys. */
        const val MASTER = "aduki_master"

        private val cache = ConcurrentHashMap<String, SecretKey>()

        private fun load(type: String): KeyStore = KeyStore.getInstance(type).apply { load(null) }
    }

    private val lock = Any()

    @Volatile
    private var store: KeyStore? = null

    // Keys read from or created in [store], by alias.
    private val keys = ConcurrentHashMap<String, SecretKey>()

    // The KeyStore, loaded once; throws when this platform has none.
    private fun keystore(): KeyStore = store ?: synchronized(lock) {
        store ?: loader(type).also { store = it }
    }

    /**
     * Retrieves an existing key or creates a new one.
     */
    fun get(alias: String = MASTER): SecretKey {
        keys[alias]?.let { return it }
        return try {
            val keyStore = keystore()
            synchronized(lock) {
                keys[alias] ?: (if (keyStore.containsAlias(alias)) keyStore.getKey(alias, null) as SecretKey else create(alias))
                    .also { keys[alias] = it }
            }
        } catch (_: Exception) {
            // JVM fallback for unit tests and local execution
            cache.getOrPut(alias) { createSoftware(alias) }
        }
    }

    /**
     * The key [alias] if it exists, else null; never creates one. One lookup
     * instead of [has] and [get].
     */
    fun find(alias: String): SecretKey? {
        keys[alias]?.let { return it }
        return try {
            val keyStore = keystore()
            synchronized(lock) {
                keys[alias] ?: if (keyStore.containsAlias(alias)) (keyStore.getKey(alias, null) as SecretKey).also { keys[alias] = it } else null
            }
        } catch (_: Exception) {
            cache[alias]
        }
    }

    /**
     * Drops the kept KeyStore and keys ([alias] only, when given), so the
     * next call reads the Keystore again. Keys stay in the Keystore.
     */
    fun forget(alias: String? = null) {
        synchronized(lock) {
            if (alias == null) {
                keys.clear()
                store = null
            } else {
                keys.remove(alias)
            }
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
        if (keys.containsKey(alias)) return true
        return try {
            val keyStore = keystore()
            synchronized(lock) { keyStore.containsAlias(alias) }
        } catch (_: Exception) {
            cache.containsKey(alias)
        }
    }

    /**
     * Removes a key alias from the KeyStore.
     */
    fun remove(alias: String) {
        cache.remove(alias)
        keys.remove(alias)
        try {
            val keyStore = keystore()
            synchronized(lock) {
                keys.remove(alias)
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias)
                }
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
        // Randomized encryption stays required (the default): the Keystore
        // generates each IV and Envelope stores it with the ciphertext.
        // Verified on an API 34 emulator (software-backed Keystore); hardware backing is not.
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
