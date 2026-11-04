package pro.aduki.state.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.aduki.core.models.Tokens

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

    @Test
    fun anotherSessionDropsTheCachedIdentity() {
        val repo = SessionRepository()
        repo.update(Tokens(token = "a1", refresh = "r1", session = "s1"))
        repo.update(Identity(user = "ada"))

        repo.update(Tokens(token = "a2", refresh = "r2", session = "s1"))
        assertEquals("a renewal of the same session keeps it", "ada", repo.identity.value?.user)

        repo.update(Tokens(token = "b1", refresh = "q1", session = "s2"))
        assertNull(repo.identity.value)
    }

    @Test
    fun tokensWithoutASessionIdAreAnotherSessionWhenTheTokenDiffers() {
        val repo = SessionRepository()
        repo.update(Tokens(token = "a1"))
        repo.update(Identity(user = "ada"))

        repo.update(Tokens(token = "a1", refresh = ""))
        assertEquals("the same token keeps it", "ada", repo.identity.value?.user)

        repo.update(Tokens(token = "b1"))
        assertNull(repo.identity.value)
    }
}

