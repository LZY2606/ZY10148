package app

import java.security.MessageDigest

object Hashing {
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(Character.forDigit(v ushr 4, 16))
            append(Character.forDigit(v and 0xf, 16))
        }
    }

    fun sha256Hex(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
