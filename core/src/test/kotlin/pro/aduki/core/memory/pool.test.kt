package pro.aduki.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolTest {

    @Test
    fun testAcquireAndRelease() {
        val pool = Pool(bufferSize = 1024, maxCapacity = 4)
        val buf = pool.acquire()
        assertNotNull(buf)
        assertEquals(1024, buf.size)

        buf[0] = 99
        pool.release(buf)

        val recycled = pool.acquire()
        assertEquals(0.toByte(), recycled[0]) // Confirms wiping upon release
    }

    @Test
    fun testUseScoped() {
        val pool = Pool(bufferSize = 512)
        val result = pool.use { buf ->
            buf[0] = 12
            buf.size
        }
        assertEquals(512, result)
    }
}

