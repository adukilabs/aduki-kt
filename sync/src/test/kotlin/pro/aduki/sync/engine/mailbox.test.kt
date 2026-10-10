package pro.aduki.sync.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pro.aduki.store.entities.Mailbox as MailboxEntity
import pro.aduki.store.entities.Message

class MailboxTest {

    private class TestStorage : MailboxStorage {
        val mailboxes = mutableMapOf<String, MailboxEntity>()
        val messages = mutableMapOf<Long, Message>()

        override fun getMailbox(hex: String): MailboxEntity? = mailboxes[hex]
        override fun putMailbox(mailbox: MailboxEntity) { mailboxes[mailbox.hex] = mailbox }
        override fun getMessages(mailboxHex: String): List<Message> =
            messages.values.filter { it.mailbox == mailboxHex }
        override fun putMessages(messages: List<Message>) {
            messages.forEach { this.messages[it.id] = it }
        }
        override fun removeMessages(messages: List<Message>) {
            messages.forEach { this.messages.remove(it.id) }
        }
        override fun clearMailbox(mailboxHex: String) {
            val toRemove = messages.values.filter { it.mailbox == mailboxHex }.map { it.id }
            toRemove.forEach { messages.remove(it) }
        }
        override fun <T> tx(block: () -> T): T = block()
    }

    private lateinit var storage: TestStorage

    @Before
    fun setup() {
        storage = TestStorage()
        storage.putMailbox(
            MailboxEntity(
                hex = "inbox",
                name = "INBOX",
                uidvalidity = 1000L,
                modseq = 10L
            )
        )
    }

    @Test
    fun testCondstoreDeltaApplication() = runBlocking {
        // Initial messages: UID 1 (to be removed), UID 2 (to be modified), UID 3 (to be removed)
        storage.putMessages(
            listOf(
                Message(id = 1L, hex = "m1", mailbox = "inbox", uid = 101L, flags = 0),
                Message(id = 2L, hex = "m2", mailbox = "inbox", uid = 102L, flags = 0, dirty = false),
                Message(id = 3L, hex = "m3", mailbox = "inbox", uid = 103L, flags = 0)
            )
        )

        val transport = MailboxTransport { _, _, _ ->
            MailboxDelta(
                newUids = listOf(104L, 105L, 106L),
                changedUids = listOf(102L),
                removedUids = listOf(101L, 103L),
                modseq = 25L,
                uidvalidity = 1000L,
                messages = listOf(
                    Message(id = 2L, hex = "m2", mailbox = "inbox", uid = 102L, flags = Message.SEEN),
                    Message(id = 4L, hex = "m4", mailbox = "inbox", uid = 104L, subject = "New 1"),
                    Message(id = 5L, hex = "m5", mailbox = "inbox", uid = 105L, subject = "New 2"),
                    Message(id = 6L, hex = "m6", mailbox = "inbox", uid = 106L, subject = "New 3")
                )
            )
        }

        val engine = Mailbox(storage, transport)
        val success = engine.sync("inbox")
        assertTrue(success)

        val messages = storage.getMessages("inbox")
        assertEquals(4, messages.size) // 1 modified + 3 new (2 removed)

        // Verify removed
        assertNull(messages.firstOrNull { it.uid == 101L })
        assertNull(messages.firstOrNull { it.uid == 103L })

        // Verify modified
        val modified = messages.firstOrNull { it.uid == 102L }
        assertNotNull(modified)
        assertTrue(modified!!.seen())

        // Verify modseq advanced
        val mailbox = storage.getMailbox("inbox")!!
        assertEquals(25L, mailbox.modseq)
    }

    @Test
    fun testUidValidityResetWipesMailbox() = runBlocking {
        storage.putMessages(
            listOf(
                Message(id = 1L, hex = "m1", mailbox = "inbox", uid = 101L),
                Message(id = 2L, hex = "m2", mailbox = "inbox", uid = 102L)
            )
        )

        val transport = MailboxTransport { _, _, _ ->
            MailboxDelta(
                newUids = listOf(201L),
                modseq = 1L,
                uidvalidity = 2000L, // Different UIDVALIDITY triggers wipe
                messages = listOf(
                    Message(id = 10L, hex = "new_m", mailbox = "inbox", uid = 201L, subject = "Fresh")
                )
            )
        }

        val engine = Mailbox(storage, transport)
        val success = engine.sync("inbox")
        assertTrue(success)

        val messages = storage.getMessages("inbox")
        assertEquals(1, messages.size)
        assertEquals(201L, messages[0].uid)

        val mailbox = storage.getMailbox("inbox")!!
        assertEquals(2000L, mailbox.uidvalidity)
        assertEquals(1L, mailbox.modseq)
    }

    @Test
    fun testDirtyFlagsPreservedDuringConflict() = runBlocking {
        // Message locally flagged as FLAGGED with dirty=true
        storage.putMessages(
            listOf(
                Message(id = 1L, hex = "m1", mailbox = "inbox", uid = 101L, flags = Message.FLAGGED, dirty = true)
            )
        )

        val transport = MailboxTransport { _, _, _ ->
            MailboxDelta(
                changedUids = listOf(101L),
                modseq = 15L,
                uidvalidity = 1000L,
                messages = listOf(
                    Message(id = 1L, hex = "m1", mailbox = "inbox", uid = 101L, flags = 0)
                )
            )
        }

        val engine = Mailbox(storage, transport)
        engine.sync("inbox")

        val msg = storage.getMessages("inbox").first()
        assertTrue(msg.flagged()) // Local dirty flag preserved
    }
}

