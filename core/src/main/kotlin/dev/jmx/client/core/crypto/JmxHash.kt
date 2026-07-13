package dev.jmx.client.core.crypto

import java.security.MessageDigest

object JmxHash {
    fun md5Hex(value: String): String {
        return MessageDigest
            .getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
