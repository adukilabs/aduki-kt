package pro.aduki.store.box

import io.objectbox.BoxStore
import io.objectbox.BoxStoreBuilder
import pro.aduki.store.entities.MyObjectBox
import java.io.File

/**
 * Factory for configuring BoxStore instances.
 *
 * The database is NOT encrypted: the ObjectBox 4.0.3 artifacts have no
 * encryption option (see `guide/security.md` section 4 for the findings and
 * the design that would add it). An earlier `key` parameter was ignored and
 * has been removed so that no caller believes it protects anything.
 */
object Factory {

    /**
     * Builds BoxStore configuration targeting the specified directory.
     */
    fun create(dir: File): BoxStoreBuilder {
        return MyObjectBox.builder().directory(dir)
    }

    /**
     * Builds and opens a BoxStore targeting the specified directory.
     */
    fun build(dir: File): BoxStore {
        return create(dir).build()
    }
}
