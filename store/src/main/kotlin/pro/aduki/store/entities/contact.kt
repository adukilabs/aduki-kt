package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Contact represents an address book contact with search indexes.
 */
@Entity
data class Contact(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var name: String = "",
    @Index var email: String = "",
    var phone: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var company: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var vcard: String = "",
    var ctag: String = "",
    var updated: Long = 0
)
