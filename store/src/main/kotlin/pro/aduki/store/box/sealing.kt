package pro.aduki.store.box

import io.objectbox.BoxStore
import io.objectbox.converter.PropertyConverter
import pro.aduki.crypto.cipher.Vault
import pro.aduki.store.entities.Appointment
import pro.aduki.store.entities.Contact
import pro.aduki.store.entities.Message
import pro.aduki.store.entities.Outbox

/**
 * Sealing holds the process-wide [Vault] the field converters use (ObjectBox
 * instantiates converters itself, so they cannot take a key argument).
 *
 * With no vault installed, values are written in the clear and sealed values
 * cannot be read (they fail closed). Only the sensitive payload columns are
 * sealed: `Message.preview`, `Message.blob`, `Contact.vcard`, `Contact.company`,
 * `Appointment.notes`, and `Outbox.payload` (through the outbox storage, not a converter: the ObjectBox generator cannot convert a byte-array column). Names, subjects, e-mail addresses,
 * hexes, flags, timestamps and every indexed column stay in the clear so
 * queries and sync work; the rest of the file relies on OS protection.
 */
object Sealing {
    @Volatile
    private var vault: Vault? = null

    fun install(vault: Vault?) {
        this.vault = vault
    }

    fun vault(): Vault? = vault

    private fun <T> resave(store: BoxStore, type: Class<T>): Int {
        val box = store.boxFor(type)
        val all = box.all
        box.put(all)
        return all.size
    }

    /** Bytes ready to store: sealed when a vault is installed. */
    fun seal(plain: ByteArray): ByteArray = vault?.sealBytes(plain) ?: plain

    /** Bytes read back: opened when sealed (fails closed without a vault); legacy plaintext passes through. */
    fun open(stored: ByteArray): ByteArray {
        val v = vault
        if (v == null) {
            check(!Vault.isSealed(stored)) { "Sealed value but no vault is installed" }
            return stored
        }
        return v.openBytes(stored)
    }

    /**
     * Puts an outbox entry with its payload sealed (ObjectBox cannot convert a
     * byte-array column); [entry] keeps its plaintext payload and gets its id.
     */
    fun put(box: io.objectbox.Box<Outbox>, entry: Outbox): Long {
        val plain = entry.payload
        entry.payload = seal(plain)
        try {
            return box.put(entry)
        } finally {
            entry.payload = plain
        }
    }

    /** [entry] with its payload opened. */
    fun opened(entry: Outbox): Outbox = entry.also { it.payload = open(it.payload) }

    /**
     * Rewrites every row so legacy plaintext and values sealed under an older
     * key are sealed under the current key. Returns the number of rows rewritten.
     */
    fun reseal(store: BoxStore): Int {
        var count = resave(store, Message::class.java) + resave(store, Contact::class.java) +
            resave(store, Appointment::class.java)
        val outbox = store.boxFor(Outbox::class.java)
        for (entry in outbox.all) {
            entry.payload = open(entry.payload)
            put(outbox, entry)
            count++
        }
        return count
    }
}

/** Seals a text column. */
class SealedText : PropertyConverter<String, String> {
    override fun convertToEntityProperty(databaseValue: String?): String {
        if (databaseValue == null) return ""
        val v = Sealing.vault()
        if (v == null) {
            check(!databaseValue.startsWith(Vault.TEXT)) { "Sealed value but no vault is installed" }
            return databaseValue
        }
        return v.openText(databaseValue)
    }

    override fun convertToDatabaseValue(entityProperty: String?): String {
        val text = entityProperty ?: ""
        return Sealing.vault()?.sealText(text) ?: text
    }
}
