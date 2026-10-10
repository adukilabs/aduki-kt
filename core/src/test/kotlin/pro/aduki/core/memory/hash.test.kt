package pro.aduki.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HashTest {

    @Test
    fun testEmptyBuffer() {
        val hash = Hash.hash64(byteArrayOf())
        assertNotEquals(0L, hash)
    }

    @Test
    fun testConsistency64() {
        val bytes = "Aduki Android SDK ultra-fast email client".toByteArray(Charsets.UTF_8)
        val h1 = Hash.hash64(bytes)
        val h2 = Hash.hash64(bytes)
        assertEquals(h1, h2)
    }

    @Test
    fun testConsistency32() {
        val bytes = "Aduki Android SDK 32-bit".toByteArray(Charsets.UTF_8)
        val h1 = Hash.hash32(bytes)
        val h2 = Hash.hash32(bytes)
        assertEquals(h1, h2)
    }

    @Test
    fun testDistinctPayloads() {
        val b1 = "message_uid_1001".toByteArray(Charsets.UTF_8)
        val b2 = "message_uid_1002".toByteArray(Charsets.UTF_8)
        assertNotEquals(Hash.hash64(b1), Hash.hash64(b2))
        assertNotEquals(Hash.hash32(b1), Hash.hash32(b2))
    }

    @Test
    fun testMix() {
        val m1 = Hash.mix(12345L)
        val m2 = Hash.mix(12346L)
        assertNotEquals(m1, m2)
    }
}

