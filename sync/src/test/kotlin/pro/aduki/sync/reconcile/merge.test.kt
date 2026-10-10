package pro.aduki.sync.reconcile

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.aduki.store.entities.Message

class MergeTest {

    @Test
    fun testServerOverride() {
        val local = Message(
            hex = "msg_1",
            flags = Message.SEEN,
            dirty = false
        )
        val serverFlags = Message.SEEN or Message.FLAGGED

        val merged = Reconcile.flags(local, serverFlags)
        assertEquals(serverFlags, merged)
    }

    @Test
    fun testLocalPreserve() {
        val local = Message(
            hex = "msg_2",
            flags = Message.FLAGGED,
            dirty = true
        )
        val serverFlags = 0

        val merged = Reconcile.flags(local, serverFlags)
        assertEquals(Message.FLAGGED, merged)
    }

    @Test
    fun testMailboxPreserveWhenDirty() {
        val local = Message(hex = "msg_3", mailbox = "trash_box", dirty = true)
        val server = "inbox"
        val merged = Reconcile.mailbox(local, server)
        assertEquals("trash_box", merged)
    }

    @Test
    fun testMailboxUpdateWhenClean() {
        val local = Message(hex = "msg_4", mailbox = "inbox", dirty = false)
        val server = "archive"
        val merged = Reconcile.mailbox(local, server)
        assertEquals("archive", merged)
    }

    @Test
    fun testContactPreserveNewer() {
        val local = pro.aduki.store.entities.Contact(hex = "c1", name = "Local Alice", updated = 2000L)
        val server = pro.aduki.store.entities.Contact(hex = "c1", name = "Server Alice", updated = 1000L)
        val merged = Reconcile.contact(local, server)
        assertEquals("Local Alice", merged.name)
    }

    @Test
    fun testContactApplyNewerServer() {
        val local = pro.aduki.store.entities.Contact(id = 42L, hex = "c2", name = "Old Bob", updated = 1000L)
        val server = pro.aduki.store.entities.Contact(id = 0L, hex = "c2", name = "New Bob", updated = 3000L)
        val merged = Reconcile.contact(local, server)
        assertEquals("New Bob", merged.name)
        assertEquals(42L, merged.id)
    }
}

