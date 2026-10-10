package pro.aduki.store.queries

import io.objectbox.BoxStore

/**
 * Batch provides atomic ACID transaction execution over ObjectBox.
 */
class Batch(private val store: BoxStore) {

    fun <T> tx(block: () -> T): T {
        return store.callInTx(block)
    }

    fun run(block: Runnable) {
        store.runInTx(block)
    }
}
