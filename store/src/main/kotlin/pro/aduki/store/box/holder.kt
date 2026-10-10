package pro.aduki.store.box

import io.objectbox.Box
import io.objectbox.BoxStore
import java.io.Closeable

/**
 * Thread-safe holder and lifecycle manager for the active BoxStore instance.
 */
class Holder(val store: BoxStore) : Closeable {

    fun <T> box(entityClass: Class<T>): Box<T> {
        return store.boxFor(entityClass)
    }

    override fun close() {
        if (!store.isClosed) {
            store.close()
        }
    }

    fun closed(): Boolean = store.isClosed
}

