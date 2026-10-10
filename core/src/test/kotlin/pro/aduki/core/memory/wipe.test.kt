package pro.aduki.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WipeTest {

    @Test
    fun testBytesWipe() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        bytes.wipe()
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun testCharsWipe() {
        val chars = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        chars.wipe()
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun testIntsWipe() {
        val ints = intArrayOf(10, 20, 30)
        ints.wipe()
        assertTrue(ints.all { it == 0 })
    }

    @Test
    fun testLongsWipe() {
        val longs = longArrayOf(100L, 200L, 300L)
        longs.wipe()
        assertTrue(longs.all { it == 0L })
    }

    @Test
    fun testScopedWipedBytes() {
        var ref: ByteArray? = null
        val result = withWipedBytes(64) { buffer ->
            buffer[0] = 77
            ref = buffer
            "ok"
        }
        assertEquals("ok", result)
        assertTrue(ref!!.all { it == 0.toByte() })
    }
}

