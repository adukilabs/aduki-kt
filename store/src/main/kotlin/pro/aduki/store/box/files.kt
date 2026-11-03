package pro.aduki.store.box

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Files keeps byte values (contact photos, queued uploads) as files in
 * [dir], one per name, sealed like the store's columns ([Sealing.seal]).
 * With no [dir] they are kept in memory for the life of the process.
 *
 * A write goes to a temporary file first and is renamed into place, so a
 * reader never sees half a file. Names are 1 to 128 characters of letters,
 * digits, `.`, `_` and `-`.
 */
class Files(private val dir: File?) {
    private val memory = ConcurrentHashMap<String, ByteArray>()

    /** Whether values survive the process (a directory was given). */
    val durable: Boolean get() = dir != null

    /** The value stored as [name], opened, or null when there is none or it cannot be opened. */
    fun read(name: String): ByteArray? {
        check(name)
        if (dir == null) return memory[name]?.copyOf()
        val file = File(dir, name)
        if (!file.isFile) return null
        return try {
            Sealing.open(file.readBytes())
        } catch (_: Exception) {
            // Sealed under a key that is gone, or damaged: as good as absent.
            file.delete()
            null
        }
    }

    /** Stores [bytes] as [name], sealed, replacing what was there. */
    fun write(name: String, bytes: ByteArray) {
        check(name)
        if (dir == null) {
            memory[name] = bytes.copyOf()
            return
        }
        dir.mkdirs()
        val temp = File(dir, ".$name.tmp")
        temp.writeBytes(Sealing.seal(bytes))
        if (!temp.renameTo(File(dir, name))) {
            temp.delete()
            throw java.io.IOException("Could not store $name")
        }
    }

    /** Removes [name]; nothing happens when it is not there. */
    fun delete(name: String) {
        check(name)
        if (dir == null) memory.remove(name) else File(dir, name).delete()
    }

    /** Whether [name] is stored. */
    fun has(name: String): Boolean {
        check(name)
        return if (dir == null) memory.containsKey(name) else File(dir, name).isFile
    }

    /** The stored names that start with [prefix]. */
    fun names(prefix: String = ""): List<String> =
        if (dir == null) memory.keys.filter { it.startsWith(prefix) }
        else dir.list()?.filter { it.startsWith(prefix) && !it.startsWith(".") }.orEmpty()

    private fun check(name: String) {
        require(name.length in 1..128 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' } && !name.startsWith(".")) {
            "Bad file name '$name'"
        }
    }
}
