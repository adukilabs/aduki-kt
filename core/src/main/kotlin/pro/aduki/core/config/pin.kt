package pro.aduki.core.config

/**
 * A TLS public-key pin for one host: the base64 SHA-256 of the certificate's
 * SubjectPublicKeyInfo, written `sha256/<base64>`.
 *
 * Pinning is opt-in: the SDK ships no pins. Give at least two hashes per host
 * (the current key and a backup) so a key rotation does not lock clients out.
 * See the TLS page of the docs for the `openssl` command that prints a hash.
 */
data class Pin(val host: String, val hashes: List<String>) {

    constructor(host: String, vararg hashes: String) : this(host, hashes.toList())

    init {
        require(host.isNotBlank()) { "Pin host must not be blank" }
        require(hashes.isNotEmpty()) { "Pin for $host needs at least one hash" }
        hashes.forEach {
            require(it.startsWith("sha256/") && it.length > "sha256/".length) {
                "Pin hash for $host must look like sha256/<base64>"
            }
        }
    }
}
