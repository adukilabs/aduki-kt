package pro.aduki.crypto.cipher

import pro.aduki.crypto.keystore.Provider
import java.nio.ByteBuffer
import java.util.Base64

/** A sealed value could not be opened: unknown format, unknown key or failed authentication. */
class SealException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Vault seals values with AES-256-GCM ([Envelope]) under keys held by a
 * [Provider] (the Android Keystore on a device, a process-lifetime software
 * key on a plain JVM).
 *
 * Sealed layout: `0xAD 0x4B | version (1) | key id (4, big endian) | Envelope`.
 * The key id names the Provider alias `<prefix><id>`, so keys can rotate:
 * [rotate] makes a new key current, values sealed under older keys still open
 * while their key exists, and anything sealed again uses the current one.
 *
 * Text is sealed as `aduki:1:` plus base64 of the bytes. Anything not in the
 * sealed format is treated as legacy plaintext by [openText] and [openBytes]
 * and returned unchanged.
 *
 * Android Keystore behaviour is UNVERIFIED: it has not run on a device.
 */
class Vault(
    private val provider: Provider = Provider(),
    private val prefix: String = "aduki_data_"
) {
    @Volatile
    private var current: Int

    init {
        var found = 0
        for (id in 1..MAX_KEYS) if (provider.has(alias(id))) found = id
        if (found == 0) {
            provider.get(alias(1))
            found = 1
        }
        current = found
    }

    private fun alias(id: Int) = "$prefix$id"

    // ---- Blind index ------------------------------------------------------
    // Android Keystore keys cannot be read out, so index keys are not derived
    // from them: a random seed per index key id is kept in a file, sealed under
    // the vault. Per-field keys come from the seed by HKDF-Expand (HMAC-SHA256)
    // with a field label, so the same value in two fields gives two unrelated
    // index values.
    private var indexFile: java.io.File? = null
    private val seeds = java.util.TreeMap<Int, ByteArray>()

    /** Loads (or creates) the index seeds kept in [file]; call before [blind]. */
    @Synchronized
    fun attachIndex(file: java.io.File) {
        if (indexFile == file) return
        seeds.clear()
        if (file.exists()) {
            String(open(file.readBytes()), Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach {
                val (id, hex) = it.split(":")
                seeds[id.toInt()] = hex.chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()
            }
        }
        indexFile = file
        if (seeds.isEmpty()) newSeed() else persist()
    }

    fun indexAttached(): Boolean = indexFile != null

    /** The id of the current index key. */
    @Synchronized
    fun indexId(): Int = seeds.lastKey()

    /** Adds a new index key and makes it current; callers then rebuild their indexes. */
    @Synchronized
    fun rotateIndex(): Int = newSeed()

    private fun newSeed(): Int {
        val id = (if (seeds.isEmpty()) 0 else seeds.lastKey()) + 1
        seeds[id] = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        persist()
        return id
    }

    private fun persist() {
        val text = seeds.entries.joinToString("\n") { (id, k) -> "$id:" + k.joinToString("") { "%02x".format(it) } }
        val file = indexFile ?: return
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeBytes(seal(text.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) { file.writeBytes(tmp.readBytes()); tmp.delete() }
    }

    /**
     * Deterministic, keyed index value of [value] for [field]: `<index key id>:<hex HMAC-SHA256>`.
     * Equal values give equal index values (equality leaks by design); the value
     * cannot be recovered or guessed without the index seed, which is sealed.
     * The caller normalises [value] first.
     */
    @Synchronized
    fun blind(field: String, value: String): String {
        check(indexFile != null) { "Index keys not attached" }
        val id = seeds.lastKey()
        val key = mac(seeds.getValue(id), ("aduki-index-v1/" + field).toByteArray() + byteArrayOf(1))
        return "$id:" + mac(key, value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val m = javax.crypto.Mac.getInstance("HmacSHA256")
        m.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    /** The id of the key new values are sealed under. */
    fun keyId(): Int = current

    /** Creates a new key and makes it current; returns its id. */
    @Synchronized
    fun rotate(): Int {
        val next = current + 1
        check(next <= MAX_KEYS) { "Too many keys" }
        provider.get(alias(next))
        current = next
        return next
    }

    fun seal(plain: ByteArray): ByteArray {
        val id = current
        val body = Envelope.encrypt(plain, provider.get(alias(id)))
        return ByteBuffer.allocate(HEADER + body.size)
            .put(MAGIC0).put(MAGIC1).put(VERSION).putInt(id).put(body).array()
    }

    /** Opens [sealed]; throws [SealException] for a bad format, an unknown key or a failed tag. */
    fun open(sealed: ByteArray): ByteArray {
        if (!isSealed(sealed)) throw SealException("Not a sealed value")
        if (sealed[2] != VERSION) throw SealException("Unsupported sealed version ${sealed[2]}")
        val id = ByteBuffer.wrap(sealed, 3, 4).int
        if (id !in 1..MAX_KEYS || !provider.has(alias(id))) throw SealException("Unknown key id $id")
        return try {
            Envelope.decrypt(sealed.copyOfRange(HEADER, sealed.size), provider.get(alias(id)))
        } catch (e: Exception) {
            throw SealException("Sealed value failed authentication", e)
        }
    }

    fun sealText(plain: String): String =
        if (plain.isEmpty()) plain else TEXT + Base64.getEncoder().encodeToString(seal(plain.toByteArray(Charsets.UTF_8)))

    /** Opens sealed text; text that is not sealed is legacy plaintext and comes back unchanged. */
    fun openText(stored: String): String {
        if (!stored.startsWith(TEXT)) return stored
        val bytes = try {
            Base64.getDecoder().decode(stored.substring(TEXT.length))
        } catch (e: IllegalArgumentException) {
            throw SealException("Sealed text is not valid base64", e)
        }
        return String(open(bytes), Charsets.UTF_8)
    }

    fun sealBytes(plain: ByteArray): ByteArray = if (plain.isEmpty()) plain else seal(plain)

    /** Opens sealed bytes; bytes that are not sealed are legacy plaintext and come back unchanged. */
    fun openBytes(stored: ByteArray): ByteArray = if (isSealed(stored)) open(stored) else stored

    companion object {
        private const val MAGIC0: Byte = 0xAD.toByte()
        private const val MAGIC1: Byte = 0x4B
        private const val VERSION: Byte = 1
        private const val HEADER = 7
        private const val MAX_KEYS = 255
        const val TEXT = "aduki:1:"

        fun isSealed(bytes: ByteArray): Boolean =
            bytes.size > HEADER && bytes[0] == MAGIC0 && bytes[1] == MAGIC1

        /** A Vault on the Android Keystore when running on Android, else null (nothing is sealed). */
        fun platform(): Vault? = try {
            Class.forName("android.os.Build")
            Vault(Provider())
        } catch (_: ClassNotFoundException) {
            null
        }
    }
}
