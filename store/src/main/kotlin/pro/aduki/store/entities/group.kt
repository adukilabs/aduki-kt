package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import pro.aduki.store.box.SealedText

/**
 * Group is a contact group as the server lists it (`GET /user/contacts/groups`),
 * or as created on this device and not sent yet. Members carry the group
 * as a tag (`Contact.groups`); this row is what keeps a group without
 * members listed.
 */
@Entity
data class Group(
    @Id var id: Long = 0,
    @Convert(converter = SealedText::class, dbType = String::class) var name: String = "",
    /** Members on the server at the last sync; 0 for an empty group or one made here. */
    var count: Int = 0
)
