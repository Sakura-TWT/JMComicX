package dev.jmx.client.core.download

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

interface ByteSink {
    fun write(bytes: ByteArray)
}

class MemoryByteSink : ByteSink, TruncatingSink {
    private val chunks = mutableListOf<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks += bytes.copyOf()
    }

    override fun truncate() {
        chunks.clear()
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

class FileByteSink(
    private val path: Path,
    append: Boolean = false
) : ByteSink, TruncatingSink, AutoCloseable {
    private var stream: OutputStream = open(append)

    private fun open(append: Boolean): OutputStream {
        if (path.parent != null) {
            Files.createDirectories(path.parent)
        }
        val options = if (append) {
            arrayOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            )
        } else {
            arrayOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        }
        return Files.newOutputStream(path, *options)
    }

    override fun write(bytes: ByteArray) {
        stream.write(bytes)
        stream.flush()
    }

    override fun truncate() {
        stream.close()
        stream = open(append = false)
    }

    override fun close() {
        stream.close()
    }
}
