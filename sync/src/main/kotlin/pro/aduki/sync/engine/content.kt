package pro.aduki.sync.engine

import io.objectbox.BoxStore
import org.json.JSONArray
import org.json.JSONObject
import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Body
import pro.aduki.store.entities.Content as ContentEntity
import pro.aduki.store.entities.Content_

/**
 * ContentTransport fetches the body of one message from the server.
 */
fun interface ContentTransport {
    suspend fun fetch(hex: String): Body
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
 */
class Content(
    private val storage: ContentStorage,
    private val transport: ContentTransport
) {
    constructor(store: BoxStore, transport: ContentTransport) : this(storage(store), transport)

    /** The body of message [hex]; [refresh] fetches it again. */
    suspend fun body(hex: String, refresh: Boolean = false): Body {
        val cached = storage.get(hex)
        if (cached != null && !refresh) return body(cached)
        val fresh = transport.fetch(hex)
        storage.put(
            ContentEntity(
                id = cached?.id ?: 0,
                hex = hex,
                text = fresh.text,
                html = fresh.html,
                attachments = encode(fresh.attachments),
                fetched = System.currentTimeMillis()
            )
        )
        return fresh.copy(hex = hex)
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
                fetched = System.currentTimeMillis()
            )
        )
    }

    /** Drops cached bodies (e.g. of deleted messages). */
    fun forget(hexes: List<String>) = storage.remove(hexes)

    companion object {
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
            Body(hex = row.hex, text = row.text, html = row.html, attachments = decode(row.attachments))

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
