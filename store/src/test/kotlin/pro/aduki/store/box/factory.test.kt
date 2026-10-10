package pro.aduki.store.box

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // Characterization, not a requirement: records the fact the docs state, that the
    // database is stored in the clear. If ObjectBox encryption is ever adopted this
    // test is replaced by one that proves the opposite.
    @Test
    fun theDatabaseFileHoldsStoredTextInTheClear() {
        val marker = "plaintext-marker-7f3a91c2"
        val folder = dir.newFolder("clear")
        Factory.build(folder).use { store ->
            store.boxFor(Mailbox::class.java).put(Mailbox(hex = "box_x", name = marker, role = "x", uidnext = 1))
        }
        val found = folder.walkTopDown().filter { it.isFile }.any { file ->
            String(file.readBytes(), Charsets.ISO_8859_1).contains(marker)
        }
        assertTrue("expected the marker in the clear in ${folder.list()?.toList()}", found)
    }
}
