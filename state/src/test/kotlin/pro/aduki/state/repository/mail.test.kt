package pro.aduki.state.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Message

class MailTest {

    private class TestMailSource : MailSource {
        val mailboxFlow = MutableStateFlow<List<Mailbox>>(emptyList())
        val messageFlow = MutableStateFlow<List<Message>>(emptyList())

        override fun mailboxes(): Flow<List<Mailbox>> = mailboxFlow
        override fun messages(mailboxHex: String): Flow<List<Message>> = messageFlow
        override fun message(hex: String): Flow<Message?> =
            MutableStateFlow(messageFlow.value.firstOrNull { it.hex == hex })

        override fun getMessage(hex: String): Message? =
            messageFlow.value.firstOrNull { it.hex == hex }

        override fun getMailbox(hex: String): Mailbox? =
            mailboxFlow.value.firstOrNull { it.hex == hex }
    }

    @Test
    fun testMailboxFlowEmission() = runBlocking {
        val source = TestMailSource()
        val repo = Mail(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        source.mailboxFlow.value = listOf(
            Mailbox(hex = "box_inbox", name = "Inbox"),
            Mailbox(hex = "box_sent", name = "Sent")
        )

        val boxes = repo.mailboxes().value
        assertEquals(2, boxes.size)
        assertEquals("Inbox", boxes[0].name)
    }

    @Test
    fun testUnreadCountEmission() = runBlocking {
        val source = TestMailSource()
        val repo = Mail(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        source.messageFlow.value = listOf(
            Message(hex = "m1", mailbox = "inbox", flags = 0), // unread
            Message(hex = "m2", mailbox = "inbox", flags = Message.SEEN), // read
            Message(hex = "m3", mailbox = "inbox", flags = 0) // unread
        )

        val unreadCount = repo.unread("inbox").value
        assertEquals(2, unreadCount)
    }
}

