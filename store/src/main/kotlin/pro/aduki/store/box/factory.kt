package pro.aduki.store.box

import io.objectbox.BoxStore
import io.objectbox.BoxStoreBuilder
import pro.aduki.crypto.cipher.Vault
import pro.aduki.store.entities.MyObjectBox
import java.io.File

/**
 * Factory for configuring BoxStore instances.
 *
 * The database file as a whole is NOT encrypted (ObjectBox 4.0.3 has no
 * encryption option). Sensitive payload columns are sealed with a [Vault];
 * see [Sealing] for exactly which, and `guide/security.md` section 4.
 */
object Factory {

    /**
     * Builds BoxStore configuration targeting the specified directory. A
     * [vault] is installed process-wide for the sealed columns; null keeps the
     * one already installed (or none).
     */
    fun create(dir: File, vault: Vault? = null): BoxStoreBuilder {
        if (vault != null) Sealing.install(vault)
        return MyObjectBox.builder().directory(dir)
    }

    /**
     * Builds and opens a BoxStore targeting the specified directory.
     */
    fun build(dir: File, vault: Vault? = null): BoxStore {
        return create(dir, vault).build()
    }
}
