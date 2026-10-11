package pro.aduki.android

import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider

/** A Vault on the JVM software fallback (Robolectric has no AndroidKeyStore): tests the sealing paths, not the Keystore. */
fun softVault(): Vault = Vault(Provider("software-test"), "aduki_test_${System.nanoTime()}_")
