package pro.aduki.hermes.net.http

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import pro.aduki.hermes.core.errors.HermesException
import pro.aduki.hermes.core.models.Identity

/**
 * Whoami resolves the signed-in account via GET /user.
 *
 * `/user` accepts Aduki ID tokens as well as API keys (`/auth/whoami` does
 * not). It carries the account and its tenant; scopes and tier are not part
 * of it and stay empty.
 */
class Whoami(
    private val client: OkHttpClient,
    private val endpoint: String
) {

    /**
     * Resolves authenticated identity from the Hermes server.
     */
    fun resolve(): Identity {
        val url = "${endpoint.trimEnd('/')}/user"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw HermesException.Network("Failed to connect to Hermes whoami endpoint", e)
        }

        response.use { resp ->
            val raw = resp.body?.string()
            if (resp.code == 401 || resp.code == 403) {
                val why = raw?.let(Envelope::failure) ?: "HTTP ${resp.code}"
                throw HermesException.Auth("Invalid or expired API key ($why)")
            }
            if (!resp.isSuccessful) {
                throw HermesException.Network("Whoami request failed with HTTP ${resp.code}", code = resp.code)
            }

            val body = raw?.let(Envelope::data)?.ifBlank { null }
                ?: throw HermesException.Network("Empty response from whoami")
            return parse(body)
        }
    }

    private fun parse(json: String): Identity {
        val obj = JSONObject(json)
        val user = obj.optString("hex", "").ifBlank { obj.optString("user", "") }
        val tenant = obj.optString("tenant", "")
        val tier = obj.optString("tier", "")
        val owner = obj.optBoolean("owner", false)

        val scopesArray = obj.optJSONArray("scopes")
        val scopes = if (scopesArray != null) {
            (0 until scopesArray.length()).map { scopesArray.getString(it) }
        } else {
            emptyList()
        }

        return Identity(
            user = user,
            tenant = tenant,
            owner = owner,
            scopes = scopes,
            tier = tier
        )
    }

    companion object {
        fun resolve(client: OkHttpClient, endpoint: String): Identity = Whoami(client, endpoint).resolve()
    }
}

