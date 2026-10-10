@file:Suppress("DEPRECATION")

package pro.aduki.hermes

import org.junit.Test
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class ShimsTest {
    @Test
    fun `old names alias the new classes`() {
        assertSame(pro.aduki.sdk.Aduki::class.java, HermesClient::class.java)
        assertSame(pro.aduki.core.errors.AdukiException::class.java, HermesException::class.java)
    }

    @Test
    fun `old exception type catches new subclasses`() {
        val e: HermesException = pro.aduki.core.errors.AdukiException.Network("x")
        assertTrue(e is pro.aduki.core.errors.AdukiException.Network)
    }
}
