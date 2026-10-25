package pro.aduki.sync.outbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.core.retry.Jitter
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox

class WorkerTest {

    private class TestStorage : Storage {
        val messages = mutableMapOf<String, Message>()
        val outbox = mutableMapOf<Long, Outbox>()
        private var nextId = 1L

        override fun getMessage(hex: String): Message? = messages[hex]
        override fun putMessage(msg: Message) { messages[msg.hex] = msg }
        override fun getOutbox(id: Long): Outbox? = outbox[id]
        override fun putOutbox(entry: Outbox): Long {
            val id = if (entry.id == 0L) nextId++ else entry.id
            outbox[id] = entry.copy(id = id)
            return id
        }
        override fun removeOutbox(id: Long) { outbox.remove(id) }
        override fun pending(): List<Outbox> = outbox.values.filter { it.pending() }.sortedBy { it.created }
        override fun failed(): List<Outbox> = outbox.values.filter { it.failed() }.sortedBy { it.created }
        override fun size(): Int = pending().size
        override fun <T> tx(block: () -> T): T = block()
    }

    private lateinit var storage: TestStorage
    private lateinit var manager: Manager

    @Before
    fun setup() {
        storage = TestStorage()
        manager = Manager(storage)
    }

    @Test
    fun testSuccessfulDrain() = runBlocking {
        val dispatched = mutableListOf<String>()
        val worker = Worker(manager, Dispatcher { action ->
            dispatched.add(action.action)
        })

        manager.enqueue("flag", "msg_1:4".toByteArray(Charsets.UTF_8))
        manager.enqueue("move", "msg_2:archive".toByteArray(Charsets.UTF_8))

        val count = worker.drain()
        assertEquals(2, count)
        assertEquals(listOf("flag", "move"), dispatched)
        assertTrue(manager.pending().isEmpty())
    }

    @Test
    fun testFailureBackoffAndSequentialHalt() = runBlocking {
        var failFirst = true
        val worker = Worker(
            manager = manager,
            dispatcher = Dispatcher { action ->
                if (failFirst && action.action == "flag") {
                    throw RuntimeException("Network 500 error")
                }
            },
            jitter = Jitter(base = 100, max = 500)
        )

        manager.enqueue("flag", "msg_fail:4".toByteArray(Charsets.UTF_8))
        manager.enqueue("move", "msg_second:archive".toByteArray(Charsets.UTF_8))

        // First attempt fails on action 1, does not process action 2
        val count = worker.drain()
        assertEquals(0, count)

        val pending = manager.pending()
        assertEquals(2, pending.size)
        assertEquals(1, pending[0].attempts)
        assertTrue(pending[0].nextRetry > System.currentTimeMillis())

        // Simulate retry eligibility by advancing time
        storage.outbox[pending[0].id]!!.nextRetry = 0L
        failFirst = false

        // Second attempt succeeds and drains both
        val count2 = worker.drain()
        assertEquals(2, count2)
        assertTrue(manager.pending().isEmpty())
    }

    @Test
    fun testRejectedActionPreservedAsFailedAndQueueProceeds() = runBlocking {
        val rejectedCalls = mutableListOf<String>()
        val dispatched = mutableListOf<String>()
        val worker = Worker(
            manager = manager,
            dispatcher = Dispatcher { action ->
                if (action.action == "refused") {
                    throw Rejected("550 User unknown", code = 550)
                }
                dispatched.add(action.action)
            },
            onRejected = { entry, err ->
                rejectedCalls.add("${entry.action}:${err.code}")
            }
        )

        manager.enqueue("refused", "msg_rejected".toByteArray(Charsets.UTF_8))
        manager.enqueue("move", "msg_second:archive".toByteArray(Charsets.UTF_8))

        assertEquals(2, manager.count.value)
        assertEquals(0, manager.failed.value.size)

        // Drain processes refused action (rejects it, keeps in failed) and proceeds to move action
        val count = worker.drain()
        assertEquals(2, count)

        // Pending queue should now be 0, failed queue should be 1
        assertEquals(0, manager.count.value)
        assertTrue(manager.pending().isEmpty())
        assertEquals(1, manager.failed.value.size)

        val failedAction = manager.failed.value[0]
        assertEquals("refused", failedAction.action)
        assertEquals("failed", failedAction.status)
        assertEquals("550", failedAction.code)
        assertEquals("550 User unknown", failedAction.reason)
        assertEquals(listOf("refused:550"), rejectedCalls)
        assertEquals(listOf("move"), dispatched)

        // Retry restores to pending
        assertTrue(manager.retry(failedAction.id))
        assertEquals(1, manager.count.value)
        assertEquals(0, manager.failed.value.size)
        val retried = manager.pending()[0]
        assertEquals("pending", retried.status)
        assertEquals(0, retried.attempts)
        assertEquals(0L, retried.nextRetry)
        assertEquals("", retried.code)
        assertEquals("", retried.reason)

        // Discard removes from outbox completely
        assertTrue(manager.discard(retried.id))
        assertEquals(0, manager.count.value)
        assertEquals(0, manager.failed.value.size)
        assertTrue(manager.pending().isEmpty())
    }
}

