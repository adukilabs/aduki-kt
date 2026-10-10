package pro.aduki.core.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JitterTest {

    @Test
    fun testBounds() {
        val jitter = Jitter(base = 50, max = 5000)
        for (i in 1..100) {
            val d = jitter.next()
            assertTrue("Delay $d was less than base 50", d >= 50)
            assertTrue("Delay $d exceeded max 5000", d <= 5000)
        }
    }

    @Test
    fun testReset() {
        val jitter = Jitter(base = 100, max = 10_000)
        repeat(5) { jitter.next() }
        jitter.reset()
        assertEquals(100L, jitter.current())
    }

    @Test
    fun testDeterministicSeed() {
        val j1 = Jitter(base = 50, max = 1000, random = Random(42))
        val j2 = Jitter(base = 50, max = 1000, random = Random(42))
        assertEquals(j1.next(), j2.next())
        assertEquals(j1.next(), j2.next())
    }
}

