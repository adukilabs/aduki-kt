package pro.aduki.core.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.core.models.Confidence
import pro.aduki.core.models.Conversation
import pro.aduki.core.models.Person
import pro.aduki.core.models.Reply
import pro.aduki.core.models.ReplySource
import pro.aduki.core.models.Security
import pro.aduki.core.models.ThreadMessage
import pro.aduki.core.models.Transport
import java.time.Instant

/** Fixture threads for the tree and the reply extraction. Every fixture keeps every message and never throws. */
class ThreadsTest {

    private val ana = Person("Ana Kimani", "ana@example.com")
    private val ben = Person("Ben O.", "ben@example.org")
    private val chloe = Person("Chloé M.", "chloe@example.net")
    private val now = Instant.parse("2026-11-04T18:00:00Z")

    private fun at(time: String) = Instant.parse("2026-11-04T${time}:00Z")

    private fun build(entries: List<Entry>): Conversation {
        val conversation = Threads.build("t1", entries, now, budget = 5_000)
        // Every message present exactly once.
        val hexes = conversation.messages.filterNot { it.placeholder }.flatMap { it.copies }.toSet()
        assertEquals(entries.map { it.hex }.toSet(), hexes)
        return conversation
    }

    private fun Conversation.of(hex: String): ThreadMessage = messages.first { it.hex == hex }

    @Test
    fun aGmailOutlookAppleThreadKeepsOnlyOwnWords() {
        val m1 = Entry(
            hex = "m1", messageId = "<root@mail.gmail.com>", subject = "Q4 roadmap review", from = ana, to = listOf(ben, chloe),
            sentAt = at("09:12"), receivedAt = at("09:12"),
            text = "Hi all, here is the draft for Q4.\n\nAna",
            html = "<div dir=\"ltr\">Hi all, here is the draft for Q4.<div><br></div><div>Ana</div></div>"
        )
        val m2 = Entry(
            hex = "m2", messageId = "<ben1@outlook.com>", inReplyTo = listOf("<root@mail.gmail.com>"), references = listOf("<root@mail.gmail.com>"),
            subject = "RE: Q4 roadmap review", from = ben, to = listOf(ana), cc = listOf(chloe), sentAt = at("10:30"), receivedAt = at("10:30"),
            text = "Looks good. Two questions on budget.\n\n________________________________\nFrom: Ana Kimani <ana@example.com>\n" +
                "Sent: Tuesday, November 4, 2026 9:12 AM\nTo: Ben O. <ben@example.org>\nSubject: Q4 roadmap review\n\nHi all, here is the draft for Q4.\n\nAna",
            html = "<html><body><div>Looks good. Two questions on budget.</div><div id=\"appendonsend\"></div><hr style=\"display:inline-block\">" +
                "<div id=\"divRplyFwdMsg\" dir=\"ltr\"><b>From:</b> Ana Kimani &lt;ana@example.com&gt;<br><b>Sent:</b> Tuesday</div>" +
                "<div>Hi all, here is the draft for Q4.</div></body></html>"
        )
        val m3 = Entry(
            hex = "m3", messageId = "<C1@icloud.com>", inReplyTo = listOf("<ben1@outlook.com>"),
            references = listOf("<root@mail.gmail.com>", "<ben1@outlook.com>"), subject = "Re: Q4 roadmap review", from = chloe,
            to = listOf(ben), cc = listOf(ana), sentAt = at("11:02"), receivedAt = at("11:02"),
            text = "The budget numbers are in tab 3.\n\nSent from my iPhone\n\n> On 4 Nov 2026, at 10:30, Ben O. <ben@example.org> wrote:\n>\n> Looks good. Two questions on budget.",
            html = "<html><body dir=\"auto\"><div>The budget numbers are in tab 3.</div><div><br></div><div>Sent from my iPhone</div>" +
                "<div><br><blockquote type=\"cite\">On 4 Nov 2026, at 10:30, Ben O. &lt;ben@example.org&gt; wrote:<br><br>" +
                "<div>Looks good. Two questions on budget.</div></blockquote></div></body></html>"
        )
        val m4 = Entry(
            hex = "m4", messageId = "<CAB4@mail.gmail.com>", inReplyTo = listOf("<C1@icloud.com>"),
            references = listOf("<root@mail.gmail.com>", "<ben1@outlook.com>", "<C1@icloud.com>"), subject = "Re: Q4 roadmap review",
            from = ana, to = listOf(chloe), cc = listOf(ben), sentAt = at("15:05"), receivedAt = at("15:05"),
            text = "Thanks all, merging now.\n\nOn Tue, 4 Nov 2026 at 11:02, Chloé M. <chloe@example.net> wrote:\n\n> The budget numbers are in tab 3.",
            html = "<div dir=\"ltr\">Thanks all, merging now.</div><br><div class=\"gmail_quote\"><div dir=\"ltr\" class=\"gmail_attr\">" +
                "On Tue, 4 Nov 2026 at 11:02, Chloé M. &lt;chloe@example.net&gt; wrote:<br></div><blockquote class=\"gmail_quote\">" +
                "The budget numbers are in tab 3.</blockquote></div>",
            keywords = emptySet()
        )
        val c = build(listOf(m4, m2, m3, m1))
        assertEquals(listOf("m1", "m2", "m3", "m4"), c.messages.map { it.hex })
        assertEquals(listOf(null, "m1", "m2", "m3"), c.messages.map { it.parent })
        assertEquals(listOf(0, 1, 2, 3), c.messages.map { it.depth })
        assertEquals("Q4 roadmap review", c.subject)
        assertEquals(listOf(ana, ben, chloe), c.participants)

        val outlook = c.of("m2").reply
        assertEquals("Looks good. Two questions on budget.", outlook.text)
        assertEquals(ReplySource.Marker, outlook.source)
        assertEquals(Confidence.High, outlook.confidence)
        assertFalse(outlook.html!!.contains("divRplyFwdMsg"))
        assertEquals("m1", outlook.quotedFrom)

        val apple = c.of("m3").reply
        assertEquals("The budget numbers are in tab 3.", apple.text)
        assertEquals("Sent from my iPhone", apple.signature)
        assertTrue(apple.quoted!!.contains("Looks good"))
        assertEquals("Ben O.", apple.author)

        val gmail = c.of("m4").reply
        assertEquals("Thanks all, merging now.", gmail.text)
        assertFalse(gmail.html!!.contains("gmail_quote"))
        assertEquals("Chloé M.", gmail.author)
        assertTrue(c.of("m4").unread)

        // The root has nothing to cut: whole, low confidence.
        assertEquals(Confidence.Low, c.of("m1").reply.confidence)
        assertTrue(c.of("m1").reply.text.startsWith("Hi all"))
    }

    @Test
    fun aMissingParentBecomesAPlaceholder() {
        val m1 = Entry(hex = "m1", messageId = "root@x", subject = "Plan", from = ana, sentAt = at("09:00"), text = "Start")
        val m3 = Entry(
            hex = "m3", messageId = "three@x", inReplyTo = listOf("gone@x"), references = listOf("root@x", "gone@x"),
            subject = "Re: Plan", from = ana, sentAt = at("11:00"), text = "Agreed"
        )
        val m4 = Entry(hex = "m4", messageId = "four@x", inReplyTo = listOf("<nowhere@y>"), subject = "Re: Plan", from = ben, sentAt = at("12:00"), text = "Late")
        val c = build(listOf(m1, m3, m4))
        val holder = c.of(ThreadMessage.PLACEHOLDER + "gone@x")
        assertTrue(holder.placeholder)
        assertEquals("m1", holder.parent)
        assertEquals(holder.hex, c.of("m3").parent)
        assertEquals(2, c.of("m3").depth)
        // A placeholder with no known ancestor is a root of its own.
        val lone = c.of(ThreadMessage.PLACEHOLDER + "nowhere@y")
        assertNull(lone.parent)
        assertEquals(lone.hex, c.of("m4").parent)
        assertEquals(5, c.messages.size)
    }

    @Test
    fun theSentAndReceivedCopiesMergeAndTheReceivedOneGivesSecurity() {
        val tls = Security(transport = Transport(tls = true, version = "TLSv1.3", cipher = "TLS_AES_128_GCM_SHA256", from = "mx.example.com"), internal = false)
        val outbound = Security(transport = Transport(tls = false, from = "mx.aduki.pro"))
        val root = Entry(hex = "m1", messageId = "<root@x>", subject = "Hello", from = ben, sentAt = at("08:00"), text = "Hi", keywords = setOf("\$seen"))
        val sent = Entry(
            hex = "s1", messageId = "<same@x>", inReplyTo = listOf("<root@x>"), subject = "Re: Hello", from = ana, to = listOf(ana, ben),
            sentAt = at("09:00"), mailboxes = setOf("b-sent"), sent = true, security = outbound, text = "Hello back", keywords = setOf("\$seen")
        )
        val received = sent.copy(hex = "r1", mailboxes = setOf("b-inbox"), sent = false, security = tls, keywords = emptySet())
        val c = build(listOf(root, sent, received))
        assertEquals(2, c.messages.size)
        val merged = c.of("r1")
        assertEquals(listOf("r1", "s1"), merged.copies)
        assertEquals(tls, merged.security)
        assertEquals(setOf("b-sent", "b-inbox"), merged.mailboxes)
        assertTrue(merged.unread)
        assertTrue(c.security.available)
        assertTrue(c.security.encrypted)
    }

    @Test
    fun withoutThreadingHeadersTheSubjectAndRecipientsDecide() {
        val m1 = Entry(hex = "m1", subject = "Lunch", from = ana, to = listOf(ben), sentAt = at("09:00"), text = "Noon?")
        val m2 = Entry(hex = "m2", subject = "Re: lunch", from = ben, to = listOf(ana), sentAt = at("09:10"), text = "Yes")
        val m3 = Entry(hex = "m3", subject = "RE[2]:  LUNCH", from = ana, to = listOf(ben), sentAt = at("09:20"), text = "Great")
        val m4 = Entry(hex = "m4", subject = "AW: SV: Re: Lunch", from = ben, to = listOf(ana), sentAt = at("09:30"), text = "See you")
        // Same subject but the sender was never a recipient: a root of its own.
        val other = Entry(hex = "o1", subject = "Re: Lunch", from = chloe, to = listOf(ana), sentAt = at("09:40"), text = "Me too?")
        val c = build(listOf(m4, m3, m2, m1, other))
        assertEquals(listOf(null, "m1", "m2", "m3"), listOf("m1", "m2", "m3", "m4").map { c.of(it).parent })
        assertNull(c.of("o1").parent)
        assertEquals("Lunch", Tree.strip("Fwd: [team] RE[2]: AW: Lunch"))
    }

    @Test
    fun cyclesAndSelfReferencesAreBroken() {
        val a = Entry(hex = "a", messageId = "a@x", inReplyTo = listOf("b@x"), subject = "Loop", from = ana, sentAt = at("09:00"), text = "A")
        val b = Entry(hex = "b", messageId = "b@x", inReplyTo = listOf("a@x"), subject = "Re: Loop", from = ben, sentAt = at("09:05"), text = "B")
        val self = Entry(hex = "s", messageId = "s@x", inReplyTo = listOf("s@x"), references = listOf("s@x"), subject = "Self", from = ana, sentAt = at("09:10"), text = "S")
        val c = build(listOf(a, b, self))
        assertEquals(3, c.messages.size)
        // Walking up from any node ends at a root.
        for (m in c.messages) {
            var at: String? = m.parent
            var steps = 0
            while (at != null) {
                at = c.of(at).parent
                assertTrue(steps++ < 5)
            }
        }
        assertNull(c.of("s").parent)
    }

    @Test
    fun deepChainsAreCappedAndTwoHundredMessagesParseQuickly() {
        val entries = ArrayList<Entry>()
        var history = ""
        for (i in 0 until 200) {
            val own = "Message number $i from ${if (i % 2 == 0) "Ana" else "Ben"} with some words to make it a line."
            val text = if (i == 0) own else "$own\n\nOn Tue, 4 Nov 2026, ${if (i % 2 == 0) "Ben" else "Ana"} wrote:\n" + history.lines().joinToString("\n") { "> $it" }
            entries += Entry(
                hex = "m$i", messageId = "id$i@x", inReplyTo = if (i == 0) emptyList() else listOf("id${i - 1}@x"),
                subject = if (i == 0) "Long" else "Re: Long", from = if (i % 2 == 0) ana else ben,
                sentAt = at("09:00").plusSeconds(i * 60L), text = text
            )
            history = text
        }
        val start = System.nanoTime()
        val c = build(entries.shuffled(java.util.Random(7)))
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $millis ms", millis < 10_000)
        assertEquals(200, c.messages.size)
        assertEquals((0 until 200).map { "m$it" }, c.messages.map { it.hex })
        assertEquals(ThreadMessage.DEPTH, c.messages.last().depth)
        assertTrue(c.messages.drop(1).all { it.reply.text.startsWith("Message number") && !it.reply.text.contains(">") })
    }

    @Test
    fun malformedAndHugeHtmlNeverThrows() {
        val broken = "<div><<<<b>Hi <a href='x>there</div><blockquote type=cite><p>old" + "<div>".repeat(5_000) + "&#xD800;&bogus;&#99999999;"
        val huge = "<p>" + "x".repeat(1_100_000) + "</p>"
        val unclosedComment = "Hello <!-- never closed <blockquote type=\"cite\">quoted"
        val attrs = "<div class=\"gmail_quote\" title=\"unbalanced>Own words</div>"
        val entries = listOf(
            Entry(hex = "b1", subject = "Broken", from = ana, sentAt = at("09:00"), html = broken),
            Entry(hex = "b2", subject = "Huge", from = ana, sentAt = at("09:01"), html = huge),
            Entry(hex = "b3", subject = "Comment", from = ana, sentAt = at("09:02"), html = unclosedComment),
            Entry(hex = "b4", subject = "Attrs", from = ana, sentAt = at("09:03"), html = attrs),
            Entry(hex = "b5", subject = "Empty", from = null, sentAt = null, receivedAt = null)
        )
        val c = build(entries)
        assertEquals(5, c.messages.size)
        val first = c.of("b1").reply
        assertTrue(first.text.contains("Hi"))
        assertTrue(first.quoted.orEmpty().contains("old"))
        assertEquals(Confidence.Low, c.of("b2").reply.confidence)
        assertEquals(huge, c.of("b2").reply.html)
        assertTrue(c.of("b3").reply.text.startsWith("Hello"))
    }

    @Test
    fun aServerReplyIsUsedAsItIsAndALowOneFallsBack() {
        val root = Entry(hex = "m1", messageId = "<root@x>", subject = "Budget", from = ana, sentAt = at("09:00"), text = "Numbers attached.")
        val server = Reply(
            text = "Looks good.", html = "<p>Looks good.</p>", quotedId = "root@x", author = "Ana Kimani",
            signature = "Ben", confidence = Confidence.High, source = ReplySource.Server
        )
        val m2 = Entry(hex = "m2", messageId = "<two@x>", inReplyTo = listOf("<root@x>"), subject = "Re: Budget", from = ben, sentAt = at("10:00"), reply = server)
        val low = Reply(
            text = "Fine by me.\n\nOn Tue, Ben O. <ben@example.org> wrote:\n> Looks good.", confidence = Confidence.Low, source = ReplySource.Server
        )
        val m3 = Entry(hex = "m3", messageId = "<three@x>", inReplyTo = listOf("<two@x>"), subject = "Re: Budget", from = chloe, sentAt = at("11:00"), reply = low)
        val c = build(listOf(root, m2, m3))
        val used = c.of("m2").reply
        assertEquals(server.copy(quotedFrom = "m1"), used)
        val fallback = c.of("m3").reply
        assertEquals("Fine by me.", fallback.text)
        assertEquals(ReplySource.Heuristic, fallback.source)
        assertEquals(Confidence.High, fallback.confidence)
        assertEquals("m2", fallback.quotedFrom)
    }

    @Test
    fun aSpentBudgetLeavesTheMessageWhole() {
        val root = Entry(hex = "m1", messageId = "<root@x>", subject = "Trip", from = ana, sentAt = at("09:00"), text = "Who comes?")
        val text = "Me!\n\nOn Tue, Ana wrote:\n> Who comes?"
        val m2 = Entry(hex = "m2", messageId = "<two@x>", inReplyTo = listOf("<root@x>"), subject = "Re: Trip", from = ben, sentAt = at("10:00"), text = text)
        val c = Threads.build("t1", listOf(root, m2), now, budget = 0)
        assertEquals(2, c.messages.size)
        val reply = c.of("m2").reply
        assertEquals(text, reply.text)
        assertEquals(Confidence.Low, reply.confidence)
        assertEquals(ReplySource.None, reply.source)
    }

    @Test
    fun theServerQuoteStartIsTheFirstStep() {
        val root = Entry(hex = "m1", messageId = "<root@x>", subject = "Trip", from = ana, sentAt = at("09:00"), text = "Who comes?")
        val text = "Me!\nI will bring snacks.\nWho comes?"
        val m2 = Entry(hex = "m2", messageId = "<two@x>", inReplyTo = listOf("<root@x>"), subject = "Re: Trip", from = ben, sentAt = at("10:00"), text = text, quoteStart = text.indexOf("Who"))
        val reply = build(listOf(root, m2)).of("m2").reply
        assertEquals("Me!\nI will bring snacks.", reply.text)
        assertEquals(ReplySource.Server, reply.source)
        assertEquals("Who comes?", reply.quoted)
    }

    @Test
    fun anAncestorQuotedWithoutMarkersIsFoundByDiff() {
        val parentText = "Here is the plan for the offsite.\nWe leave Friday at 8.\nBring warm clothes.\nRooms are shared."
        val root = Entry(hex = "m1", messageId = "<root@x>", subject = "Offsite", from = ana, sentAt = at("09:00"), text = parentText)
        val reply = "Count me in.\n\nAna Kimani | 4 Nov\n" + parentText.replace("Rooms are shared.", "Rooms are  SHARED.")
        val m2 = Entry(hex = "m2", messageId = "<two@x>", inReplyTo = listOf("<root@x>"), subject = "Re: Offsite", from = ben, sentAt = at("10:00"), text = reply)
        val r = build(listOf(root, m2)).of("m2").reply
        assertEquals(ReplySource.Diff, r.source)
        assertEquals(Confidence.Medium, r.confidence)
        assertTrue(r.text.startsWith("Count me in."))
        assertFalse(r.text.contains("warm clothes"))
        assertEquals("m1", r.quotedFrom)
    }

    @Test
    fun aRepeatedTrailingBlockIsTheSendersSignature() {
        val sig = "Ben Otieno\nHead of Finance, Example Ltd\n+254 700 000 000"
        val m1 = Entry(hex = "m1", messageId = "<1@x>", subject = "Q", from = ben, sentAt = at("09:00"), text = "First question.\n\n$sig")
        val m2 = Entry(hex = "m2", messageId = "<2@x>", inReplyTo = listOf("<1@x>"), subject = "Re: Q", from = ana, sentAt = at("09:30"), text = "Answer.")
        val m3 = Entry(hex = "m3", messageId = "<3@x>", inReplyTo = listOf("<2@x>"), subject = "Re: Q", from = ben, sentAt = at("10:00"), text = "Thanks, clear.\n\n$sig")
        val c = build(listOf(m1, m2, m3))
        assertEquals("First question.", c.of("m1").reply.text)
        assertEquals(sig, c.of("m1").reply.signature)
        assertEquals("Thanks, clear.", c.of("m3").reply.text)
        assertEquals(sig, c.of("m3").reply.signature)
        assertNull(c.of("m2").reply.signature)
    }

    @Test
    fun aForwardIsACardNotAQuote() {
        val html = "<div dir=\"ltr\">FYI<br><br><div class=\"gmail_quote\"><div dir=\"ltr\" class=\"gmail_attr\">---------- Forwarded message ---------<br>" +
            "From: <b>Shop</b> &lt;orders@shop.example&gt;<br>Date: Mon, 3 Nov 2026 at 10:00<br>Subject: Your order<br>To: &lt;ana@example.com&gt;<br></div><br><br>" +
            "<div>Your order has shipped.</div></div></div>"
        val text = "Begin forwarded message:\n\nFrom: Shop <orders@shop.example>\nSubject: Your order\nDate: 3 November 2026\n\nYour order has shipped."
        val c = build(
            listOf(
                Entry(hex = "f1", subject = "Fwd: Your order", from = ana, sentAt = at("09:00"), html = html),
                Entry(hex = "f2", subject = "Fwd: Your order", from = ben, sentAt = at("09:10"), text = text)
            )
        )
        val gmail = c.of("f1").reply
        assertEquals("FYI", gmail.text)
        assertNotNull(gmail.forwarded)
        assertEquals("Your order", gmail.forwarded!!.subject)
        assertTrue(gmail.forwarded!!.text.contains("shipped"))
        assertNull(gmail.quoted)
        val apple = c.of("f2").reply
        assertEquals("", apple.text)
        assertEquals("Shop <orders@shop.example>", apple.forwarded!!.from)
        assertEquals("Your order has shipped.", apple.forwarded!!.text)
    }

    @Test
    fun attributionsInOtherLanguages() {
        val lines = listOf(
            "Le mar. 4 nov. 2026 à 09:12, Ana Kimani <ana@example.com> a écrit :",
            "Am 04.11.2026 um 09:12 schrieb Ana Kimani <ana@example.com>:",
            "El mar, 4 nov 2026 a las 9:12, Ana Kimani (<ana@example.com>) escribió:",
            "Em ter., 4 de nov. de 2026 às 09:12, Ana Kimani <ana@example.com> escreveu:",
            "Op di 4 nov. 2026 om 09:12 schreef Ana Kimani <ana@example.com>:",
            "Il giorno mar 4 nov 2026 alle ore 09:12 Ana Kimani <ana@example.com> ha scritto:",
            "Mnamo Jumanne, 4 Nov 2026, Ana Kimani <ana@example.com> aliandika:",
            "Ana Kimani <ana@example.com> 于2026年11月4日周二 09:12写道：",
            "-----Original Message-----",
            "-----Ursprüngliche Nachricht-----"
        )
        val entries = lines.mapIndexed { i, line ->
            Entry(hex = "l$i", subject = "Lang $i", from = ben, sentAt = at("09:00").plusSeconds(i.toLong()), text = "Own words $i.\n\n$line\n> quoted")
        }
        val c = build(entries)
        lines.indices.forEach { i ->
            val r = c.of("l$i").reply
            assertEquals(lines[i], "Own words $i.", r.text)
            assertEquals(lines[i], ReplySource.Heuristic, r.source)
        }
    }

    @Test
    fun aSentDateFarAheadOrdersByArrival() {
        val root = Entry(hex = "m1", messageId = "<root@x>", subject = "S", from = ana, sentAt = at("09:00"), text = "root")
        val skewed = Entry(
            hex = "m2", messageId = "<2@x>", inReplyTo = listOf("<root@x>"), subject = "Re: S", from = ben,
            sentAt = Instant.parse("2028-01-01T00:00:00Z"), receivedAt = at("09:30"), text = "first"
        )
        val later = Entry(hex = "m3", messageId = "<3@x>", inReplyTo = listOf("<root@x>"), subject = "Re: S", from = chloe, sentAt = at("10:00"), receivedAt = at("10:00"), text = "second")
        val c = build(listOf(later, skewed, root))
        assertEquals(listOf("m1", "m2", "m3"), c.messages.map { it.hex })
        assertEquals(at("09:30"), c.of("m2").time)
    }

    @Test
    fun securitySummaryPointsAtThePlainMessage() {
        val c = build(
            listOf(
                Entry(hex = "m1", subject = "S", from = ana, sentAt = at("09:00"), security = Security(transport = Transport(tls = true))),
                Entry(hex = "m2", subject = "T", from = ben, sentAt = at("09:10"), security = Security(transport = Transport(tls = false, from = "old.example"))),
                Entry(hex = "m3", subject = "U", from = ben, sentAt = at("09:20"))
            )
        )
        assertTrue(c.security.available)
        assertFalse(c.security.encrypted)
        assertEquals("m2", c.security.plain)
        assertFalse(build(listOf(Entry(hex = "x", subject = "S"))).security.available)
    }
}
