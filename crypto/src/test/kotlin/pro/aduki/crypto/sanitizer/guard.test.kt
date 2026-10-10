package pro.aduki.crypto.sanitizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {

    @Test
    fun testGuardWipesBytesOnClose() {
        val secret = byteArrayOf(9, 8, 7, 6, 5)
        val guard = Guard(secret)
        guard.close()
        assertTrue(secret.all { it == 0.toByte() })
    }

    @Test
    fun testWithGuardScoping() {
        var external: ByteArray? = null
        val res = withGuard(byteArrayOf(1, 2, 3)) { guard ->
            external = guard.bytes
            "protected"
        }
        assertEquals("protected", res)
        assertTrue(external!!.all { it == 0.toByte() })
    }

    @Test
    fun testWithGuardWipesOnException() {
        val secret = byteArrayOf(4, 5, 6)
        try {
            withGuard(secret) {
                throw IllegalStateException("Failure")
            }
        } catch (_: Exception) {
            // Expected
        }
        assertTrue(secret.all { it == 0.toByte() })
    }
}

