package pro.aduki.sync.outbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pro.aduki.store.box.Files
import java.util.UUID

/**
 * Photos keeps contact photos on the device, sealed ([Files]):
 *
 * - the cache, one file per server blob (`blob-<hex>`): a blob id names
 *   one content for good, so a cached photo never goes stale, only unused;
 * - the spool, one file per queued upload (`spool-<id>`), read by
 *   `ContactDispatcher` and dropped once the server has the photo.
 */
class Photos(private val files: Files) {
    private val sent = MutableStateFlow(0)

    /** Photo writes (set or removed) that reached the server since this was built. */
    val done: StateFlow<Int> = sent.asStateFlow()

    /** Spools [bytes] for upload; returns its name for the outbox entry. */
    fun spool(bytes: ByteArray): String {
        val name = SPOOL + UUID.randomUUID().toString().replace("-", "")
        files.write(name, bytes)
        return name
    }

    /** The spooled photo [name], or null when it is gone. */
    fun spooled(name: String): ByteArray? = if (safe(name)) files.read(name) else null

    /** Drops the spooled photo [name]. */
    fun release(name: String) {
        if (safe(name)) files.delete(name)
    }

    /** The cached photo of blob [hex], or null. */
    fun cached(hex: String): ByteArray? = if (safe(hex)) files.read(BLOB + hex) else null

    /** Caches [bytes] as blob [hex]; a name that is not a blob id is ignored. */
    fun cache(hex: String, bytes: ByteArray) {
        if (safe(hex) && hex.isNotBlank()) files.write(BLOB + hex, bytes)
    }

    /** Drops spooled photos not named in [live] (the spools of queued and failed outbox entries). */
    fun sweep(live: Set<String>) {
        for (name in files.names(SPOOL)) if (name !in live) files.delete(name)
    }

    internal fun sent() {
        sent.update { it + 1 }
    }

    private fun safe(name: String): Boolean =
        name.length <= 100 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    companion object {
        private const val SPOOL = "spool-"
        private const val BLOB = "blob-"

        /** `local:` + spool name: `Contact.photoHex` while an upload waits. */
        const val LOCAL = "local:"

        /** The largest photo the server takes, bytes. */
        const val LIMIT = 1 shl 20

        /**
         * The media type of [bytes] by its magic bytes: `image/jpeg`,
         * `image/png` or `image/webp`; null for anything else.
         */
        fun type(bytes: ByteArray): String? {
            fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
            return when {
                at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
                at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "image/png"
                at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                    at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
                else -> null
            }
        }
    }
}
