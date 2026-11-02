package pro.aduki.sync.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.core.models.Confidence
import pro.aduki.core.models.Forwarded
import pro.aduki.core.models.ReplySource
import java.time.Instant

class ThreadTransportTest {

    @Test
    fun anEmailMapsToAnEntry() {
        val email = JSONObject(
            """{"id":"m2","messageId":["two@x"],"inReplyTo":["<root@x>"],"references":["root@x",7,null],
               "subject":"Re: Hi","sentAt":"2026-11-04T09:12:00+03:00","receivedAt":"2026-11-04T06:12:05Z",
               "from":[{"name":"Ben","email":"ben@example.org"}],"to":[{"name":null,"email":"ana@example.com"}],"cc":null,
               "keywords":{"${'$'}seen":true,"${'$'}flagged":false},"mailboxIds":{"b-sent":true},
               "attachments":[{"blobId":"b1","name":"a.pdf","type":"application/pdf","size":3}],
               "aduki:reply":{"text":"Hi","html":null,"quote":{"start":null,"from":"<root@x>","author":null},"signature":"Ben",
                 "forwarded":{"from":"Shop","date":null,"subject":"Order","text":"Shipped"},"confidence":"medium"}}"""
        )
        val entry = JmapThreadTransport.entry(email, setOf("b-sent"))!!
        assertEquals("two@x", entry.messageId)
        assertEquals(listOf("<root@x>"), entry.inReplyTo)
        assertEquals(listOf("root@x"), entry.references)
        assertEquals(Instant.parse("2026-11-04T06:12:00Z"), entry.sentAt)
        assertEquals("Ben", entry.from!!.name)
        assertNull(entry.to.single().name)
        assertEquals(setOf("\$seen"), entry.keywords)
        assertTrue(entry.sent)
        assertEquals("a.pdf", entry.attachments.single().name)
        val reply = entry.reply!!
        assertEquals(Confidence.Medium, reply.confidence)
        assertEquals(ReplySource.Server, reply.source)
        assertEquals("root@x", reply.quotedId)
        assertNull(reply.quoteStart)
        assertEquals(Forwarded("Shop", null, "Order", "Shipped"), reply.forwarded)
        assertNull(entry.security)
    }

    @Test
    fun malformedFieldsAreLeftEmpty() {
        val email = JSONObject(
            """{"id":"m1","messageId":"not-a-list","sentAt":"yesterday","from":"ana","keywords":[],"mailboxIds":null,
               "aduki:reply":{"html":"<p>no text</p>"},"aduki:security":{"transport":"tls","dkim":[1,{"result":"fail"}],"internal":"yes"}}"""
        )
        val entry = JmapThreadTransport.entry(email)!!
        assertNull(entry.messageId)
        assertNull(entry.sentAt)
        assertNull(entry.from)
        assertNull(entry.reply)
        val security = entry.security!!
        assertNull(security.transport)
        assertEquals("fail", security.dkim!!.single().result)
        assertEquals(false, security.internal)
        assertNull(JmapThreadTransport.entry(JSONObject("""{"subject":"no id"}""")))
    }
}
