package pro.aduki.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import org.json.JSONObject
import pro.aduki.core.config.Endpoints
import pro.aduki.core.config.Options
import pro.aduki.net.http.Client as HttpClient
import pro.aduki.net.http.Events
import pro.aduki.net.http.Id
import pro.aduki.net.http.Login
import pro.aduki.net.http.Whoami
import pro.aduki.core.models.Identity
import pro.aduki.core.models.Tokens
import pro.aduki.state.repository.Session

import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.sync.engine.Mailbox as MailboxEngine
import pro.aduki.sync.engine.Schedule as ScheduleEngine
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Worker
import pro.aduki.state.repository.Contact as ContactRepo
import pro.aduki.state.repository.Mail as MailRepo
import pro.aduki.state.repository.Appointment as AppointmentRepo
import pro.aduki.net.http.Scheduling as NetScheduling
import pro.aduki.net.http.Mail as NetMail

/**
 * Aduki is the primary entrypoint for the Android Kotlin SDK.
 */
class Aduki internal constructor(
    val apiKey: String = "",
    val token: String = "",
    val options: Options,
    val session: Session = Session(),
    val lifecycle: Lifecycle = Lifecycle(),
    private val httpClient: OkHttpClient? = null,
    manager: Manager? = null,
    worker: Worker? = null,
    mailRepo: MailRepo? = null,
    contactRepo: ContactRepo? = null,
    appointmentRepo: AppointmentRepo? = null,
    mailboxEngine: MailboxEngine? = null,
    contactEngine: ContactEngine? = null,
    scheduleEngine: ScheduleEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private fun activeAuthString(): String {
        return session.token() ?: if (token.isNotBlank()) token else apiKey
    }

    private val defaultClient: OkHttpClient by lazy {
        val pinner = if (options.secure && !options.endpoint.contains("localhost") && !options.endpoint.contains("127.0.0.1")) {
            pro.aduki.crypto.tls.Pinning.pinner()
        } else {
            null
        }
        val base = HttpClient.create(activeAuthString(), options.timeoutSeconds, pinner)
        base.newBuilder()
            .addInterceptor { chain ->
                val auth = activeAuthString()
                val request = if (auth.isNotBlank()) {
                    val authHeader = if (auth.startsWith("hm_") || auth.startsWith("key_")) "Key $auth" else "Bearer $auth"
                    chain.request().newBuilder()
                        .header("Authorization", authHeader)
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .authenticator(renewer)
            .build()
    }

    // Aduki ID access tokens live ten minutes: on a 401 renew once with the
    // refresh token and retry. API keys are not renewed.
    private val renewer = Authenticator { _, response ->
        val sent = response.request.header("Authorization").orEmpty()
        if (response.priorResponse != null || !sent.startsWith("Bearer ")) {
            null
        } else {
            renew(sent.removePrefix("Bearer "))?.let { fresh ->
                response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
            }
        }
    }

    // A client from `Builder.http` gets the same renewal unless it brings
    // its own authenticator.
    private val customClient: OkHttpClient? by lazy {
        httpClient?.let { custom ->
            if (custom.authenticator == Authenticator.NONE) custom.newBuilder().authenticator(renewer).build() else custom
        }
    }

    private val renewal = Any()

    // The Aduki ID client (K2) owns renewal: one serialized refresh rotation,
    // a spent refresh token is dropped and never presented again. `session`
    // stays the observable copy and the source of truth for sign-ins adopted
    // from outside (`Aduki.login`, `session.update`).
    private val id: Id by lazy { Id(identityClient, options.identity) }

    // Makes [id] hold what [session] holds; must hold [renewal].
    private fun align(current: Tokens) {
        if (id.refreshToken() != current.refresh || id.session() != current.session) id.adopt(current)
    }

    /**
     * Renews the access token unless another caller already replaced [stale].
     */
    private fun renew(stale: String?): String? = synchronized(renewal) {
        val current = session.tokens.value ?: return null
        if (stale != null && current.token != stale) return current.token
        if (current.refresh.isBlank()) return null
        align(current)
        try {
            val fresh = id.rotate(Endpoints.AUDIENCE, current.token)
            session.update(fresh.copy(session = current.session))
            fresh.token
        } catch (e: pro.aduki.core.errors.AdukiException.Auth) {
            // A 2xx that failed validation spent the refresh token: keep the
            // rotated one if it came back, else drop it (reuse revokes the session).
            session.update(current.copy(refresh = e.refresh))
            null
        } catch (e: pro.aduki.core.errors.AdukiException.Unauthorized) {
            // Refused: spent, expired or revoked. Never present it again.
            session.update(current.copy(refresh = ""))
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun activeHttpClient(): OkHttpClient {
        return customClient ?: defaultClient
    }

    // Aduki ID calls carry their own credentials (a refresh token, or an `id`
    // audience token for sign-out), never mail's. A custom client keeps its
    // connection settings but loses its interceptors and authenticator here,
    // so nothing can swap in the mail token.
    private val identityClient: OkHttpClient by lazy {
        httpClient?.newBuilder()?.apply {
            interceptors().clear()
            networkInterceptors().clear()
            authenticator(Authenticator.NONE)
        }?.build() ?: HttpClient.create("", options.timeoutSeconds)
    }

    // Declared after the HTTP client above: property initializers run in
    // order, and `scheduling` needs the client while the object is built.
    val mail = Mail(this, manager, mailRepo, worker)
    val contacts = Contacts(this, contactRepo, contactEngine)
    val sync = Sync(this, mailboxEngine, contactEngine, worker, manager)
    val scheduling = Scheduling(this, appointmentRepo, scheduleEngine, NetScheduling(activeHttpClient(), options.endpoint))

    /**
     * Typed REST mail client on this client's authenticated connection.
     * Build `HttpMailboxTransport(mailApi)` and `HttpDispatcher(mailApi, …)`
     * from it for the sync engine and the outbox worker.
     */
    val mailApi: NetMail by lazy { NetMail(activeHttpClient(), options.endpoint) }

    init {
        lifecycle.listen { active ->
            if (active) {
                scope.launch {
                    sync.flush()
                }
            }
        }
    }

    /**
     * Resolves authenticated user and tenant identity, utilizing cache if already resolved.
     */
    suspend fun me(): Identity? {
        val cached = session.identity.value
        if (cached != null) return cached

        return try {
            val resolved = Whoami.resolve(activeHttpClient(), options.endpoint)
            session.update(resolved)
            resolved
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Confirms or configures 6-digit TOTP secret for the active account.
     */
    @Deprecated("Second factors are managed in Aduki ID's Account Center; mail's /user/totp is going away.")
    @Suppress("DEPRECATION")
    suspend fun totp(code: String): Boolean {
        val currentToken = activeAuthString()
        return Login.totp(activeHttpClient(), options.endpoint, currentToken, code)
    }

    /**
     * Renews the access token at Aduki ID with the active refresh token. The
     * refresh token rotates, so the new pair replaces the old one; the
     * session hex is kept for sign-out.
     */
    suspend fun refresh(): Boolean {
        return renew(null) != null
    }

    /**
     * Handles a `rights` push (ADK-AUTH-003 §7.4): an `event: rights` from the
     * JMAP EventSource, or a web-push or FCM payload. [payload] is its JSON
     * body. For `{"@type": "Rights", ...}` the access token is renewed now,
     * before the next request would be refused with `auth.stale`; anything
     * else is ignored. Returns whether a token was renewed.
     */
    suspend fun rights(payload: String): Boolean = applyRights(payload)

    private fun applyRights(payload: String): Boolean {
        val type = runCatching { JSONObject(payload).optString("@type") }.getOrNull()
        if (type != "Rights") return false
        val stale = session.tokens.value?.token ?: return false
        return renew(stale) != null
    }

    private var watcher: Events? = null

    /**
     * Subscribes to the Mail JMAP EventSource (`{mail host}/jmap/eventsource`)
     * and renews the access token on every `rights` event (plan K3). The
     * stream reconnects by itself, with jitter backoff after failures, and a
     * stale token is renewed on the next 401 whether or not a push arrived.
     * [host] is the mail host root, by default the REST endpoint without
     * its `/v1`. Calling it again replaces the previous subscription; stop it
     * with [unwatchRights] (also done by [logout]).
     */
    fun watchRights(host: String = options.endpoint.removeSuffix("/").removeSuffix("/v1")) {
        val http = activeHttpClient().newBuilder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        val events = Events(http, host, { payload -> applyRights(payload) })
        synchronized(renewal) {
            watcher?.stop()
            watcher = events
        }
        scope.launch { events.run() }
    }

    /** Stops the subscription started by [watchRights]. */
    fun unwatchRights() = synchronized(renewal) {
        watcher?.stop()
        watcher = null
    }

    /**
     * Signs out at Aduki ID (revoking the session and every token issued from
     * it) and wipes local tokens. Clients built from an API key or a bare
     * token have no session to revoke and return `false`.
     *
     * If Aduki ID could not be reached or refused the revocation, this returns
     * `false` and keeps the session (with the rotated refresh token) so a
     * later call can retry; `session.clear()` forgets it locally instead.
     */
    suspend fun logout(): Boolean = synchronized(renewal) {
        watcher?.stop()
        watcher = null
        val current = session.tokens.value
        if (current == null) {
            session.clear()
            return false
        }
        align(current)
        val revoked = id.signOut()
        if (id.signedIn()) {
            // Still signed in remotely: keep the retry credential.
            session.update(current.copy(refresh = id.refreshToken()))
        } else {
            session.clear()
        }
        revoked
    }

    /**
     * Suspends background operations when app enters background.
     */
    fun pause() {
        lifecycle.pause()
    }

    /**
     * Resumes background operations and flushes outbox when app enters foreground.
     */
    fun resume() {
        lifecycle.resume()
    }

    class Builder {
        private var apiKey: String = ""
        private var token: String = ""
        private var endpoint: String = Endpoints.REST
        private var identity: String = Endpoints.ID
        private var grpcHost: String = Endpoints.GRPC_HOST
        private var grpcPort: Int = Endpoints.GRPC_PORT
        private var secure: Boolean = true
        private var timeoutSeconds: Long = 15
        private var httpClient: OkHttpClient? = null
        private var manager: Manager? = null
        private var worker: Worker? = null
        private var mailRepo: MailRepo? = null
        private var contactRepo: ContactRepo? = null
        private var mailboxEngine: MailboxEngine? = null
        private var contactEngine: ContactEngine? = null
        private var appointmentRepo: AppointmentRepo? = null
        private var scheduleEngine: ScheduleEngine? = null

        fun key(key: String) = apply { this.apiKey = key }
        fun token(token: String) = apply { this.token = token }
        fun endpoint(endpoint: String) = apply { this.endpoint = endpoint }
        /** The Aduki ID base used to renew and revoke sign-ins, e.g. `https://id.aduki.pro/v1`. */
        fun identity(identity: String) = apply { this.identity = identity }
        fun grpc(host: String, port: Int = Endpoints.GRPC_PORT) = apply {
            this.grpcHost = host
            this.grpcPort = port
        }
        fun secure(enabled: Boolean) = apply { this.secure = enabled }
        fun timeout(seconds: Long) = apply { this.timeoutSeconds = seconds }
        fun http(client: OkHttpClient) = apply { this.httpClient = client }
        fun manager(manager: Manager) = apply { this.manager = manager }
        fun worker(worker: Worker) = apply { this.worker = worker }
        fun mail(repo: MailRepo) = apply { this.mailRepo = repo }
        fun contacts(repo: ContactRepo) = apply { this.contactRepo = repo }
        fun scheduling(repo: AppointmentRepo, engine: ScheduleEngine? = null) = apply {
            this.appointmentRepo = repo
            this.scheduleEngine = engine
        }
        fun engines(mailbox: MailboxEngine, contact: ContactEngine) = apply {
            this.mailboxEngine = mailbox
            this.contactEngine = contact
        }

        fun build(): Aduki {
            require(apiKey.isNotBlank() || token.isNotBlank()) {
                "Either API key or JWT token must not be blank"
            }
            val options = Options(
                endpoint = endpoint,
                identity = identity,
                grpcHost = grpcHost,
                grpcPort = grpcPort,
                timeoutSeconds = timeoutSeconds,
                secure = secure
            )
            return Aduki(
                apiKey = apiKey,
                token = token,
                options = options,
                httpClient = httpClient,
                manager = manager,
                worker = worker,
                mailRepo = mailRepo,
                contactRepo = contactRepo,
                appointmentRepo = appointmentRepo,
                mailboxEngine = mailboxEngine,
                contactEngine = contactEngine,
                scheduleEngine = scheduleEngine
            )
        }
    }

    companion object {
        fun builder() = Builder()

        /**
         * Signs in at Aduki ID with a full address, password and second factor
         * (an authenticator [code] or a [backup] code) and returns a client
         * that sends the resulting mail token.
         */
        suspend fun login(
            handle: String,
            password: String,
            code: String? = null,
            endpoint: String = Endpoints.REST,
            identity: String = Endpoints.ID,
            backup: String? = null
        ): Aduki {
            val tempClient = HttpClient.create("", 15)
            val tokens = Login.submit(tempClient, identity, handle, password, code, backup)
            val client = builder()
                .endpoint(endpoint)
                .identity(identity)
                .token(tokens.token)
                .build()

            client.session.update(tokens)
            client.me() // Eagerly resolve identity
            return client
        }
    }
}

