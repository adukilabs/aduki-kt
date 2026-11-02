package pro.aduki.sync.engine

import io.objectbox.BoxStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Attachment
import pro.aduki.core.models.Body
import pro.aduki.core.models.BodyMode
import pro.aduki.crypto.cipher.Vault
import pro.aduki.crypto.keystore.Provider
import pro.aduki.store.box.Factory
import pro.aduki.store.entities.Content as ContentEntity
import pro.aduki.store.entities.Content_
import java.util.UUID

/** The body cache on a real ObjectBox store: round trip, modes and the refetch rule. */
class ContentTest {

    @get:Rule
    val dir = TemporaryFolder()

    private lateinit var store: BoxStore

    /** A transport that counts fetches; [reading] is the server's reading support. */
    private class Server(var reading: Boolean, var body: Body) : ContentTransport {
        val modes = mutableListOf<BodyMode>()
        var down = false

        override suspend fun fetch(hex: String): Body = fetch(hex, BodyMode.Both)
        override suspend fun fetch(hex: String, mode: BodyMode): Body {
            if (down) throw AdukiException.Network("offline")
            modes += mode
            return body
        }
        override suspend fun sanitised(): Boolean {
            if (down) throw AdukiException.Network("offline")
            return reading
        }
    }

    private val text = "Yes.\n\n> On Monday Ada wrote:\n> hello\n"
    private val sanitised = Body(
        hex = "m1",
        text = text,
        html = "<p>Yes.</p>",
        attachments = listOf(Attachment(blob = "b1", name = "a.pdf", type = "application/pdf", size = 3)),
        quoteStart = 6,
        hasText = true,
        hasHtml = true,
        sanitised = true
    )

    @Before
    fun setUp() {
        store = Factory.build(dir.newFolder("db"), Vault(Provider(), "content_${UUID.randomUUID()}_"))
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun aSanitisedBodyRoundTripsAndServesEveryMode() = runBlocking {
        val server = Server(reading = true, body = sanitised)
        val content = Content(store, server)
        assertEquals(sanitised, content.body("m1", BodyMode.Both))
        assertEquals(listOf(BodyMode.Both), server.modes)

        // A fresh engine reads the stored row: nothing is fetched again.
        val again = Content(store, server)
        assertEquals(sanitised, again.body("m1"))
        assertEquals(sanitised.copy(text = "", quoteStart = null), again.body("m1", BodyMode.Rich))
        assertEquals(sanitised.copy(html = ""), again.body("m1", BodyMode.Plain))
        assertEquals(sanitised, again.cached("m1"))
        assertEquals(1, server.modes.size)

        val row = store.boxFor(ContentEntity::class.java).query(Content_.hex.equal("m1")).build().use { it.findFirst() }!!
        assertEquals(6, row.quoteStart)
        assertTrue(row.sanitised && row.hasText && row.hasHtml)
    }

    @Test
    fun aRichRequestCachesTheWholeBody() = runBlocking {
        val server = Server(reading = true, body = sanitised)
        val content = Content(store, server)
        assertEquals("", content.body("m1", BodyMode.Rich).text)
        // The Plain reading comes from the cache, text included.
        assertEquals(text, content.body("m1", BodyMode.Plain).text)
        assertEquals(listOf(BodyMode.Both), server.modes)
    }

    @Test
    fun aNullQuoteStartAndMissingPartsRoundTrip() = runBlocking {
        val unreadable = Body(hex = "m1", hasText = false, hasHtml = false, sanitised = true)
        Content(store, Server(reading = true, body = unreadable)).body("m1")
        val back = Content(store, Server(reading = true, body = sanitised)).body("m1")
        assertEquals(unreadable, back)
        assertNull(back.quoteStart)
    }

    @Test
    fun aRawBodyIsReadAsBeforeWithoutReadingSupport() = runBlocking {
        val raw = Body(hex = "m1", text = "hi", html = "<b>hi</b>")
        val server = Server(reading = false, body = raw)
        val content = Content(store, server)
        assertEquals(raw, content.body("m1"))
        // A raw body is returned whole whatever the mode.
        assertEquals(raw, content.body("m1", BodyMode.Rich))
        assertEquals(raw, content.body("m1", BodyMode.Plain))
        assertEquals(1, server.modes.size)
        assertFalse(content.body("m1").sanitised)
        assertTrue(content.body("m1").hasHtml)
    }

    @Test
    fun aRawRowIsFetchedAgainOnceTheServerSanitises() = runBlocking {
        // A row as 0.6.1 cached it: no reading fields.
        store.boxFor(ContentEntity::class.java).put(ContentEntity(hex = "m1", text = "hi", html = "<b>hi</b>", fetched = 1))
        val server = Server(reading = false, body = sanitised)
        val content = Content(store, server)
        val old = content.body("m1", BodyMode.Rich)
        assertFalse(old.sanitised)
        assertTrue(old.hasText && old.hasHtml)
        assertTrue(server.modes.isEmpty())

        // The server's answer is kept; an engine started after the upgrade fetches the row again.
        server.reading = true
        assertFalse(content.body("m1").sanitised)
        val upgraded = Content(store, server)
        assertEquals(sanitised.copy(text = "", quoteStart = null), upgraded.body("m1", BodyMode.Rich))
        assertEquals(sanitised, upgraded.body("m1"))
        assertEquals(listOf(BodyMode.Both), server.modes)
        assertEquals(1, store.boxFor(ContentEntity::class.java).count())
    }

    @Test
    fun aRawRowIsServedWhenTheRefetchFails() = runBlocking {
        store.boxFor(ContentEntity::class.java).put(ContentEntity(hex = "m1", text = "hi", fetched = 1))
        val server = Server(reading = true, body = sanitised)
        server.down = true
        val content = Content(store, server)
        assertEquals(Body(hex = "m1", text = "hi"), content.body("m1"))
        // Back online within the minute: the cached row is served without asking again.
        server.down = false
        assertFalse(content.body("m1").sanitised)
        assertTrue(server.modes.isEmpty())
        // An explicit refresh still fails.
        server.down = true
        try {
            Content(store, server).body("m1", refresh = true)
            fail("expected the network failure")
        } catch (_: AdukiException.Network) {
        }
    }

    @Test
    fun aLocalDraftIsNotFetchedAgain() = runBlocking {
        val server = Server(reading = true, body = sanitised)
        val content = Content(store, server)
        content.put("d1", "draft text", "<p>draft</p>")
        val draft = content.body("d1", BodyMode.Both)
        assertEquals("draft text", draft.text)
        assertFalse(draft.sanitised)
        assertTrue(server.modes.isEmpty())
    }
}
