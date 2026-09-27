package pro.aduki.hermes.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import pro.aduki.hermes.core.config.Endpoints
import pro.aduki.hermes.core.config.Options
import pro.aduki.hermes.net.http.Client as HttpClient
import pro.aduki.hermes.net.http.Login
import pro.aduki.hermes.net.http.Whoami
import pro.aduki.hermes.core.models.Identity
import pro.aduki.hermes.core.models.Tokens
import pro.aduki.hermes.state.repository.Session

import pro.aduki.hermes.sync.engine.Contact as ContactEngine
import pro.aduki.hermes.sync.engine.Mailbox as MailboxEngine
import pro.aduki.hermes.sync.engine.Schedule as ScheduleEngine
import pro.aduki.hermes.sync.outbox.Manager
import pro.aduki.hermes.sync.outbox.Worker
import pro.aduki.hermes.state.repository.Contact as ContactRepo
import pro.aduki.hermes.state.repository.Mail as MailRepo
import pro.aduki.hermes.state.repository.Appointment as AppointmentRepo
import pro.aduki.hermes.net.http.Scheduling as NetScheduling
import pro.aduki.hermes.net.http.Mail as NetMail

/**
 * HermesClient is the primary entrypoint for the Android Kotlin SDK.
 */
class HermesClient internal constructor(
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
            pro.aduki.hermes.crypto.tls.Pinning.pinner()
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
            .authenticator { _, response ->
                // Aduki ID access tokens live ten minutes: on a 401 renew once
                // with the refresh token and retry. API keys are not renewed.
                val sent = response.request.header("Authorization").orEmpty()
                if (response.priorResponse != null || !sent.startsWith("Bearer ")) {
                    null
                } else {
                    renew(sent.removePrefix("Bearer "))?.let { fresh ->
                        response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
                    }
                }
            }
            .build()
    }

    private val renewal = Any()

    /**
     * Renews the access token unless another caller already replaced [stale].
     * Serialized: refresh tokens rotate, and presenting a spent one twice
     * makes Aduki ID revoke the whole session.
     */
    private fun renew(stale: String?): String? = synchronized(renewal) {
        val current = session.tokens.value ?: return null
        if (stale != null && current.token != stale) return current.token
        if (current.refresh.isBlank()) return null
        try {
            val fresh = Login.refresh(identityClient, options.identity, current.refresh)
            session.update(fresh.copy(session = current.session))
            fresh.token
        } catch (_: Exception) {
            null
        }
    }

    private fun activeHttpClient(): OkHttpClient {
        return httpClient ?: defaultClient
    }

    // Aduki ID calls carry their own credentials (a refresh token, or an `id`
    // audience token for sign-out), never the mail token the default client adds.
    private val identityClient: OkHttpClient by lazy {
        httpClient ?: HttpClient.create("", options.timeoutSeconds)
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
     * Signs out at Aduki ID (revoking the session and every token issued from
     * it) and wipes local tokens. Clients built from an API key or a bare
     * token have no session to revoke and return `false`.
     */
    suspend fun logout(): Boolean {
        val ok = synchronized(renewal) {
            val current = session.tokens.value
            current != null && Login.logout(identityClient, options.identity, current.session, current.refresh)
        }
        session.clear()
        return ok
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

        fun build(): HermesClient {
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
            return HermesClient(
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
        ): HermesClient {
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

