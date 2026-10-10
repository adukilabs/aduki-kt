package pro.aduki.store.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MailboxTest {

    @Test
    fun testMailboxInstantiation() {
        val mailbox = Mailbox(
            hex = "box_inbox",
            name = "Inbox",
            role = "inbox",
            uidnext = 1001,
            uidvalidity = 42,
            modseq = 15000,
            exists = 150,
            unseen = 5
        )

        assertNotNull(mailbox)
        assertEquals("box_inbox", mailbox.hex)
        assertEquals("inbox", mailbox.role)
        assertEquals(15000L, mailbox.modseq)
        assertEquals(42L, mailbox.uidvalidity)
        assertEquals(5, mailbox.unseen)
    }
}

