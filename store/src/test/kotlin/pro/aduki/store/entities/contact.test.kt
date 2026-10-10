package pro.aduki.store.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContactTest {

    @Test
    fun testContactAttributes() {
        val contact = Contact(
            hex = "cnt_101",
            name = "Jane Doe",
            email = "jane@example.com",
            phone = "+123456789",
            company = "Aduki Org",
            ctag = "ctag_rev_3"
        )

        assertNotNull(contact)
        assertEquals("cnt_101", contact.hex)
        assertEquals("Jane Doe", contact.name)
        assertEquals("jane@example.com", contact.email)
        assertEquals("ctag_rev_3", contact.ctag)
    }
}

