package pro.aduki.net.http

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import pro.aduki.core.retry.Jitter

/**
 * Events subscribes to the Aduki Mail JMAP EventSource (`GET /jmap/eventsource`)
 * for the `rights` event (ADK-KT-003 K3, ADK-AUTH-003 §7.4). The server sends
 * `event: rights` with `{"@type": "Rights", "epoch": N}` (or `"keys": true`)
 * and then ends the stream; the client renews its token and reconnects.
 *
 * [client] is the authenticated client (token and 401 renewal included).
 * [endpoint] is the mail host root without `/v1`, e.g. `https://mail.aduki.pro`.
 * The push is an optimisation: a stale token is refused with `auth.stale`
 * and renewed by the client's 401 path whether or not a push arrived.
 *
 * Reconnects after a failure back off with decorrelated [jitter]; after a
 * `rights` event the stream is reopened at once (it was ended on purpose)
 * and each event is handed to [onRights] exactly once.
 */
class Events(
    private val client: OkHttpClient,
    private val endpoint: String,
    private val onRights: (String) -> Unit,
    private val jitter: Jitter = Jitter(1_000, 60_000),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    @Volatile private var stopped = false
    @Volatile private var call: Call? = null

    /** Ends [run] and closes the open stream. */
    fun stop() {
        stopped = true
        call?.cancel()
    }

    /** Blocks, streaming and reconnecting, until [stop] is called. */
    fun run() {
        while (!stopped) {
            val renewed = try {
                once()
            } catch (_: Exception) {
                false // connect failure, refusal, or a stream cut short
            }
            if (stopped) return
            if (renewed) {
                jitter.reset()
            } else {
                try {
                    sleep(jitter.next())
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * Opens one stream and reads it to its end. Returns true when it ended
     * after at least one `rights` event (so the caller reconnects without
     * backoff), false when it ended without one. Throws on a refused
     * connection (non-2xx) or a transport error.
     */
    internal fun once(): Boolean {
        val url = endpoint.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("jmap/eventsource")
            .addQueryParameter("types", "*")
            .addQueryParameter("ping", "30")
            .build()
        val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        val open = client.newCall(request)
        call = open
        var seen = false
        open.execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("eventsource refused: ${response.code}")
            val source = response.body?.source() ?: return false
            var type = ""
            val data = StringBuilder()
            while (!stopped) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        if (type == "rights" && data.isNotEmpty()) {
                            seen = true
                            onRights(data.toString())
                        }
                        type = ""
                        data.setLength(0)
                    }
                    line.startsWith(":") -> Unit // comment or ping
                    else -> {
                        val colon = line.indexOf(':')
                        val field = if (colon < 0) line else line.substring(0, colon)
                        var value = if (colon < 0) "" else line.substring(colon + 1)
                        if (value.startsWith(" ")) value = value.substring(1)
                        when (field) {
                            "event" -> type = value
                            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
                        }
                    }
                }
            }
        }
        return seen
    }
}
