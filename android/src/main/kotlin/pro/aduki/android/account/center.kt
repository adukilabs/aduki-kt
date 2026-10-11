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

    fun clear() = prefs.edit().clear().apply()
}
