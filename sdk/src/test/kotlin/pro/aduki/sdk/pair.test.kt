package pro.aduki.sdk

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.aduki.core.config.Endpoints
import pro.aduki.core.errors.AdukiException
import pro.aduki.core.models.Draft
import pro.aduki.core.models.EventRequest
import pro.aduki.core.models.Response
import pro.aduki.core.models.Tokens
import pro.aduki.crypto.dpop.Key
import pro.aduki.crypto.dpop.Software
import pro.aduki.store.entities.Message
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Live checks between two accounts (adukilabs/suite#288): the Accounts
 * Center with a real second account, an invitation answered by the other
 * account, and mail both ways. Needs `ADUKI_LIVE=true` and, for each
 * account, `ADUKI_LOGIN_HANDLE`/`PASSWORD`/`TOTP` and
 * `ADUKI_SECOND_HANDLE`/`PASSWORD`/`TOTP` (dedicated test accounts).
 *
 * The second account is left as it was found: every message, event and
 * session made here is removed, and nothing of its profile, password or
 * second factor is touched.
 */
class PairLiveTest {

    @get:Rule
    val dir = TemporaryFolder()

    private class Account(val handle: String, val password: String, val secret: String)

    private fun account(prefix: String): Account? {
        val handle = System.getenv("${prefix}_HANDLE")
        val password = System.getenv("${prefix}_PASSWORD")
        val secret = System.getenv("${prefix}_TOTP")
        if (handle.isNullOrBlank() || password.isNullOrBlank() || secret.isNullOrBlank()) return null
        return Account(handle, password, secret)
    }

    private lateinit var first: Account
    private lateinit var second: Account
    private val opened = mutableListOf<Aduki>()

    @Before
    fun require() {
        assumeTrue("Skipping pair live tests: set ADUKI_LIVE=true", System.getenv("ADUKI_LIVE") == "true")
        first = account("ADUKI_LOGIN") ?: return assumeTrue("set ADUKI_LOGIN_*", false)
        second = account("ADUKI_SECOND") ?: return assumeTrue("set ADUKI_SECOND_*", false)
    }

    // Every session opened here ends (Aduki ID revokes it), so neither account keeps one.
    @After
    fun end() = runBlocking {
        opened.forEach { runCatching { it.logout() }; runCatching { it.close() } }
    }

    private val endpoint = System.getenv("ADUKI_ENDPOINT") ?: Endpoints.REST

    // The time step each account's last code was used in: a code is single use.
    private val steps = HashMap<String, Long>()

    private fun code(account: Account): String {
        var step = System.currentTimeMillis() / 30_000
        // The step may already be spent (by this run or a script before it): start each account in a fresh one.
        if (steps[account.handle] == null || steps[account.handle] == step) {
            Thread.sleep(30_000 - System.currentTimeMillis() % 30_000 + 1_000)
            step = System.currentTimeMillis() / 30_000
        }
        steps[account.handle] = step
        return totp(account.secret)
    }

    // A sign-in bound to [key], with [link] a ticket; the client is kept to be signed out at the end.
    private suspend fun signIn(account: Account, key: Key, device: String, link: Boolean = false): Opened =
        Aduki.signIn(account.handle, account.password, code = code(account), endpoint = endpoint, dpop = key, device = device, link = link)
            .also { opened += it.client }

    // A client with a local store holding [tokens] (bound to [key]), as an app builds it.
    private fun stored(key: Key, tokens: Tokens): Aduki =
        Aduki.builder().endpoint(endpoint).dpop(key).token(tokens.token).store(dir.newFolder()).build()
            .also { it.session.update(tokens) }

    /**
     * suite#288 Accounts Center: a second account added in the same app,
     * switched to and from in process (identity, profile and contacts follow
     * the session), then removed from the device while the first stays.
     */
    @Test
    fun accountsCenterWithASecondAccount() = runBlocking<Unit> {
        val tag = "pair-${System.currentTimeMillis()}"
        val key = Software.p256() // the app's one device key
        val ada = signIn(first, key, "$tag ada", link = true)
        val bob = signIn(second, key, "$tag bob", link = true)
        val adaTokens = ada.client.session.tokens.value!!
        val bobTokens = bob.client.session.tokens.value!!
        assertNotEquals("each account has its own device group", ada.group, bob.group)
        val adaProfile = ada.client.account(refresh = true)!!
        val bobProfile = bob.client.account(refresh = true)!!
        assertNotEquals(adaProfile.hex, bobProfile.hex)

        // Adding Bob to Ada's Account Center: two personal accounts cannot share one,
        // so the app keeps Bob on the device only (AccountsCenter: Refusal.SecondPersonal).
        val ticket = bob.ticket ?: error("no link ticket")
        var linked = false
        try {
            ada.client.link(ticket)
            linked = true
        } catch (e: AdukiException.Network) {
            assertEquals("a second personal account is refused", 409, e.code)
        }
        if (linked) {
            // Not expected; undo it so both accounts are left as found.
            ada.client.unlink(code(first))
            bob.client.unlink(code(second))
        }
        assertFalse("two personal accounts are not linked", linked)
        assertEquals(null, ada.client.center().center)

        // One running client switched between the two (what the suite does on "Switch").
        val app = stored(key, adaTokens)
        try {
            val adaMe = app.me()!!
            assertEquals(adaProfile.handle.substringBefore('@'), adaMe.email.substringBefore('@'))
            app.contacts.sync()
            val adaBook = app.contactsApi.list().items.map { it.hex }.toSet()

            app.session.update(bobTokens)
            val bobMe = app.me()!!
            assertNotEquals("the identity follows the switch", adaMe.user, bobMe.user)
            assertEquals(bobProfile.hex, app.account()?.hex)
            app.contacts.sync()
            val bobBook = app.contactsApi.list().items.map { it.hex }.toSet()
            val local = bobBook.filter { app.contacts.get(it) != null }
            println("live: ada=${adaMe.user}/${adaMe.tenant} bob=${bobMe.user}/${bobMe.tenant} books ada=${adaBook.size} bob=${bobBook.size} local=${local.size} adaLeft=${adaBook.count { app.contacts.get(it) != null }}")
            assertEquals("the synced book holds the new account's contacts", bobBook.size, local.size)
            assertEquals("and none of the previous account's", 0, (adaBook - bobBook).count { app.contacts.get(it) != null })

            app.session.update(adaTokens)
            assertEquals(adaMe.user, app.me()?.user)
            assertEquals(adaProfile.hex, app.account()?.hex)
        } finally {
            app.close()
        }

        // Remove Bob from the device: his device group ends, Ada's session is untouched.
        assertTrue(bob.client.endDevice())
        val bobEnded = try { bob.client.refresh() } catch (e: AdukiException.Unauthorized) { false }
        assertFalse(bobEnded)
        assertTrue("Ada still renews", ada.client.refresh())
        assertNotNull(ada.client.me())
        assertEquals(adaProfile.hex, ada.client.account(refresh = true)?.hex)
    }

    /**
     * suite#288 Calendar: Ada invites Bob; Bob finds the invitation in his
     * mail (iMIP, also for an account on the same server), files it and
     * accepts, then declines, and Ada sees each answer on her event; Ada
     * reschedules and cancels, and Bob's mail brings each change to his copy.
     */
    @Test
    fun invitationAnsweredByTheOtherAccount() = runBlocking<Unit> {
        val tag = "rsvp${System.currentTimeMillis()}"
        val adaKey = Software.p256()
        val bobKey = Software.p256()
        val ada = stored(adaKey, signIn(first, adaKey, "$tag ada").client.session.tokens.value!!)
        val bob = stored(bobKey, signIn(second, bobKey, "$tag bob").client.session.tokens.value!!)
        val bobAddress = bob.me()!!.email
        val start = Instant.now().plus(Duration.ofDays(21)).truncatedTo(ChronoUnit.HOURS)
        var adaHex: String? = null
        // Bob's mail about the event, each message filed once.
        val filed = mutableSetOf<String>()
        val subjects = mutableListOf<String>()
        suspend fun mail(): List<String> {
            val rows = bob.mailApi.search(tag, limit = 100).items.filter { it.hex !in filed && !it.subject.startsWith("Response") }
            for (row in rows) {
                bob.scheduling.invitation(row.hex)
                filed += row.hex
                subjects += row.subject
            }
            return subjects
        }
        try {
            val made = ada.scheduling.create(
                EventRequest(title = "Invite $tag", start = start, end = start.plusSeconds(1800), attendees = listOf(bobAddress)),
                notify = true
            )
            ada.sync.flush()
            assertEquals(0, ada.sync.pending())
            adaHex = waitFor("Ada's event has its server id") {
                ada.scheduling.sync()
                ada.schedulingApi.events().firstOrNull { it.uid == made.uid }?.hex
            }
            assertEquals("Ada organizes it", ada.me()!!.email.lowercase(), ada.schedulingApi.events().first { it.uid == made.uid }.organizer.lowercase())

            // The invitation reaches Bob as an email (Aduki Mail sends iMIP to its own accounts too).
            val invited = waitFor("Bob gets the invitation by mail") {
                mail()
                bob.scheduling.appointments!!.value.firstOrNull { it.uid == made.uid && !it.local() }
            }
            assertTrue("the mail names the event: $subjects", subjects.any { it == "Invitation: Invite $tag" })
            assertEquals(ada.me()!!.email.lowercase(), invited.organizer.lowercase())
            for ((answer, partstat) in listOf(Response.Accepted to "ACCEPTED", Response.Declined to "DECLINED")) {
                bob.scheduling.respond(invited.hex, answer, address = bobAddress)
                assertEquals(0, bob.sync.pending())
                waitFor("Ada sees $partstat") {
                    ada.scheduling.sync()
                    ada.scheduling.get(adaHex)?.takeIf { partstat(it.ical, bobAddress) == partstat }
                }
            }

            ada.scheduling.reschedule(adaHex, start.plusSeconds(3600), start.plusSeconds(5400), notify = true)
            assertEquals(0, ada.sync.pending())
            waitFor("Bob sees the new time") {
                mail()
                bob.scheduling.appointments!!.value.firstOrNull { it.uid == made.uid && it.start == start.plusSeconds(3600).toEpochMilli() }
            }
            ada.scheduling.cancel(adaHex, "live $tag", notify = true)
            assertEquals(0, ada.sync.pending())
            waitFor("Bob sees it cancelled") {
                mail()
                bob.scheduling.appointments!!.value.firstOrNull { it.uid == made.uid && it.status == "cancelled" }
            }
            assertTrue("one mail per change: $subjects", subjects.count { it == "Invitation: Invite $tag" } >= 3)
        } finally {
            adaHex?.let { runCatching { ada.scheduling.remove(it, notify = true) } }
            ada.sync.flush()
            runCatching { ada.schedulingApi.events().filter { it.title.contains(tag) }.forEach { ada.schedulingApi.removeEvent(it.hex, notify = true) } }
            // Bob's copy of the event and every message about it go.
            runCatching { bob.scheduling.sync() }
            bob.scheduling.appointments?.value.orEmpty().filter { it.title.contains(tag) }.forEach { runCatching { bob.scheduling.remove(it.hex) } }
            bob.sync.flush()
            runCatching { bob.schedulingApi.events().filter { it.title.contains(tag) }.forEach { bob.schedulingApi.removeEvent(it.hex) } }
            // The CANCEL of the removal may still be on its way: let it land, then purge.
            Thread.sleep(5_000)
            purge(bob, tag)
            purge(ada, tag)
            ada.close()
            bob.close()
        }
        assertTrue("nothing of it is left with Bob", bobLeft(second, tag).isEmpty())
    }

    /**
     * suite#288 Mail between two accounts: Ada writes to Bob with Ada's
     * alias in Cc, Bob replies to all, Ada replies; each side sees one
     * conversation.
     */
    @Test
    fun mailBothWays() = runBlocking<Unit> {
        val tag = "duo${System.currentTimeMillis()}"
        val adaKey = Software.p256()
        val bobKey = Software.p256()
        val ada = stored(adaKey, signIn(first, adaKey, "$tag ada").client.session.tokens.value!!)
        val bob = stored(bobKey, signIn(second, bobKey, "$tag bob").client.session.tokens.value!!)
        val adaAddress = ada.me()!!.email
        val bobAddress = bob.me()!!.email
        val adaAlias = ada.settings.senders().map { it.email }.firstOrNull { !it.equals(adaAddress, true) } ?: adaAddress
        try {
            ada.mail.send(Draft(to = listOf(bobAddress), cc = listOf(adaAlias), subject = "Duo $tag", text = "hello ${tag}o"))
            assertEquals(0, ada.sync.pending())
            val atBob = arrived(bob, "${tag}o")
            assertTrue(atBob.ccList().any { it.equals(adaAlias, true) })

            // Bob replies to all: to Ada, Cc Ada's alias.
            bob.mail.send(Draft(to = listOf(adaAddress), cc = listOf(adaAlias), subject = "Re: Duo $tag", text = "reply ${tag}r", reply = atBob.hex))
            assertEquals(0, bob.sync.pending())
            val atAda = arrived(ada, "${tag}r")
            ada.mail.send(Draft(to = listOf(bobAddress), subject = "Re: Duo $tag", text = "again ${tag}a", reply = atAda.hex))
            assertEquals(0, ada.sync.pending())
            val back = arrived(bob, "${tag}a")

            assertEquals("Bob's side is one conversation", atBob.threadId, back.threadId)
            val adaThread = ada.mailApi.thread(atAda.threadId).items
            assertTrue("Ada's conversation holds her first message", adaThread.any { it.subject == "Duo $tag" })
            assertTrue("and her answer", adaThread.any { it.preview.contains("${tag}a") })
            assertTrue("Bob's original is answered", bob.mailApi.search("${tag}o").items.any { r -> r.hex == atBob.hex && r.flags.any { it.equals("\\Answered", true) } })
        } finally {
            purge(bob, tag)
            purge(ada, tag)
            ada.close()
            bob.close()
        }
    }

    // ---- helpers ----

    private suspend fun <T : Any> waitFor(what: String, rounds: Int = 20, read: suspend () -> T?): T {
        repeat(rounds) {
            try {
                read()?.let { return it }
            } catch (e: AdukiException.Network) {
                if (e.code != 429) throw e
                Thread.sleep(10_000)
            }
            Thread.sleep(2_000)
        }
        throw AssertionError("timed out: $what")
    }

    // The message whose preview holds [marker], once it is in the inbox.
    private suspend fun arrived(client: Aduki, marker: String): Message = waitFor("$marker arrives") {
        client.sync.all(listOf("inbox"))
        client.mail.find(marker, store = true).firstOrNull { m -> client.mail.getMailbox(m.mailbox)?.role == "inbox" }
            ?.let { client.mail.fetch(it.hex) }
    }

    // Deletes every message the server finds for [tag] in [client]'s account.
    private fun purge(client: Aduki, tag: String) {
        repeat(3) {
            val hits = runCatching { client.mailApi.search(tag, limit = 100).items }.getOrDefault(emptyList())
            if (hits.isEmpty()) return
            hits.forEach { runCatching { client.mailApi.delete(it.hex) } }
        }
    }

    // What the account still holds for [tag]: messages and events, read with a fresh sign-in.
    private suspend fun bobLeft(account: Account, tag: String): List<String> {
        val key = Software.p256()
        val client = signIn(account, key, "$tag check").client
        val mail = client.mailApi.search(tag, limit = 100).items.map { "mail ${it.subject}" }
        val events = client.schedulingApi.events().filter { it.title.contains(tag) }.map { "event ${it.title}" }
        return mail + events
    }

    // The PARTSTAT of [address] in an iCalendar object.
    private fun partstat(ical: String, address: String): String? {
        val unfolded = ical.replace("\r\n ", "").replace("\n ", "")
        val line = unfolded.lines().firstOrNull { it.startsWith("ATTENDEE") && it.contains(address, ignoreCase = true) } ?: return null
        return Regex("PARTSTAT=([A-Z-]+)").find(line)?.groupValues?.get(1)
    }

    // RFC 6238 code for [secret] (base32) at the current 30-second step.
    private fun totp(secret: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in secret.uppercase().filter { it in alphabet }) {
            value = (value shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((value shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(out.toByteArray(), "HmacSHA1"))
        val step = System.currentTimeMillis() / 1000 / 30
        val hash = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val number = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        return "%06d".format(number % 1_000_000)
    }
}
