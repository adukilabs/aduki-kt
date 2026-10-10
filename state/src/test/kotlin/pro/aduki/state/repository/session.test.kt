package pro.aduki.state.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTest {

    @Test
    fun testUpdate() {
        val repo = SessionRepository()
        assertNull(repo.identity.value)

        val id = Identity(
            user = "usr_123",
            tenant = "ten_456",
            owner = true,
            scopes = listOf("mail:read", "mail:write"),
            tier = "pro"
        )
        repo.updateIdentity(id)
        assertEquals(id, repo.identity.value)

        repo.clear()
        assertNull(repo.identity.value)
    }
}

