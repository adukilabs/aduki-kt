package pro.aduki.android.account

import android.content.Context
import pro.aduki.crypto.cipher.Vault

/**
 * CenterStore keeps this device's credential id (see `Accounts.registerDevice`)
 * in a private preferences file. [clear] also removes a center token an older
 * SDK kept there.
 */
class CenterStore(context: Context, @Suppress("UNUSED_PARAMETER") vault: Vault? = null) {
    private val prefs = context.applicationContext.getSharedPreferences("pro.aduki.center", Context.MODE_PRIVATE)

    /** Keeps the id of this device's credential (ES256 or Ed25519, see `Accounts.registerDevice`); it is an identifier, not a secret. */
    fun saveCredential(credential: String) = prefs.edit().putString("credential", credential).apply()

    /** The device credential id last registered, or null. */
    fun credential(): String? = prefs.getString("credential", null)

    fun clearCredential() = prefs.edit().remove("credential").apply()

    /** Forgets everything, the device credential included (sign-out of the device). */
    fun clear() = prefs.edit().clear().apply()
}
