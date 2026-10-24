package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedList
import pro.aduki.store.box.SealedText
import pro.aduki.store.box.Sealing

/**
 * Contact represents an address book contact with search indexes.
 *
 * `email` and `phone` are the first of `emails` and `phones` (the blind
 * indexes and the search cover them); `groups` are the server's group tags.
 */
@Entity
data class Contact(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var name: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var email: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var phone: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var company: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var vcard: String = "",
    var ctag: String = "",
    var updated: Long = 0,
    /** Keyed blind index of the normalised e-mail (`Sealing.index`), for exact lookups. */
    @Index var emailIndex: String = Sealing.index("email", email),
    /** Keyed blind index of the normalised phone. */
    @Index var phoneIndex: String = Sealing.index("phone", phone),
    /** The vCard UID; matches imported cards to stored ones. */
    @Index var uid: String = "",
    @Convert(converter = SealedList::class, dbType = String::class) var emails: List<String> = emptyList(),
    @Convert(converter = SealedList::class, dbType = String::class) var phones: List<String> = emptyList(),
    @Convert(converter = SealedList::class, dbType = String::class) var groups: List<String> = emptyList(),
    @Convert(converter = SealedText::class, dbType = String::class) var notes: String = "",
    /** A `data:` URI or a URL; "" for none. */
    @Convert(converter = SealedText::class, dbType = String::class) var photo: String = ""
)
