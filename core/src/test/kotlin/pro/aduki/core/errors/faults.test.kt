package pro.aduki.core.errors

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.DateTimeException
import kotlin.coroutines.cancellation.CancellationException

class FaultsTest {

    @Test
    fun documentedFailuresPassUnchanged() {
        val kept = listOf(
            CancellationException("cancelled"),
            AdukiException.Network("offline", code = 503),
            IOException("reset"),
            SocketTimeoutException("slow"),
            UnknownHostException("mail.example"),
            IllegalArgumentException("bad argument"),
            IllegalStateException("no local store"),
            OutOfMemoryError("big")
        )
        for (e in kept) assertSame(e, Faults.normal(e))
    }

    @Test
    fun anythingElseIsWrappedWithItsCause() {
        val wrapped = listOf(
            RuntimeException("json"),
            NoSuchElementException("empty"),
            ClassCastException("cast"),
            IndexOutOfBoundsException("index"),
            NullPointerException("null"),
            DateTimeException("date"),
            NumberFormatException("For input string: \"abc\"")
        )
        for (e in wrapped) {
            val n = Faults.normal(e)
            assertTrue("$e", n is AdukiException.Protocol)
            assertSame(e, n.cause)
        }
    }

    @Test
    fun reportNeverThrows() {
        Faults.report("test", RuntimeException("secret@example.com"))
        Faults.report("test", CancellationException())
    }
}
