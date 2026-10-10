package pro.aduki.store.entities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OutboxTest {

    @Test
    fun testOutboxActionInstantiation() {
        val payload = "raw_action_bytes".toByteArray(Charsets.UTF_8)
        val action = Outbox(
            action = "send",
            payload = payload,
            attempts = 0,
            nextRetry = System.currentTimeMillis() + 1000
        )

        assertNotNull(action)
        assertEquals("send", action.action)
        assertEquals(0, action.attempts)
        assertEquals(payload.size, action.payload.size)
    }
}

