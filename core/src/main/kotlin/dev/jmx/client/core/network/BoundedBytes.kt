package dev.jmx.client.core.network

import okio.Buffer
import okio.BufferedSource
import java.io.IOException

/** Bounded consumption without trusting Content-Length. Caller owns and closes the source. */
fun BufferedSource.readBoundedBytes(maxBytes: Long): ByteArray {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    val buffer = Buffer()
    while (true) {
        val count = read(buffer, minOf(8192L, maxBytes + 1 - buffer.size))
        if (count < 0) return buffer.readByteArray()
        if (buffer.size > maxBytes) throw IOException("Encoded image exceeds its byte budget")
    }
}
