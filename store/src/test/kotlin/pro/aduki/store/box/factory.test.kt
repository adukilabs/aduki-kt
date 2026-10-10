package pro.aduki.store.box

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.store.entities.Contact
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.io.File
import java.util.UUID

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

    @After
    fun resetSealing() = Sealing.install(null)

    private fun newVault() = Vault(Provider(), "store_${UUID.randomUUID()}_")

    private fun diskHas(folder: File, text: String) = folder.walkTopDown().filter { it.isFile }.any {
        String(it.readBytes(), Charsets.ISO_8859_1).contains(text)
    }

    @Test
    fun noPersonalPlaintextIsInTheDatabaseDirectoryAndStructuralColumnsAre() {
        val folder = dir.newFolder("sealed")
        val secrets = listOf(
            "SecretName-51aa", "secret-51aa@example.com", "+254700515151", "SecretCompany-51aa",
            "SecretVcard-51aa", "SecretSubject-51aa", "SecretFrom-51aa", "SecretTo-51aa@example.org",
            "SecretPreview-51aa", "SecretBlob-51aa", "SecretPayload-51aa", "SecretBox-51aa", "SecretPlace-51aa"
        )
        Factory.build(folder, newVault()).use { store ->
            store.boxFor(Contact::class.java).put(
                Contact(hex = "clearhex-51aa", name = "SecretName-51aa", email = "secret-51aa@example.com", phone = "+254700515151",
                    company = "SecretCompany-51aa", vcard = "BEGIN:VCARD\nNOTE:SecretVcard-51aa")
            )
            store.boxFor(Message::class.java).put(Message(hex = "msg-51aa", subject = "SecretSubject-51aa", fromName = "SecretFrom-51aa",
                fromEmail = "secret-51aa@example.com", to = "SecretTo-51aa@example.org", preview = "SecretPreview-51aa", blob = "SecretBlob-51aa"))
            store.boxFor(Mailbox::class.java).put(Mailbox(hex = "box-51aa", name = "SecretBox-51aa", role = "inbox"))
            store.boxFor(pro.aduki.store.entities.Appointment::class.java).put(pro.aduki.store.entities.Appointment(hex = "appt-51aa", location = "SecretPlace-51aa"))
            Sealing.put(store.boxFor(Outbox::class.java), Outbox(hex = "o1", action = "send", payload = "SecretPayload-51aa".toByteArray()))
        }
        for (secret in secrets) assertFalse("$secret is on disk", diskHas(folder, secret))
        // Blind indexes reveal no plaintext either (checked above through the e-mail and phone), and
        // the structural columns stay readable.
        assertTrue(diskHas(folder, "clearhex-51aa"))
        assertTrue(diskHas(folder, "msg-51aa"))
        assertTrue(diskHas(folder, "inbox"))
    }

    @Test
    fun exactLookupWorksThroughTheBlindIndexAndTheIndexIsKeyed() {
        val folder = dir.newFolder("index")
        val vault = newVault()
        Factory.build(folder, vault).use { store ->
            val box = store.boxFor(Contact::class.java)
            box.put(Contact(hex = "a", name = "Ada", email = "Ada@Example.com", phone = "+254 700 000 001"))
            box.put(Contact(hex = "b", name = "Bob", email = "bob@example.com", phone = "+254700000002"))
            assertTrue(Sealing.indexed())
            val key = Sealing.index("email", " ADA@example.com ")
            assertTrue(key.matches(Regex("1:[0-9a-f]{64}")))
            assertEquals("a", box.query(pro.aduki.store.entities.Contact_.emailIndex.equal(key)).build().findFirst()?.hex)
            val phoneKey = Sealing.index("phone", "+254-700-000-002")
            assertEquals("b", box.query(pro.aduki.store.entities.Contact_.phoneIndex.equal(phoneKey)).build().findFirst()?.hex)
            // Same value in two fields gives unrelated index values.
            assertFalse(Sealing.index("email", "x@example.com") == Sealing.index("phone", "x@example.com"))
            // Another vault's index key gives different values for the same address.
            val other = Vault(Provider(), "store_${UUID.randomUUID()}_").also { it.attachIndex(File(folder, "other.keys")) }
            assertFalse(key == other.blind("email", "ada@example.com"))
        }
    }

    @Test
    fun rotatingTheIndexKeyIsRebuiltByReseal() {
        val folder = dir.newFolder("rotidx")
        val vault = newVault()
        Factory.build(folder, vault).use { store ->
            val box = store.boxFor(Contact::class.java)
            box.put(Contact(hex = "a", email = "ada@example.com"))
            val before = box.all.single().emailIndex
            assertEquals(2, vault.rotateIndex())
            val after = Sealing.index("email", "ada@example.com")
            assertTrue(after.startsWith("2:"))
            assertFalse(before == after)
            Sealing.reseal(store)
            assertEquals(after, box.all.single().emailIndex)
        }
        // The seeds survive a reopen with a vault on the same keys.
        Sealing.install(vault)
        Factory.build(folder).use { store ->
            assertEquals(2, vault.indexId())
            assertEquals("a", store.boxFor(Contact::class.java).query(
                pro.aduki.store.entities.Contact_.emailIndex.equal(Sealing.index("email", "ada@example.com"))).build().findFirst()?.hex)
        }
    }

    @Test
    fun sealedColumnsRoundTripAndLegacyPlaintextRowsUpgrade() {
        val folder = dir.newFolder("upgrade")
        // A legacy row, written before any vault existed.
        Factory.build(folder).use { store ->
            store.boxFor(Contact::class.java).put(Contact(hex = "old", name = "Old", vcard = "LEGACY-VCARD"))
            store.boxFor(Outbox::class.java).put(Outbox(hex = "o", action = "send", payload = "LEGACY-PAYLOAD".toByteArray()))
        }
        val vault = newVault()
        Factory.build(folder, vault).use { store ->
            val contacts = store.boxFor(Contact::class.java)
            assertEquals("LEGACY-VCARD", contacts.all.single().vcard) // readable as plaintext
            val id = contacts.put(Contact(hex = "new", name = "New", vcard = "NEW-VCARD"))
            assertEquals("NEW-VCARD", contacts.get(id).vcard)

            assertTrue(Sealing.reseal(store) >= 3)
            assertEquals("LEGACY-VCARD", contacts.all.first { it.hex == "old" }.vcard)
            val out = Sealing.opened(store.boxFor(Outbox::class.java).all.single())
            assertArrayEquals("LEGACY-PAYLOAD".toByteArray(), out.payload)
        }
        // Without the vault the rows are sealed now, and reading them fails closed.
        Sealing.install(null)
        Factory.build(folder).use { store ->
            assertThrows(Exception::class.java) { store.boxFor(Contact::class.java).all }
        }
    }

    @Test
    fun rowsSealedUnderAnOlderKeyStillReadAfterRotation() {
        val folder = dir.newFolder("rotate")
        val vault = newVault()
        Factory.build(folder, vault).use { store ->
            val box = store.boxFor(Contact::class.java)
            box.put(Contact(hex = "a", vcard = "UNDER-KEY-1"))
            vault.rotate()
            box.put(Contact(hex = "b", vcard = "UNDER-KEY-2"))
            assertEquals(setOf("UNDER-KEY-1", "UNDER-KEY-2"), box.all.map { it.vcard }.toSet())
        }
    }
}
