package dev.jmx.client.core.download

interface ByteSink {
    fun write(bytes: ByteArray)
}

class MemoryByteSink : ByteSink {
    private val chunks = mutableListOf<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks += bytes.copyOf()
    }

    fun bytes(): ByteArray {
        val size = chunks.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}
