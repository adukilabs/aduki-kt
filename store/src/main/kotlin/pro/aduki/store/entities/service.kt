package pro.aduki.store.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import pro.aduki.store.box.SealedText

/**
 * Service represents a meeting type or booking template.
 */
@Entity
data class Service(
    @Id var id: Long = 0,
    @Index var hex: String = "",
    @Index var tenant: String = "",
    @Index var user: String = "",
    @Index var slug: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var name: String = "",
    @Convert(converter = SealedText::class, dbType = String::class) var description: String = "",
    var duration: Int = 30,
    var buffer: Int = 0,
    var notice: Int = 60,
    var horizon: Int = 30,
    var increment: Int = 15,
    var active: Boolean = true,
    var updated: Long = 0L
)

