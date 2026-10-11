package pro.aduki.android.passkey

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import pro.aduki.core.models.Tokens
import pro.aduki.net.http.Passkeys

/** What the platform's passkey step did instead of returning a credential. */
sealed class PasskeyFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The person dismissed the sheet. Not an error to show. */
    class Cancelled(cause: Throwable? = null) : PasskeyFailure("Passkey request cancelled", cause)

    /** Sign-in or step-up found no passkey for this site on the device or the provider. */
    class NoPasskey(cause: Throwable? = null) : PasskeyFailure("No passkey available", cause)

    /** Anything else the platform refused (no provider, domain not linked to the app, unsupported OS). */
    class Platform(val kind: String, cause: Throwable? = null) : PasskeyFailure("Passkey platform error: $kind", cause)
}

/**
 * The authenticator step of a WebAuthn ceremony: takes the options JSON Aduki ID
 * returned, returns the credential JSON the platform produced. The real one is
 * [CredentialManagerAuthenticator]; tests use a fake.
 */
interface PlatformAuthenticator {
    /** Creates a passkey: [options] is `PublicKeyCredentialCreationOptions` JSON, the result `registrationResponseJson`. */
    suspend fun create(options: String): String

    /** Asserts with a passkey: [options] is `PublicKeyCredentialRequestOptions` JSON, the result `authenticationResponseJson`. */
    suspend fun get(options: String): String
}

/**
 * Credential Manager (K6): the sheet is shown by the OS over [context], which
 * must be an Activity for the UI to attach. Jetpack's library uses the platform
 * service on Android 14 and later and Google Play services before it.
 *
 * Needs a real device with a credential provider; there is no headless
 * verification of this class (see ANDROID-TESTS.md). Request shaping and the
 * mapping of failures are tested.
 */
class CredentialManagerAuthenticator(
    private val context: Context,
    private val manager: CredentialManager = CredentialManager.create(context)
) : PlatformAuthenticator {

    override suspend fun create(options: String): String = try {
        val response = manager.createCredential(context, request(options))
        (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyFailure.Platform("unexpected-response")
    } catch (e: CreateCredentialException) {
        throw map(e)
    }

    override suspend fun get(options: String): String = try {
        val result = manager.getCredential(context, assertion(options))
        (result.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw PasskeyFailure.Platform("unexpected-credential")
    } catch (e: GetCredentialException) {
        throw map(e)
    }

    companion object {
        /** The registration request for Aduki ID's options JSON; origin checks stay with the platform. */
        fun request(options: String) = CreatePublicKeyCredentialRequest(options)

        /** The sign-in request: one public-key option, no password or federated options. */
        fun assertion(options: String) = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(options)))

        fun map(e: CreateCredentialException): PasskeyFailure = when (e) {
            is CreateCredentialCancellationException -> PasskeyFailure.Cancelled(e)
            else -> PasskeyFailure.Platform(e.type, e)
        }

        fun map(e: GetCredentialException): PasskeyFailure = when (e) {
            is GetCredentialCancellationException -> PasskeyFailure.Cancelled(e)
            is NoCredentialException -> PasskeyFailure.NoPasskey(e)
            else -> PasskeyFailure.Platform(e.type, e)
        }
    }
}

/**
 * The three ceremonies glued to Aduki ID: begin (blocking [Passkeys] call on
 * the IO dispatcher), the platform step, finish. [identity] is the Aduki ID
 * base, e.g. `https://id.aduki.pro/v1`.
 */
class PasskeyFlow(
    private val client: OkHttpClient,
    private val identity: String,
    private val authenticator: PlatformAuthenticator
) {
    /** Registers a passkey (needs T3: a recent step-up or a fresh authenticator [code]). */
    suspend fun register(bearer: String, label: String? = null, code: String? = null): Passkeys.Registered {
        val challenge = io { Passkeys.beginRegistration(client, identity, bearer, label, code) }
        val credential = authenticator.create(challenge.optionsJson)
        return io { Passkeys.finishRegistration(client, identity, bearer, challenge, credential) }
    }

    /** Steps up with a passkey; returns the Unix seconds of the step-up. Renew the access token afterwards. */
    suspend fun stepUp(bearer: String): Long {
        val challenge = io { Passkeys.beginStepUp(client, identity, bearer) }
        val credential = authenticator.get(challenge.optionsJson)
        return io { Passkeys.finishStepUp(client, identity, bearer, challenge, credential) }
    }

    /** Passkey-first sign-in: no account named; the assertion is password and second factor at once. */
    suspend fun signIn(audience: String = pro.aduki.core.config.Endpoints.AUDIENCE, device: String? = null): Tokens {
        val challenge = io { Passkeys.beginSignIn(client, identity) }
        val credential = authenticator.get(challenge.optionsJson)
        return io { Passkeys.finishSignIn(client, identity, challenge, credential, audience, device) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
