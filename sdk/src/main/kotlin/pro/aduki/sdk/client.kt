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
import pro.aduki.core.config.Pin
import pro.aduki.net.http.Client as HttpClient
import pro.aduki.net.http.Dpop
import pro.aduki.net.http.Events
import pro.aduki.net.http.Id
import pro.aduki.net.http.Login
import pro.aduki.net.http.Scheme
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
import pro.aduki.net.http.Contacts as NetContacts
import pro.aduki.sync.engine.ContactStorage
import pro.aduki.sync.http.HttpContactTransport

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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dpop: Dpop? = null,
    contactStorage: ContactStorage? = null
) {
    private fun activeAuthString(): String {
        return session.token() ?: if (token.isNotBlank()) token else apiKey
    }

    // The `Key` scheme is for a credential that came in through `Builder.key`:
    // true while no session token and no bare token take precedence over it.
    private fun usingApiKey(): Boolean = session.token() == null && token.isBlank() && apiKey.isNotBlank()

    // Null unless the app configured pins (opt-in; the SDK ships none).
    private val pinner: okhttp3.CertificatePinner? by lazy {
        if (options.secure) pro.aduki.crypto.tls.Pinning.pinner(options.pins) else null
    }

    private val defaultClient: OkHttpClient by lazy {
        val base = HttpClient.create(activeAuthString(), options.timeoutSeconds, pinner, usingApiKey())
        base.newBuilder()
            .addInterceptor { chain ->
                val auth = activeAuthString()
                val request = if (auth.isNotBlank()) {
                    chain.request().newBuilder()
                        .header("Authorization", Scheme.header(auth, usingApiKey()))
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .authenticator(renewer)
            .apply { dpop?.let(::addInterceptor) }
            .build()
    }

    // Aduki ID access tokens live ten minutes: on a 401 renew once with the
    // refresh token and retry. API keys are not renewed.
    private val renewer = Authenticator { _, response ->
        val sent = response.request.header("Authorization").orEmpty()
        val scheme = listOf("Bearer ", "DPoP ").firstOrNull { sent.startsWith(it) }
        if (response.priorResponse != null || scheme == null) {
            null
        } else {
            renew(sent.removePrefix(scheme))?.let { fresh ->
                val next = response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
                dpop?.apply(next) ?: next // a rebuilt request needs its own proof
            }
        }
    }

    // A client from `Builder.http` gets the same renewal unless it brings
    // its own authenticator.
    private val customClient: OkHttpClient? by lazy {
        httpClient?.let { custom ->
            if (custom.authenticator == Authenticator.NONE) {
                custom.newBuilder().authenticator(renewer).apply { dpop?.let(::addInterceptor) }.build()
            } else custom
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
            dpop?.let(::addInterceptor)
        }?.build() ?: HttpClient.create("", options.timeoutSeconds, pinner).let { base ->
            if (dpop == null) base else base.newBuilder().addInterceptor(dpop).build()
        }
    }

    /**
     * Typed REST address book client on this client's authenticated connection.
     * Build `HttpContactTransport(contactsApi)` from it for a contact engine.
     */
    val contactsApi: NetContacts by lazy { NetContacts(activeHttpClient(), options.endpoint) }

    // An explicit engine wins; with only a storage the engine reads the
    // address book over REST (`HttpContactTransport`).
    private val contactSync: ContactEngine? =
        contactEngine ?: contactStorage?.let { ContactEngine(it, HttpContactTransport(contactsApi)) }

    // Declared after the HTTP client above: property initializers run in
    // order, and `scheduling` needs the client while the object is built.
    val mail = Mail(this, manager, mailRepo, worker)
    val contacts = Contacts(this, contactRepo, contactSync)
    val sync = Sync(this, mailboxEngine, contactSync, worker, manager)
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
        return Login.totp(activeHttpClient(), options.endpoint, currentToken, code, usingApiKey())
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
        private var secure: Boolean = true
        private var pins: List<Pin> = emptyList()
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
        private var dpop: Dpop? = null
        private var contactStorage: ContactStorage? = null
        private var secureStore: pro.aduki.crypto.keystore.Provider? = null

        fun key(key: String) = apply { this.apiKey = key }
        fun token(token: String) = apply { this.token = token }
        fun endpoint(endpoint: String) = apply { this.endpoint = endpoint }
        /** The Aduki ID base used to renew and revoke sign-ins, e.g. `https://id.aduki.pro/v1`. */
        fun identity(identity: String) = apply { this.identity = identity }
        /**
         * Binds sign-ins to a device key (DPoP, RFC 9449): Aduki ID and mail
         * calls carry a proof, and tokens bound to the key are sent as
         * `Authorization: DPoP`. Hardware keys must be P-256 (`ES256`).
         */
        fun dpop(key: pro.aduki.crypto.dpop.Key) = apply { this.dpop = Dpop(key) }
        fun secure(enabled: Boolean) = apply { this.secure = enabled }
        /**
         * Pins the TLS public keys of the given hosts on the default HTTP
         * client. Off by default: the SDK ships no pins. Ignored when
         * [secure] is false or a client is supplied through [http].
         */
        fun pins(pins: List<Pin>) = apply { this.pins = pins }
        fun pins(vararg pins: Pin) = apply { this.pins = pins.toList() }
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
        /**
         * Syncs contacts into [storage] over REST (`GET /user/contacts`, full
         * list reconcile): `client.contacts.sync()` and `client.sync.all()` then
         * work without building an engine. With ObjectBox, `ContactEngine.storage(boxStore)`.
         */
        fun contactStorage(storage: ContactStorage) = apply { this.contactStorage = storage }
        /**
         * Seals the sensitive payload columns of the local database (see
         * `Sealing`) with keys from [provider]. On Android with no call to this,
         * the Android Keystore provider is used; on a plain JVM nothing is
         * sealed unless this is called, and a `Provider` there keeps its keys
         * in memory only, so use it for tests. The vault is process-wide and is
         * installed by [build], before the database is opened. Android Keystore
         * behaviour is unverified until a device run.
         */
        fun secureStore(provider: pro.aduki.crypto.keystore.Provider) = apply { this.secureStore = provider }
        fun engines(mailbox: MailboxEngine, contact: ContactEngine) = apply {
            this.mailboxEngine = mailbox
            this.contactEngine = contact
        }

        fun build(): Aduki {
            require(apiKey.isNotBlank() || token.isNotBlank()) {
                "Either API key or JWT token must not be blank"
            }
            (secureStore?.let { pro.aduki.crypto.cipher.Vault(it) } ?: pro.aduki.crypto.cipher.Vault.platform())
                ?.let { pro.aduki.store.box.Sealing.install(it) }
            val options = Options(
                endpoint = endpoint,
                identity = identity,
                timeoutSeconds = timeoutSeconds,
                secure = secure,
                pins = pins
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
                scheduleEngine = scheduleEngine,
                dpop = dpop,
                contactStorage = contactStorage
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
            backup: String? = null,
            dpop: pro.aduki.crypto.dpop.Key? = null,
            pins: List<Pin> = emptyList()
        ): Aduki {
            val tempClient = HttpClient.create("", 15, pro.aduki.crypto.tls.Pinning.pinner(pins)).let { base ->
                if (dpop == null) base else base.newBuilder().addInterceptor(Dpop(dpop)).build()
            }
            val tokens = Login.submit(tempClient, identity, handle, password, code, backup)
            val client = builder()
                .apply { if (dpop != null) dpop(dpop) }
                .endpoint(endpoint)
                .identity(identity)
                .pins(pins)
                .token(tokens.token)
                .build()

            client.session.update(tokens)
            client.me() // Eagerly resolve identity
            return client
        }
    }
}

