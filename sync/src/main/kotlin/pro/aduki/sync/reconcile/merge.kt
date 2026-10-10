package pro.aduki.sync.reconcile

import pro.aduki.store.entities.Message

/**
 * Reconcile merges server message updates with local dirty states.
 */
object Reconcile {

    /**
     * Merges server flags with local state:
     * If the message is locally marked dirty, local flags are preserved until pending outbox commits.
     * Otherwise, server flags take precedence.
     */
    fun flags(local: Message, server: Int): Int {
        return if (local.dirty) {
            local.flags
        } else {
            server
        }
    }

    /**
     * Backward-compatible alias for flags.
     */
    fun mergeFlags(local: Message, serverFlags: Int): Int = flags(local, serverFlags)

    /**
     * Merges mailbox placement:
     * If locally dirty (e.g. moved locally), preserve local mailbox.
     */
    fun mailbox(local: Message, server: String): String {
        return if (local.dirty) {
            local.mailbox
        } else {
            server
        }
    }

    /**
     * Merges contact changes:
     * If local contact exists and was updated more recently than server, preserve local.
     */
    fun contact(local: pro.aduki.store.entities.Contact?, server: pro.aduki.store.entities.Contact): pro.aduki.store.entities.Contact {
        if (local == null) return server
        return if (local.updated > server.updated) {
            local
        } else {
            server.copy(id = local.id)
        }
    }
}

