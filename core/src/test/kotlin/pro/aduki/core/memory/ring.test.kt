package pro.aduki.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingTest {

    @Test
    fun testOfferAndPoll() {
        val ring = Ring<String>(capacity = 4)
        assertTrue(ring.empty())

        assertTrue(ring.offer("msg1"))
        assertTrue(ring.offer("msg2"))
        assertEquals(2, ring.size())

        assertEquals("msg1", ring.poll())
        assertEquals("msg2", ring.poll())
        assertNull(ring.poll())
        assertTrue(ring.empty())
    }

    @Test
    fun testFullCapacity() {
        val ring = Ring<Int>(capacity = 2) // Next power of 2 is 2
        assertTrue(ring.offer(1))
        assertTrue(ring.offer(2))
        assertFalse(ring.offer(3)) // Buffer full

        assertEquals(1, ring.poll())
        assertTrue(ring.offer(3)) // Space freed
    }

    @Test
    fun testZeroCapacityDefaultsToOne() {
        val ring = Ring<Int>(capacity = 0)
        assertTrue(ring.empty())
        assertTrue(ring.offer(42))
        assertFalse(ring.offer(43)) // Capacity 1 is now full
        assertEquals(42, ring.poll())
    }
}

