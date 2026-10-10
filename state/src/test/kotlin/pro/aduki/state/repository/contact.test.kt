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
}

