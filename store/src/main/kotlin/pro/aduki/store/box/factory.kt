package pro.aduki.store.box

import io.objectbox.BoxStore
import io.objectbox.BoxStoreBuilder
import pro.aduki.store.entities.MyObjectBox
import java.io.File

/**
 * Factory for configuring BoxStore instances.
 */
object Factory {

    /**
     * Builds BoxStore configuration targeting specified directory and optional encryption key.
     */
    @Suppress("UNUSED_PARAMETER")
    fun create(dir: File, key: ByteArray? = null): BoxStoreBuilder {
        return MyObjectBox.builder().directory(dir)
    }

    /**
     * Builds and opens a BoxStore targeting specified directory.
     */
    fun build(dir: File, key: ByteArray? = null): BoxStore {
        return create(dir, key).build()
    }
}
