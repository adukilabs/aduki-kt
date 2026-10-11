package pro.aduki.crypto.dpop

/**
 * Der converts an ASN.1 DER ECDSA signature (`SEQUENCE { INTEGER r, INTEGER s }`,
 * what the Android Keystore's `SHA256withECDSA` returns) to the fixed-width
 * `r || s` form JWS ES256 needs (RFC 7518 section 3.4).
 */
object Der {
    /** @throws IllegalArgumentException when [der] is not a well-formed two-integer sequence or a value does not fit [size]. */
    fun toRaw(der: ByteArray, size: Int): ByteArray {
        var at = 0
        fun byte(): Int {
            require(at < der.size) { "Truncated DER signature" }
            return der[at++].toInt() and 0xFF
        }
        fun length(): Int {
            val first = byte()
            if (first < 0x80) return first
            val count = first and 0x7F
            require(count in 1..2) { "Unsupported DER length" }
            var n = 0
            repeat(count) { n = (n shl 8) or byte() }
            return n
        }
        require(byte() == 0x30) { "Not a DER sequence" }
        val total = length()
        require(at + total == der.size) { "DER length does not match" }
        val out = ByteArray(size * 2)
        for (index in 0..1) {
            require(byte() == 0x02) { "Not a DER integer" }
            val len = length()
            require(len >= 1 && at + len <= der.size) { "Bad DER integer length" }
            var start = at
            var count = len
            while (count > 1 && der[start].toInt() == 0) { start++; count-- }
            require(count <= size) { "Integer too large for ${size * 8}-bit curve" }
            System.arraycopy(der, start, out, index * size + (size - count), count)
            at += len
        }
        require(at == der.size) { "Trailing bytes in DER signature" }
        return out
    }
}
