package pro.aduki.sync.engine

import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.errors.Faults
import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Body
import pro.aduki.core.models.BodyMode
import pro.aduki.store.entities.Content as ContentEntity
import pro.aduki.store.entities.Content_

/**
 * ContentTransport fetches the body of one message from the server.
 */
fun interface ContentTransport {
    suspend fun fetch(hex: String): Body

    /**
     * The body of [hex] read in [mode]. A transport without reading support
     * returns the raw parts ([fetch]) whatever the mode.
     */
    suspend fun fetch(hex: String, mode: BodyMode): Body = fetch(hex)

    /**
     * The bodies of [hexes] read in [mode], in one request where the
     * transport can; a message the server does not have is left out. By
     * default one [fetch] per message.
     */
    suspend fun fetchAll(hexes: List<String>, mode: BodyMode): List<Body> = hexes.map { fetch(it, mode) }

    /** The server sanitises bodies ([Body.sanitised]); false by default. */
    suspend fun sanitised(): Boolean = false
}

/**
 * ContentStorage caches message bodies.
 */
interface ContentStorage {
    fun get(hex: String): ContentEntity?
    fun put(content: ContentEntity)
    fun remove(hexes: List<String>)
}

/**
 * Content reads message bodies cache-first: a body is fetched once, then
 * served from the store, offline included.
 *
 * The cache holds the whole body ([BodyMode.Both]) and serves any mode from
 * it. A row cached raw (before 0.6.2, or from a server without reading
 * support) is fetched again once the server sanitises; offline, or when that
 * fetch fails, the raw row is served.
 */
class Content(
    private val storage: ContentStorage,
    private val transport: ContentTransport
) {
    constructor(store: BoxStore, transport: ContentTransport) : this(storage(store), transport)

    /** The whole body of message [hex] ([BodyMode.Both]); [refresh] fetches it again. */
    suspend fun body(hex: String, refresh: Boolean = false): Body = body(hex, BodyMode.Both, refresh)

    /**
     * The body of message [hex] read in [mode]; [refresh] fetches it again.
     * A sanitised [BodyMode.Rich] body has an empty text and a
     * [BodyMode.Plain] one an empty HTML; a raw body is returned whole.
     */
    suspend fun body(hex: String, mode: BodyMode, refresh: Boolean = false): Body {
        val cached = storage.get(hex)
        if (cached != null && !refresh && !stale(cached)) return body(cached).only(mode)
        val fresh = try {
            transport.fetch(hex, BodyMode.Both)
        } catch (e: Exception) {
            if (e is CancellationException || cached == null || refresh) throw e
            return body(cached).only(mode)
        }
        // The cache is best effort: a body the store cannot take (too large,
        // database full or closed) is still returned, just not cached.
        try {
            storage.put(
                ContentEntity(
                    id = cached?.id ?: 0,
                    hex = hex,
                    text = fresh.text,
                    html = fresh.html,
                    attachments = encode(fresh.attachments),
                    fetched = System.currentTimeMillis(),
                    quoteStart = fresh.quoteStart,
                    hasText = fresh.hasText,
                    hasHtml = fresh.hasHtml,
                    sanitised = fresh.sanitised
                )
            )
        } catch (e: Exception) {
            Faults.report("body cache write", e)
        }
        return fresh.copy(hex = hex).only(mode)
    }

    // The server's answer to [ContentTransport.sanitised], once known.
    @Volatile private var reading: Boolean? = null
    // When asking failed last (offline): cached reads do not wait on the network again for a minute.
    @Volatile private var failed = 0L

    // A raw row fetched from the server, now that the server sanitises.
    private suspend fun stale(row: ContentEntity): Boolean {
        if (row.sanitised || row.local) return false
        reading?.let { return it }
        if (System.currentTimeMillis() - failed < RETRY) return false
        return try {
            transport.sanitised().also { reading = it }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = System.currentTimeMillis()
            false
        }
    }

    /**
     * The whole bodies of [hexes] ([BodyMode.Both]), cache-first: the ones
     * not cached are fetched in one batch and cached. A raw cached body is
     * served as it is. Best effort: when the fetch fails (offline) only the
     * cached ones are returned; it throws only on cancellation.
     */
    suspend fun bodies(hexes: List<String>): Map<String, Body> {
        val out = LinkedHashMap<String, Body>()
        val missing = ArrayList<String>()
        for (hex in hexes.distinct()) {
            val row = try {
                storage.get(hex)
            } catch (e: Exception) {
                Faults.report("body cache read", e)
                null
            }
            if (row != null) out[hex] = body(row) else missing += hex
        }
        if (missing.isEmpty()) return out
        val fresh = try {
            missing.chunked(BATCH).flatMap { transport.fetchAll(it, BodyMode.Both) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Faults.report("body batch fetch", e)
            return out
        }
        for (body in fresh) {
            if (body.hex !in missing) continue
            out[body.hex] = body
            try {
                storage.put(
                    ContentEntity(
                        hex = body.hex,
                        text = body.text,
                        html = body.html,
                        attachments = encode(body.attachments),
                        fetched = System.currentTimeMillis(),
                        quoteStart = body.quoteStart,
                        hasText = body.hasText,
                        hasHtml = body.hasHtml,
                        sanitised = body.sanitised
                    )
                )
            } catch (e: Exception) {
                Faults.report("body cache write", e)
            }
        }
        return out
    }

    /** The cached body of [hex] without fetching, or null. */
    fun cached(hex: String): Body? = storage.get(hex)?.let(::body)

    /** Saves a body directly into storage without network (e.g. for drafts). */
    fun put(hex: String, text: String, html: String = "", attachments: List<Attachment> = emptyList()) {
        storage.put(
            ContentEntity(
                hex = hex,
                text = text,
                html = html,
                attachments = encode(attachments),
                fetched = System.currentTimeMillis(),
                local = true
            )
        )
    }

    /** Drops cached bodies (e.g. of deleted messages). */
    fun forget(hexes: List<String>) = storage.remove(hexes)

    companion object {
        private const val RETRY = 60_000L

        /** Bodies fetched per request by [bodies]. */
        private const val BATCH = 50

        /** The ObjectBox-backed storage for [store]. */
        fun storage(store: BoxStore): ContentStorage = object : ContentStorage {
            private val box = store.boxFor(ContentEntity::class.java)

            override fun get(hex: String): ContentEntity? =
                box.query(Content_.hex.equal(hex)).build().use { it.findFirst() }
            override fun put(content: ContentEntity) { box.put(content) }
            override fun remove(hexes: List<String>) {
                if (hexes.isEmpty()) return
                box.query(Content_.hex.oneOf(hexes.toTypedArray())).build().use { it.remove() }
            }
        }

        private fun body(row: ContentEntity): Body =
            if (row.sanitised) {
                Body(
                    hex = row.hex, text = row.text, html = row.html, attachments = decode(row.attachments),
                    quoteStart = row.quoteStart, hasText = row.hasText, hasHtml = row.hasHtml, sanitised = true
                )
            } else {
                Body(hex = row.hex, text = row.text, html = row.html, attachments = decode(row.attachments))
            }

        // The part of a sanitised body [mode] reads; a raw body is returned whole.
        private fun Body.only(mode: BodyMode): Body = when {
            !sanitised -> this
            mode == BodyMode.Rich -> copy(text = "", quoteStart = null)
            mode == BodyMode.Plain -> copy(html = "")
            else -> this
        }

        fun encode(attachments: List<Attachment>): String = JSONArray(attachments.map { a ->
            JSONObject()
                .put("blob", a.blob)
                .put("name", a.name)
                .put("type", a.type)
                .put("size", a.size)
                .put("cid", a.cid ?: JSONObject.NULL)
                .put("inline", a.inline)
        }).toString()

        fun decode(text: String): List<Attachment> {
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Attachment(
                    blob = o.optString("blob"),
                    name = o.optString("name"),
                    type = o.optString("type"),
                    size = o.optLong("size"),
                    cid = if (o.isNull("cid")) null else o.optString("cid"),
                    inline = o.optBoolean("inline")
                )
            }
        }
    }
}
