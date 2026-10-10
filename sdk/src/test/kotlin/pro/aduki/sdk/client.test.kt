package pro.aduki.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.aduki.core.models.Identity
import pro.aduki.state.repository.ContactSource
import pro.aduki.state.repository.MailSource
import pro.aduki.core.models.Tokens

import pro.aduki.store.entities.Contact as ContactEntity
import pro.aduki.store.entities.Mailbox
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox
import pro.aduki.sync.outbox.Dispatcher
import pro.aduki.sync.outbox.Manager
import pro.aduki.sync.outbox.Storage
import pro.aduki.sync.outbox.Worker
import pro.aduki.state.repository.Contact as ContactRepo
import pro.aduki.state.repository.Mail as MailRepo

class ClientTest {

    private class TestStorage : Storage {
        val messages = mutableMapOf<String, Message>()
        val outbox = mutableMapOf<Long, Outbox>()
        private var nextId = 1L

        override fun getMessage(hex: String): Message? = messages[hex]
        override fun putMessage(msg: Message) { messages[msg.hex] = msg }
        override fun getOutbox(id: Long): Outbox? = outbox[id]
        override fun putOutbox(entry: Outbox): Long {
            val id = if (entry.id == 0L) nextId++ else entry.id
            outbox[id] = entry.copy(id = id)
            return id
        }
        override fun removeOutbox(id: Long) { outbox.remove(id) }
        override fun pending(): List<Outbox> = outbox.values.sortedBy { it.created }
        override fun <T> tx(block: () -> T): T = block()
    }

    private class TestMailSource : MailSource {
        val mailboxList = MutableStateFlow<List<Mailbox>>(emptyList())
        val messageList = MutableStateFlow<List<Message>>(emptyList())

        override fun mailboxes(): Flow<List<Mailbox>> = mailboxList
        override fun messages(mailboxHex: String): Flow<List<Message>> = messageList
        override fun message(hex: String): Flow<Message?> =
            MutableStateFlow(messageList.value.firstOrNull { it.hex == hex })
        override fun getMessage(hex: String): Message? =
            messageList.value.firstOrNull { it.hex == hex }
        override fun getMailbox(hex: String): Mailbox? =
            mailboxList.value.firstOrNull { it.hex == hex }
    }

    private class TestContactSource : ContactSource {
        val contactList = MutableStateFlow<List<ContactEntity>>(emptyList())
        override fun contacts(): Flow<List<ContactEntity>> = contactList
        override fun get(hex: String): ContactEntity? =
            contactList.value.firstOrNull { it.hex == hex }
    }

    @Test
    fun testBuilder() {
        val client = Aduki.builder()
            .key("hm_live_validkey123")
            .endpoint("https://mail.aduki.pro/v1")
            .timeout(30)
            .secure(true)
            .build()

        assertNotNull(client)
        assertEquals("hm_live_validkey123", client.apiKey)
        assertEquals("https://mail.aduki.pro/v1", client.options.endpoint)
        assertEquals(30L, client.options.timeoutSeconds)
        assertTrue(client.options.secure)
        assertNotNull(client.mail)
        assertNotNull(client.contacts)
        assertNotNull(client.sync)
        assertNotNull(client.lifecycle)
    }

    @Test
    fun testInteractiveTokenBuilder() {
        val client = Aduki.builder()
            .token("jwt_sample_token_123")
            .endpoint("https://mail.aduki.pro/v1")
            .build()

        assertNotNull(client)
        assertEquals("jwt_sample_token_123", client.token)
    }

    @Test
    fun testEmptyKey() {
        assertThrows(IllegalArgumentException::class.java) {
            Aduki.builder()
                .key("")
                .build()
        }
    }

    @Test
    fun testLifecycleTransitions() {
        val client = Aduki.builder()
            .key("hm_test_key")
            .build()

        assertTrue(client.lifecycle.active())

        client.pause()
        assertFalse(client.lifecycle.active())

        client.resume()
        assertTrue(client.lifecycle.active())
    }

    @Test
    fun testLifecycleListenerRemoval() {
        val lifecycle = Lifecycle()
        var count = 0
        val listener: (Boolean) -> Unit = { count++ }
        lifecycle.listen(listener)
        lifecycle.pause()
        assertEquals(1, count)
        lifecycle.remove(listener)
        lifecycle.resume()
        assertEquals(1, count)
    }

    @Test
    fun testMailFacadeSendAndOutbox() = runBlocking {
        val storage = TestStorage()
        val manager = Manager(storage)
        val dispatched = mutableListOf<String>()
        val worker = Worker(manager, Dispatcher { action ->
            dispatched.add(action.action)
        })
        val mailSource = TestMailSource()
        val mailRepo = MailRepo(source = mailSource, manager = manager, scope = CoroutineScope(Dispatchers.Unconfined))

        val client = Aduki.builder()
            .key("hm_test_key")
            .manager(manager)
            .worker(worker)
            .mail(mailRepo)
            .build()

        val msg = client.mail.send(
            to = listOf("recipient@example.com"),
            subject = "Fast Aduki",
            body = "Hello from Android SDK"
        )

        assertNotNull(msg)
        assertEquals("recipient@example.com", msg.to)
        assertEquals(1, dispatched.size)
        assertEquals("send", dispatched[0])
    }

    @Test
    fun testContactsFacadeSearch() = runBlocking {
        val contactSource = TestContactSource()
        contactSource.contactList.value = listOf(
            ContactEntity(hex = "c1", name = "Alice Adams", email = "alice@aduki.pro"),
            ContactEntity(hex = "c2", name = "Bob Builder", email = "bob@aduki.pro")
        )
        val contactRepo = ContactRepo(source = contactSource, scope = CoroutineScope(Dispatchers.Unconfined))

        val client = Aduki.builder()
            .key("hm_test_key")
            .contacts(contactRepo)
            .build()

        val search = client.contacts.search("Alice")
        assertNotNull(search)
        val results = search!!.value
        assertEquals(1, results.size)
        assertEquals("Alice Adams", results[0].name)
    }

    @Test
    fun testCachedIdentityResolution() = runBlocking {
        val client = Aduki.builder()
            .key("hm_test_key")
            .build()

        val identity = Identity(
            user = "usr_007",
            tenant = "ten_007",
            owner = true,
            tier = "enterprise"
        )
        client.session.update(identity)

        val resolved = client.me()
        assertNotNull(resolved)
        assertEquals("usr_007", resolved!!.user)
        assertEquals("ten_007", resolved.tenant)
    }

    @Test
    fun testSessionTokensAndLogout() = runBlocking {
        val client = Aduki.builder()
            .key("hm_test_key")
            .build()

        val tokens = Tokens(
            token = "jwt_user_access",
            refresh = "rt_user_refresh",
            expires = "2026-09-08T22:00:00Z"
        )
        client.session.update(tokens)

        assertEquals("jwt_user_access", client.session.token())
        assertEquals("rt_user_refresh", client.session.refresh())

        // Logout resets session state
        client.session.clear()
        assertNull(client.session.token())
        assertNull(client.session.identity.value)
    }
}
