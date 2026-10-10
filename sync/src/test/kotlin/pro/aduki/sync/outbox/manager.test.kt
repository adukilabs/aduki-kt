package pro.aduki.sync.outbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox

class ManagerTest {

    private class TestStorage : Storage {
        val messages = mutableMapOf<String, Message>()
        val outbox = mutableMapOf<Long, Outbox>()
        private var nextId = 1L

        override fun getMessage(hex: String): Message? = messages[hex]

        override fun putMessage(msg: Message) {
            messages[msg.hex] = msg
        }

        override fun getOutbox(id: Long): Outbox? = outbox[id]

        override fun putOutbox(entry: Outbox): Long {
            val id = if (entry.id == 0L) nextId++ else entry.id
            val saved = entry.copy(id = id)
            outbox[id] = saved
            return id
        }

        override fun removeOutbox(id: Long) {
            outbox.remove(id)
        }

        override fun pending(): List<Outbox> = outbox.values.sortedBy { it.created }

        override fun <T> tx(block: () -> T): T {
            // Transaction snapshot rollback simulation
            val msgSnapshot = messages.mapValues { it.value.copy() }.toMutableMap()
            val outboxSnapshot = outbox.mapValues { it.value.copy() }.toMutableMap()
            return try {
                block()
            } catch (e: Throwable) {
                messages.clear()
                messages.putAll(msgSnapshot)
                outbox.clear()
                outbox.putAll(outboxSnapshot)
                throw e
            }
        }
    }

    private lateinit var storage: TestStorage
    private lateinit var manager: Manager

    @Before
    fun setup() {
        storage = TestStorage()
        manager = Manager(storage)
    }

    @Test
    fun testOptimisticFlagToggle() {
        val msg = Message(hex = "msg_1", mailbox = "inbox", flags = 0, dirty = false)
        storage.putMessage(msg)

        val action = manager.flag("msg_1", Message.FLAGGED)
        assertEquals("flag", action.action)

        val updated = storage.getMessage("msg_1")
        assertNotNull(updated)
        assertTrue(updated!!.flagged())
        assertTrue(updated.dirty)

        val pending = manager.pending()
        assertEquals(1, pending.size)
        assertEquals(action.id, pending[0].id)
    }

    @Test
    fun testOptimisticMove() {
        val msg = Message(hex = "msg_2", mailbox = "inbox", dirty = false)
        storage.putMessage(msg)

        val action = manager.move("msg_2", "archive")
        assertEquals("move", action.action)

        val updated = storage.getMessage("msg_2")
        assertNotNull(updated)
        assertEquals("archive", updated!!.mailbox)
        assertTrue(updated.dirty)
    }

    @Test
    fun testCompleteClearsDirty() {
        val msg = Message(hex = "msg_3", mailbox = "inbox", flags = 0, dirty = false)
        storage.putMessage(msg)

        val action = manager.flag("msg_3", Message.SEEN)
        assertTrue(storage.getMessage("msg_3")!!.dirty)

        manager.complete(action.id, "msg_3")
        assertFalse(storage.getMessage("msg_3")!!.dirty)
        assertTrue(manager.pending().isEmpty())
    }

    @Test
    fun testFailIncrementsAttempts() {
        val action = manager.enqueue("send", byteArrayOf(1, 2, 3))
        assertEquals(0, action.attempts)

        manager.fail(action.id, 5000L)
        val pending = manager.pending()
        assertEquals(1, pending.size)
        assertEquals(1, pending[0].attempts)
        assertTrue(pending[0].nextRetry > System.currentTimeMillis())
    }

    @Test(expected = IllegalStateException::class)
    fun testTxRollbackOnFailure() {
        val msg = Message(hex = "msg_err", mailbox = "inbox", dirty = false)
        storage.putMessage(msg)

        storage.tx {
            manager.flag("msg_err", Message.SEEN)
            throw IllegalStateException("Simulated DB commit error")
        }
    }
}

