package dev.jmx.client

import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

private val PDF_PNG_CHUNKS = setOf("IHDR", "IDAT", "IEND", "sRGB", "pHYs", "tEXt", "zTXt", "iTXt")

internal data class PdfPngRange(val offset: Long, val length: Int)
internal data class PdfPng(val width: Int, val height: Int, val colors: Int, val ranges: List<PdfPngRange>) {
    val length: Long get() = ranges.sumOf { it.length.toLong() }
    val colorSpace: String get() = if (colors == 1) "/DeviceGray" else "/DeviceRGB"
}

/** PDF's PNG predictor accepts non-interlaced 8-bit gray/RGB scanlines without re-encoding pixels. */
internal fun readPdfPng(file: File, checkActive: () -> Unit = {}): PdfPng? {
    val fileLength = file.length()
    if (fileLength !in 45..32L * 1024 * 1024) return null
    val inflater = Inflater()
    try {
        return file.inputStream().buffered(64 * 1024).use { stream ->
            val input = DataInputStream(stream)
            val signature = ByteArray(8).also(input::readFully)
            if (!signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) return null
            val buffer = ByteArray(64 * 1024)
            val inflated = ByteArray(64 * 1024)
            val ranges = mutableListOf<PdfPngRange>()
            var width = 0
            var height = 0
            var colors = 0
            var rowBytes = 0L
            var decodedBytes = 0L
            var offset = 8L
            var afterData = false
            while (offset < fileLength) {
                checkActive()
                val length = input.readInt()
                require(length >= 0 && length.toLong() + 12 <= fileLength - offset)
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                if (offset == 8L && type != "IHDR") return null
                if (type !in PDF_PNG_CHUNKS) return null
                if (type == "sRGB" && (length != 1 || ranges.isNotEmpty())) return null
                if (type == "IDAT") {
                    require(width > 0 && !afterData && ranges.size < 4096)
                    ranges += PdfPngRange(offset + 8, length)
                } else if (ranges.isNotEmpty()) afterData = true
                val crc = CRC32().apply { update(typeBytes) }
                if (type == "IHDR") {
                    require(offset == 8L && length == 13)
                    val header = ByteArray(13).also(input::readFully)
                    crc.update(header)
                    val headerInput = DataInputStream(header.inputStream())
                    width = headerInput.readInt()
                    height = headerInput.readInt()
                    val depth = headerInput.readUnsignedByte()
                    colors = when (headerInput.readUnsignedByte()) { 0 -> 1; 2 -> 3; else -> return null }
                    if (width !in 1..2048 || height <= 0 || width.toLong() * height > 4_000_000 || depth != 8 ||
                        headerInput.readUnsignedByte() != 0 || headerInput.readUnsignedByte() != 0 || headerInput.readUnsignedByte() != 0) return null
                    rowBytes = width.toLong() * colors + 1
                } else {
                    var remaining = length
                    while (remaining > 0) {
                        checkActive()
                        val count = minOf(remaining, buffer.size)
                        input.readFully(buffer, 0, count)
                        crc.update(buffer, 0, count)
                        if (type == "sRGB") require(buffer[0].toInt() in 0..3)
                        if (type == "IDAT") {
                            require(!inflater.finished())
                            inflater.setInput(buffer, 0, count)
                            while (true) {
                                checkActive()
                                val size = inflater.inflate(inflated)
                                require(decodedBytes + size <= rowBytes * height)
                                var filter = ((rowBytes - decodedBytes % rowBytes) % rowBytes).toInt()
                                while (filter < size) {
                                    require(inflated[filter].toInt() in 0..4)
                                    filter += rowBytes.toInt()
                                }
                                decodedBytes += size
                                if (inflater.finished()) {
                                    require(inflater.remaining == 0)
                                    break
                                }
                                require(size > 0 || inflater.needsInput())
                                if (size == 0 && inflater.needsInput()) break
                            }
                        }
                        remaining -= count
                    }
                }
                require(input.readInt().toLong() and 0xffffffffL == crc.value)
                offset += length + 12L
                if (type == "IEND") {
                    require(length == 0 && ranges.isNotEmpty() && inflater.finished() && decodedBytes == rowBytes * height)
                    require(offset == fileLength)
                    return PdfPng(width, height, colors, ranges)
                }
            }
            null
        }
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}

internal fun copyPdfPng(file: File, image: PdfPng, output: OutputStream, buffer: ByteArray, checkActive: () -> Unit = {}) {
    RandomAccessFile(file, "r").use { input ->
        image.ranges.forEach { range ->
            input.seek(range.offset)
            var remaining = range.length
            while (remaining > 0) {
                checkActive()
                val count = minOf(remaining, buffer.size)
                input.readFully(buffer, 0, count)
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }
}
