package pro.aduki.store.box

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.store.entities.Mailbox

class FactoryTest {

    @get:Rule
    val dir = TemporaryFolder()

    // Opens a real BoxStore: proves the ObjectBox native library loads on a plain JVM.
    @Test
    fun opensAStoreAndRoundTripsAnEntity() {
        Factory.build(dir.newFolder("db")).use { store ->
            val box = store.boxFor(Mailbox::class.java)
            val id = box.put(Mailbox(hex = "box_inbox", name = "Inbox", role = "inbox", uidnext = 7))
            val back = box.get(id)
            assertEquals("Inbox", back.name)
            assertEquals(7L, back.uidnext)
            assertEquals(1L, box.count())
        }
    }
}
