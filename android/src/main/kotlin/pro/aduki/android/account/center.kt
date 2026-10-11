package pro.aduki.android.account

import android.content.Context
import pro.aduki.crypto.cipher.Vault

/**
 * CenterStore keeps the Account Center token (ADK-AUTH-002 section 7.2) in a
 * private preferences file, sealed with the [Vault] when there is one. The token
 * is for Aduki ID only; it is never written to an AccountManager entry.
 */
class CenterStore(context: Context, private val vault: Vault?) {
    private val prefs = context.applicationContext.getSharedPreferences("pro.aduki.center", Context.MODE_PRIVATE)

    fun save(center: String, token: String, expires: Long) {
        prefs.edit()
            .putString("center", center)
            .putString("token", vault?.sealText(token) ?: token)
            .putLong("expires", expires)
            .apply()
    }

    /** The center id last stored, or null. */
    fun id(): String? = prefs.getString("center", null)

    /** The token while it has not expired ([now] and `expires` are Unix seconds). */
    fun token(now: Long): String? {
        val stored = prefs.getString("token", null) ?: return null
        if (prefs.getLong("expires", 0) in 1..now) return null
        return vault?.openText(stored) ?: stored
    }

    /** Keeps the id of this device's credential (ES256 or Ed25519, see `Accounts.registerDevice`); it is an identifier, not a secret. */
    fun saveCredential(credential: String) = prefs.edit().putString("credential", credential).apply()

    /** The device credential id last registered, or null. */
    fun credential(): String? = prefs.getString("credential", null)

    fun clearCredential() = prefs.edit().remove("credential").apply()

    /** Forgets the center and its token but keeps the device credential (the account left its center, the device did not). */
    fun clearCenter() = prefs.edit().remove("center").remove("token").remove("expires").apply()

    /** Forgets everything, the device credential included (sign-out of the device). */
    fun clear() = prefs.edit().clear().apply()
}
