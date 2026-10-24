package pro.aduki.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import pro.aduki.android.account.Accounts
import pro.aduki.android.account.SwitcherState
import pro.aduki.android.keystore.AndroidKey
import pro.aduki.android.passkey.CredentialManagerAuthenticator
import pro.aduki.android.passkey.PasskeyFlow
import pro.aduki.android.signin.PendingStore
import pro.aduki.android.signin.SignIn
import pro.aduki.android.signin.SignInConfig
import pro.aduki.core.models.Linked
import pro.aduki.core.models.Tokens
import pro.aduki.crypto.cipher.Vault
import pro.aduki.net.http.Dpop
import pro.aduki.net.http.Id
import pro.aduki.net.http.Oidc
import pro.aduki.net.http.Space
import pro.aduki.core.config.Endpoints

/**
 * The manual-test screen (see CONTRIBUTING.md): every button runs one check against the real Aduki ID and
 * prints what happened (no tokens, no keys: only ids, handles, statuses and durations).
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var log: TextView
    private lateinit var accounts: Accounts
    private val identity get() = BuildConfig.ISSUER.trimEnd('/') + "/v1"
    private val redirect get() = BuildConfig.SCHEME + ":/callback"
    private val key by lazy { AndroidKey.open() }
    private val http = OkHttpClient()
    private var activeHex: String? = null
    private val totp by lazy { android.widget.EditText(this).apply { hint = "authenticator code"; inputType = android.text.InputType.TYPE_CLASS_NUMBER } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = Vault.platform()
        accounts = Accounts(this, vault)
        SignInConfig.flow = SignIn(Oidc(http, BuildConfig.ISSUER.trimEnd('/'), BuildConfig.CLIENT, redirect, dpop = Dpop(key)), redirect, PendingStore(this, vault))
        SignInConfig.done = { result -> runOnUiThread { signedIn(result) } }

        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            add("1 Key info (Keystore level, thumbprint)") { keyInfo() }
            add("2 Sign in with Aduki (Custom Tabs)") { SignInConfig.flow!!.let { f -> Thread { run("start sign-in") { f.start(this@MainActivity); "tab opened" } }.start() } }
            add("3 Accounts and switcher") { switcher() }
            add("4 Refresh center from the server") { refreshCenter() }
            add("5 Passkey: register") { passkey("register") }
            add("6 Passkey: step-up") { passkey("stepup") }
            add("7 Passkey: sign in") { passkey("signin") }
            add("8 DPoP call to Space (GET settings)") { space() }
            // ES256 center unlock: register a device credential, then unlock with it
            addView(totp)
            add("9 Device key: register ES256 credential (type a fresh authenticator code above)") { deviceRegister() }
            add("10 Device key: unlock with it (challenge, sign, unlock)") { deviceUnlock() }
            addView(log)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        println("sample ready issuer=${BuildConfig.ISSUER} scheme=${BuildConfig.SCHEME}")
    }

    private fun LinearLayout.add(label: String, action: () -> Unit) =
        addView(Button(this@MainActivity).apply { text = label; isAllCaps = false; setOnClickListener { action() } })

    private fun say(text: String) = runOnUiThread { log.append(text + "\n") }

    private fun run(what: String, block: () -> String) {
        val t = System.nanoTime()
        val out = try { block() } catch (e: Throwable) { "FAILED ${e.javaClass.simpleName}: ${e.message}" }
        say("$what: $out (${(System.nanoTime() - t) / 1_000_000} ms)")
    }

    private fun keyInfo() = scope.launch {
        run("key") {
            val p = key.protection()
            "alias=${key.alias} alg=${key.alg} level=${p.level} hardware=${p.hardware} extractable=${key.extractable()} jkt=${key.thumbprint()}"
        }
    }

    private fun signedIn(result: Result<Oidc.Grant>) {
        result.onFailure { say("sign-in FAILED ${it.javaClass.simpleName}: ${it.message}"); return }
        val grant = result.getOrThrow()
        val claims = grant.claims
        val hex = claims?.optString("sub").orEmpty()
        val handle = claims?.optString("preferred_username").orEmpty().ifBlank { claims?.optString("email").orEmpty() }.ifBlank { hex }
        accounts.add(Linked(hex, handle, personal = true, tenant = null, unlock = "center"), Tokens(grant.access, grant.refresh.orEmpty(), grant.expires.toString(), ""))
        activeHex = hex
        say("signed in as $handle (sub=$hex), scope=${grant.scope}, refresh=${!grant.refresh.isNullOrEmpty()}")
    }

    private fun switcher() = scope.launch {
        run("switcher") {
            val state = SwitcherState.of(accounts, activeHex)
            if (state.rows.isEmpty()) "no accounts" else state.rows.joinToString("\n  ", "\n  ") {
                "${it.handle} ${if (it.active) "[active] " else ""}${if (it.locked) "[lock] " else ""}${if (it.signInAgain) "[sign in again] " else ""}${if (it.instant) "[instant]" else ""}"
            }
        }
    }

    private fun refreshCenter() = scope.launch {
        run("center") {
            val current = activeHex ?: accounts.list().firstOrNull()?.hex ?: return@run "no account"
            val bearer = accounts.tokens(current)?.token ?: return@run "no token"
            val change = accounts.refresh(http, identity, bearer, current)
            "added=${change.added} removed=${change.removed} updated=${change.updated}"
        }
    }

    private fun passkey(which: String) = scope.launch {
        run("passkey $which") {
            val flow = PasskeyFlow(http, identity, CredentialManagerAuthenticator(this@MainActivity))
            val current = activeHex ?: accounts.list().firstOrNull()?.hex
            val bearer = current?.let { accounts.tokens(it)?.token }
            kotlinx.coroutines.runBlocking {
                when (which) {
                    "register" -> flow.register(bearer ?: error("sign in first")).let { "registered passkey=${it.passkey} synced=${it.synced}" }
                    "stepup" -> "stepped=${flow.stepUp(bearer ?: error("sign in first"))}"
                    else -> flow.signIn().let { "signed in, session=${it.session}" }
                }
            }
        }
    }

    private fun space() = scope.launch {
        run("space") {
            val current = activeHex ?: accounts.list().firstOrNull()?.hex ?: return@run "no account"
            val tokens = accounts.tokens(current) ?: return@run "no tokens"
            val dpop = Dpop(key)
            val client = http.newBuilder().addInterceptor(dpop).build()
            val id = Id(client, identity)
            id.adopt(tokens)
            val reply = Space(client, id).settings()
            "HTTP ${reply.status}, bound jkt=${dpop.thumbprint()}"
        }
    }

    private fun deviceRegister() {
        val code = totp.text.toString()
        scope.launch {
            run("device register") {
                val current = activeHex ?: accounts.list().firstOrNull()?.hex ?: return@run "no account"
                val bearer = accounts.tokens(current)?.token ?: return@run "no token"
                val device = AndroidKey.open(AndroidKey.DEVICE)
                val credential = accounts.registerDevice(http, identity, bearer, android.os.Build.MODEL, code, center = true, key = device)
                "credential=$credential alg=${device.alg} point=${device.devicePublic().size} bytes level=${device.protection().level}"
            }
        }
    }

    private fun deviceUnlock() = scope.launch {
        run("device unlock") {
            val opened = accounts.unlockWithDevice(http, identity)
            "unlocked=$opened"
        }
    }
}
