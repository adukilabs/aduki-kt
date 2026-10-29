package pro.aduki.sdk

import io.objectbox.BoxStore
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
import pro.aduki.net.http.Jmap
import pro.aduki.net.http.Login
import pro.aduki.net.http.Signup
import pro.aduki.net.http.Requirements
import pro.aduki.net.http.Enrolment
import pro.aduki.net.http.Scheme
import pro.aduki.net.http.Whoami
import pro.aduki.core.models.Identity
import pro.aduki.core.models.TokenStore
import pro.aduki.core.models.Tokens
import pro.aduki.state.repository.Session

import pro.aduki.sync.engine.Contact as ContactEngine
import pro.aduki.sync.engine.Mailbox as MailboxEngine
import pro.aduki.sync.engine.Schedule as ScheduleEngine
import pro.aduki.sync.outbox.Book
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Worker
import pro.aduki.state.repository.Contact as ContactRepo
import pro.aduki.state.repository.Mail as MailRepo
import pro.aduki.state.repository.Appointment as AppointmentRepo
import pro.aduki.net.http.Scheduling as NetScheduling
import pro.aduki.net.http.Mail as NetMail
import pro.aduki.net.http.Contacts as NetContacts
import pro.aduki.sync.engine.ContactStorage
import pro.aduki.sync.http.ContactDispatcher
import pro.aduki.sync.http.HttpContactTransport
import pro.aduki.sync.http.HttpDispatcher
import pro.aduki.sync.http.HttpMailboxTransport
import pro.aduki.sync.http.JmapContentTransport
import pro.aduki.sync.engine.Content as ContentEngine
import pro.aduki.sync.http.ScheduleDispatcher
import pro.aduki.sync.outbox.Dispatcher
import pro.aduki.sync.outbox.Rejected
import pro.aduki.store.box.Factory
import pro.aduki.store.entities.Outbox
import java.io.File

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
    contactStorage: ContactStorage? = null,
    /** The local database; with it every part below that was not supplied is built. */
    private val store: BoxStore? = null,
    /** Whether [close] closes [store] (it was opened by `Builder.store(dir)`). */
    private val owned: Boolean = false,
    rejected: (Outbox, Rejected) -> Unit = { _, _ -> },
    val tokenStore: TokenStore? = null
) {
    init {
        tokenStore?.load()?.let { stored ->
            session.update(stored)
        }
    }

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
        var current = session.tokens.value ?: return null

        if (tokenStore != null) {
            val stored = try { tokenStore.load() } catch (_: Exception) { null }
            if (stored != null && stored.refresh.isNotBlank() && stored.refresh != current.refresh) {
                current = current.copy(
                    token = if (stored.token.isNotBlank()) stored.token else current.token,
                    refresh = stored.refresh,
                    expires = if (stored.expires.isNotBlank()) stored.expires else current.expires,
                    session = if (stored.session.isNotBlank()) stored.session else current.session
                )
                session.update(current)
                align(current)
                if (stale != null && current.token.isNotBlank() && current.token != stale) {
                    return current.token
                }
            }
        }

        if (stale != null && current.token != stale && current.token.isNotBlank()) return current.token
        if (current.refresh.isBlank()) return null
        align(current)

        val presented = current.refresh
        try {
            val fresh = id.rotate(Endpoints.AUDIENCE, current.token)
            val updated = fresh.copy(session = current.session)
            session.update(updated)
            try { tokenStore?.save(updated) } catch (_: Exception) {}
            fresh.token
        } catch (e: pro.aduki.core.errors.AdukiException.Auth) {
            // A 2xx that failed validation spent the refresh token: keep the
            // rotated one if it came back, else drop it (reuse revokes the session).
            session.update(current.copy(refresh = e.refresh))
            if (e.refresh.isNotBlank()) {
                try { tokenStore?.save(current.copy(refresh = e.refresh)) } catch (_: Exception) {}
            } else {
                try { tokenStore?.clear() } catch (_: Exception) {}
            }
            null
        } catch (e: pro.aduki.core.errors.AdukiException.Unauthorized) {
            if (tokenStore != null) {
                val stored = try { tokenStore.load() } catch (_: Exception) { null }
                if (stored != null && stored.refresh.isNotBlank() && stored.refresh != presented) {
                    val adopted = current.copy(
                        token = if (stored.token.isNotBlank()) stored.token else current.token,
                        refresh = stored.refresh,
                        expires = if (stored.expires.isNotBlank()) stored.expires else current.expires,
                        session = if (stored.session.isNotBlank()) stored.session else current.session
                    )
                    session.update(adopted)
                    align(adopted)
                    if (stale != null && adopted.token.isNotBlank() && adopted.token != stale) {
                        return adopted.token
                    }
                    try {
                        val fresh = id.rotate(Endpoints.AUDIENCE, adopted.token)
                        val updated = fresh.copy(session = adopted.session)
                        session.update(updated)
                        try { tokenStore.save(updated) } catch (_: Exception) {}
                        return fresh.token
                    } catch (e2: pro.aduki.core.errors.AdukiException.Unauthorized) {
                        session.update(adopted.copy(refresh = ""))
                        try { tokenStore.clear() } catch (_: Exception) {}
                        return null
                    } catch (_: Exception) {
                        return null
                    }
                }
                session.update(current.copy(refresh = ""))
                try { tokenStore.clear() } catch (_: Exception) {}
            } else {
                session.update(current.copy(refresh = ""))
            }
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

    /**
     * Typed REST mail client on this client's authenticated connection.
     * Build `HttpMailboxTransport(mailApi)` and `HttpDispatcher(mailApi, …)`
     * from it for the sync engine and the outbox worker.
     */
    val mailApi: NetMail by lazy { NetMail(activeHttpClient(), options.endpoint) }

    /**
     * JMAP client of the mail host (`{endpoint without /v1}/jmap`) on this
     * client's authenticated connection: message bodies, attachments, rich
     * send and the account settings that have no REST route.
     */
    val jmap: Jmap by lazy { Jmap(activeHttpClient(), host()) }

    // The mail host root: the REST endpoint without its `/v1`.
    private fun host(): String = options.endpoint.removeSuffix("/").removeSuffix("/v1")

    /** Typed REST scheduling client on this client's authenticated connection. */
    val schedulingApi: NetScheduling by lazy { NetScheduling(activeHttpClient(), options.endpoint) }

    // Each part is the one supplied to the builder, else built on [store]
    // over this client's own HTTP clients (declared above: property
    // initializers run in order).
    private val outbox: Manager? = manager ?: store?.let(::Manager)

    // Contact writes: applied to the store, journaled in the outbox.
    private val book: Book? = store?.let { s -> outbox?.let { Book(s, it) } }

    private val scheduleSync: ScheduleEngine? = scheduleEngine
        ?: store?.let { s -> outbox?.let { ScheduleEngine(s, schedulingApi, it) } }

    // Outbox dispatchers after mail's, in order: each sends its own actions
    // and hands anything else to the next.
    private val dispatchers: List<(Dispatcher?) -> Dispatcher?> = listOf(
        { next -> ContactDispatcher(contactsApi, { old, row -> book?.created(old, row) }, next) },
        { next -> scheduleSync?.let { ScheduleDispatcher(schedulingApi, it, next) } ?: next }
    )

    private val outboxWorker: Worker? = worker ?: store?.let {
        outbox?.let { queue ->
            val fallback = dispatchers.foldRight(null as Dispatcher?) { make, next -> make(next) }
            Worker(
                queue,
                HttpDispatcher(mailApi, queue::message, queue::rename, fallback, jmap = jmap, adopt = queue::adopt),
                onRejected = rejected
            )
        }
    }

    // An explicit engine wins; with only a storage the engine reads the
    // address book over REST (`HttpContactTransport`).
    private val contactSync: ContactEngine? = contactEngine
        ?: (contactStorage ?: store?.let(ContactEngine::storage))?.let { ContactEngine(it, HttpContactTransport(contactsApi)) }

    private val mailboxSync: MailboxEngine? = mailboxEngine
        ?: store?.let { MailboxEngine(it, HttpMailboxTransport(mailApi)) }

    private val bodies: ContentEngine? = store?.let { ContentEngine(it, JmapContentTransport(jmap)) }

    val mail = Mail(this, outbox, mailRepo ?: store?.let { MailRepo(it, outbox, scope) }, outboxWorker, bodies)
    val contacts = Contacts(this, contactRepo ?: store?.let { ContactRepo(it, scope) }, contactSync, book, outboxWorker)
    val sync = Sync(this, mailboxSync, contactSync, outboxWorker, outbox)
    val scheduling = Scheduling(
        this,
        appointmentRepo ?: store?.let { AppointmentRepo(it, scope) },
        scheduleSync,
        schedulingApi,
        outboxWorker
    )
    /** Filter rules, vacation reply, senders and new-mail push, kept on the server. */
    val settings = Settings(this)

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
    fun watchRights(host: String = host()) {
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
            try { tokenStore?.clear() } catch (_: Exception) {}
            return false
        }
        align(current)
        val revoked = id.signOut()
        if (id.signedIn()) {
            // Still signed in remotely: keep the retry credential.
            val retained = current.copy(refresh = id.refreshToken())
            session.update(retained)
            try { tokenStore?.save(retained) } catch (_: Exception) {}
        } else {
            session.clear()
            try { tokenStore?.clear() } catch (_: Exception) {}
        }
        revoked
    }

    /**
     * Closes the local database if this client opened it (`Builder.store(dir)`).
     * A store passed in as a `BoxStore` belongs to the caller and stays open.
     */
    fun close() {
        unwatchRights()
        if (owned) store?.close()
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
        private var store: BoxStore? = null
        private var directory: File? = null
        private var rejected: (Outbox, Rejected) -> Unit = { _, _ -> }
        private var tokenStore: TokenStore? = null

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
        /**
         * Keeps mail, contacts and scheduling in [store] and builds everything
         * that needs it on the client's own HTTP clients: the outbox manager and
         * worker, the repositories and the sync engines. A part passed to the
         * builder as well ([manager], [worker], [mail], [engines], ...) wins.
         * The store stays the caller's: [Aduki.close] does not close it.
         */
        fun store(store: BoxStore) = apply {
            this.store = store
            this.directory = null
        }

        /**
         * Like `store(BoxStore)`, with the database opened by [build] in [dir]
         * (`Factory.build`, after the vault is installed). [Aduki.close] closes it.
         */
        fun store(dir: File) = apply {
            this.directory = dir
            this.store = null
        }

        /** Told about each outbox action the server refused for good (e.g. an invalid recipient). */
        fun rejected(callback: (Outbox, Rejected) -> Unit) = apply { this.rejected = callback }

        /**
         * Persists and reloads tokens across processes or in external
         * secure persistence (e.g. Android AccountManager).
         */
        fun tokens(store: TokenStore) = apply { this.tokenStore = store }

        fun engines(mailbox: MailboxEngine, contact: ContactEngine) = apply {
            this.mailboxEngine = mailbox
            this.contactEngine = contact
        }

        fun build(): Aduki {
            val storedTokens = tokenStore?.load()
            val activeToken = if (token.isNotBlank()) token else (storedTokens?.token.orEmpty())
            require(apiKey.isNotBlank() || activeToken.isNotBlank() || (tokenStore != null && storedTokens?.refresh?.isNotBlank() == true)) {
                "Either API key or JWT token must not be blank"
            }
            (secureStore?.let { pro.aduki.crypto.cipher.Vault(it) } ?: pro.aduki.crypto.cipher.Vault.platform())
                ?.let { pro.aduki.store.box.Sealing.install(it) }
            val local = store ?: directory?.let { Factory.build(it) }
            val options = Options(
                endpoint = endpoint,
                identity = identity,
                timeoutSeconds = timeoutSeconds,
                secure = secure,
                pins = pins
            )
            return Aduki(
                apiKey = apiKey,
                token = activeToken,
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
                contactStorage = contactStorage,
                store = local,
                owned = store == null && local != null,
                rejected = rejected,
                tokenStore = tokenStore
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
            pins: List<Pin> = emptyList(),
            tokenStore: TokenStore? = null
        ): Aduki {
            val tempClient = HttpClient.create("", 15, pro.aduki.crypto.tls.Pinning.pinner(pins)).let { base ->
                if (dpop == null) base else base.newBuilder().addInterceptor(Dpop(dpop)).build()
            }
            val tokens = Login.submit(tempClient, identity, handle, password, code, backup)
            try { tokenStore?.save(tokens) } catch (_: Exception) {}
            val client = builder()
                .apply { if (dpop != null) dpop(dpop) }
                .apply { if (tokenStore != null) tokens(tokenStore) }
                .endpoint(endpoint)
                .identity(identity)
                .pins(pins)
                .token(tokens.token)
                .build()

            client.session.update(tokens)
            client.me() // Eagerly resolve identity
            return client
        }

        /**
         * Discovers personal account signup requirements from Aduki ID.
         */
        suspend fun requirements(
            identity: String = Endpoints.ID,
            pins: List<Pin> = emptyList()
        ): Requirements {
            val tempClient = HttpClient.create("", 15, pro.aduki.crypto.tls.Pinning.pinner(pins))
            return Signup.requirements(tempClient, identity)
        }

        /**
         * Stage 1: Initiates personal account registration.
         * The account remains pending until confirmed by [verify].
         */
        suspend fun signUp(
            handle: String,
            name: String,
            password: String,
            phone: String? = null,
            identity: String = Endpoints.ID,
            pins: List<Pin> = emptyList()
        ): Enrolment {
            val tempClient = HttpClient.create("", 15, pro.aduki.crypto.tls.Pinning.pinner(pins))
            return Signup.create(tempClient, identity, handle, name, password, phone)
        }

        /**
         * Stage 2: Confirms the authenticator code and returns backup codes.
         */
        suspend fun verify(
            hex: String,
            password: String,
            code: String,
            identity: String = Endpoints.ID,
            pins: List<Pin> = emptyList()
        ): List<String> {
            val tempClient = HttpClient.create("", 15, pro.aduki.crypto.tls.Pinning.pinner(pins))
            return Signup.verify(tempClient, identity, hex, password, code)
        }

        /**
         * Complete staged registration: confirms the authenticator code, activates the account,
         * logs in, and returns the authenticated Aduki client alongside the one-time backup codes.
         */
        suspend fun register(
            handle: String,
            name: String,
            password: String,
            code: String,
            hex: String,
            endpoint: String = Endpoints.REST,
            identity: String = Endpoints.ID,
            backup: String? = null,
            dpop: pro.aduki.crypto.dpop.Key? = null,
            pins: List<Pin> = emptyList(),
            tokenStore: TokenStore? = null
        ): Pair<Aduki, List<String>> {
            val codes = verify(hex, password, code, identity, pins)
            val client = login(handle, password, code, endpoint, identity, backup, dpop, pins, tokenStore)
            return Pair(client, codes)
        }
    }
}

