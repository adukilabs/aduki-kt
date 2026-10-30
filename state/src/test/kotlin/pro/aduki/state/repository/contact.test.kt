package pro.aduki.state.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import pro.aduki.store.entities.Contact as ContactEntity

class ContactTest {

    private class TestContactSource : ContactSource {
        val flow = MutableStateFlow<List<ContactEntity>>(emptyList())

        override fun contacts(): Flow<List<ContactEntity>> = flow
        override fun get(hex: String): ContactEntity? = flow.value.firstOrNull { it.hex == hex }
        override fun all(): List<ContactEntity> = flow.value

        val stored = MutableStateFlow<List<String>>(emptyList())
        override fun groups(): Flow<List<String>> = stored
        override fun names(): List<String> = stored.value
    }

    @Test
    fun groupsAreTheTagsAndTheStoredGroups() = runBlocking {
        val source = TestContactSource()
        val repo = Contact(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        source.flow.value = listOf(ContactEntity(hex = "c1", name = "Ann", groups = listOf("work", "Friends")))
        source.stored.value = listOf("empty", "work")

        assertEquals(listOf("empty", "Friends", "work"), repo.groups().value)
        assertEquals(listOf("empty", "Friends", "work"), repo.names())
    }

    @Test
    fun testContactSearchFiltering() = runBlocking {
        val source = TestContactSource()
        val repo = Contact(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        source.flow.value = listOf(
            ContactEntity(hex = "c1", name = "Zachary", email = "z@test.pro", phone = "111"),
            ContactEntity(hex = "c2", name = "Alice", email = "alice@example.com", phone = "222"),
            ContactEntity(hex = "c3", name = "Bob", email = "bob@example.com", phone = "333")
        )

        // Observe sorted
        val sorted = repo.observe().value
        assertEquals(3, sorted.size)
        assertEquals("Alice", sorted[0].name)
        assertEquals("Bob", sorted[1].name)
        assertEquals("Zachary", sorted[2].name)

        // Search by email substring
        val searchEmail = repo.search("example.com").value
        assertEquals(2, searchEmail.size)
        assertEquals("Alice", searchEmail[0].name)
        assertEquals("Bob", searchEmail[1].name)

        // Search by phone
        val searchPhone = repo.search("111").value
        assertEquals(1, searchPhone.size)
        assertEquals("Zachary", searchPhone[0].name)

        // Direct get
        val single = repo.get("c2")
        assertNotNull(single)
        assertEquals("Alice", single!!.name)
    }

    @Test
    fun searchMatchesEveryEmailPhoneNotesAndGroups() = runBlocking {
        val source = TestContactSource()
        val repo = Contact(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        source.flow.value = listOf(
            ContactEntity(
                hex = "c1",
                name = "Ann Lee",
                email = "ann@primary.io",
                emails = listOf("ann@primary.io", "secondary-ann@corp.net"),
                phone = "+1000",
                phones = listOf("+1000", "+254700000000"),
                company = "Acme Corp",
                groups = listOf("friends", "vip"),
                notes = "Met at Nairobi summit"
            ),
            ContactEntity(
                hex = "c2",
                name = "Bob Smith",
                email = "bob@primary.io",
                emails = listOf("bob@primary.io"),
                phone = "+3000"
            )
        )

        // Matches second email
        val bySecondEmail = repo.search("corp.net").value
        assertEquals(1, bySecondEmail.size)
        assertEquals("c1", bySecondEmail[0].hex)

        // Matches second phone
        val bySecondPhone = repo.search("+2547").value
        assertEquals(1, bySecondPhone.size)
        assertEquals("c1", bySecondPhone[0].hex)

        // Matches company
        val byCompany = repo.search("Acme").value
        assertEquals(1, byCompany.size)
        assertEquals("c1", byCompany[0].hex)

        // Matches group tag
        val byGroup = repo.search("vip").value
        assertEquals(1, byGroup.size)
        assertEquals("c1", byGroup[0].hex)

        // Matches notes
        val byNotes = repo.search("Nairobi").value
        assertEquals(1, byNotes.size)
        assertEquals("c1", byNotes[0].hex)
    }

    @Test
    fun collectedAndManualFlowsSeparateContacts() = runBlocking {
        val source = TestContactSource()
        val repo = Contact(source = source, scope = CoroutineScope(Dispatchers.Unconfined))

        source.flow.value = listOf(
            ContactEntity(hex = "c1", name = "Collected One", collected = true),
            ContactEntity(hex = "c2", name = "Manual One", collected = false),
            ContactEntity(hex = "c3", name = "Collected Two", collected = true)
        )

        val collected = repo.collected().value
        assertEquals(2, collected.size)
        assertEquals("Collected One", collected[0].name)
        assertEquals("Collected Two", collected[1].name)

        val manual = repo.manual().value
        assertEquals(1, manual.size)
        assertEquals("Manual One", manual[0].name)
    }
}

