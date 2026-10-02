package dev.jmx.client.core.image

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * Lossless, 8-bit opaque RGB PNG output without a second full-image pixel buffer.
 *
 * [readArgbRows] fills the first width * rowCount elements with opaque, non-premultiplied
 * sRGB ARGB pixels. Its reused array must not be retained. Each request has at most 32 rows
 * and never crosses a move boundary. Empty [moves] means identity, as in ImagePipeline.
 * Nonempty moves must cover both source and destination exactly once; zero-height moves
 * are allowed. Validation happens before any output or pixel read.
 *
 * Owns neither [output] nor its flushing/durability. Exceptions, including cancellation,
 * propagate unchanged; callers must discard partial output. Scratch arrays are capped at
 * 8 MiB (plus the deflater's fixed working storage and O(moves.size) geometry).
 */
object StreamingPngEncoder {
    fun encode(
        width: Int,
        height: Int,
        output: OutputStream,
        readArgbRows: (sourceY: Int, rowCount: Int, dest: IntArray) -> Unit,
        moves: List<ImageSegmentMove>,
        checkActive: () -> Unit = {},
    ) {
        checkActive()
        require(width > 0 && height > 0) { "PNG dimensions must be positive" }
        val rowBytesLong = width.toLong() * 3
        require(rowBytesLong + 1 <= Int.MAX_VALUE) { "PNG row is too wide" }
        require(height.toLong() <= Long.MAX_VALUE / (rowBytesLong + 1)) { "PNG image size overflows" }
        val blockRows = minOf(MAX_BLOCK_ROWS, height, maxOf(1, BLOCK_PIXELS / width))
        val blockPixelsLong = width.toLong() * blockRows
        val scratchBytes = blockPixelsLong * 4 + rowBytesLong * 3 + 1 + IDAT_BYTES
        require(blockPixelsLong <= Int.MAX_VALUE && scratchBytes <= MAX_SCRATCH_BYTES) {
            "PNG row exceeds the encoder memory budget"
        }
        val orderedMoves = validateMoves(height, moves, checkActive)
        val rowBytes = rowBytesLong.toInt()
        val pixels = IntArray(blockPixelsLong.toInt())
        var current = ByteArray(rowBytes)
        var previous = ByteArray(rowBytes)
        val filtered = ByteArray(rowBytes + 1)
        val idat = ByteArray(IDAT_BYTES)
        val stream = DataOutputStream(output)
        val crc = CRC32()
        var idatSize = 0

        fun chunk(type: ByteArray, data: ByteArray, size: Int = data.size) {
            checkActive()
            crc.reset()
            crc.update(type)
            crc.update(data, 0, size)
            stream.writeInt(size)
            stream.write(type)
            stream.write(data, 0, size)
            stream.writeInt(crc.value.toInt())
        }

        val deflater = Deflater(Deflater.BEST_SPEED) // PNG requires the zlib wrapper, not raw DEFLATE.
        try {
            fun drain() {
                checkActive()
                idatSize += deflater.deflate(idat, idatSize, idat.size - idatSize)
                if (idatSize == idat.size) {
                    chunk(IDAT, idat, idatSize)
                    idatSize = 0
                }
            }

            checkActive()
            stream.write(SIGNATURE)
            val header = ByteArray(13)
            for (index in 0..3) {
                header[index] = (width ushr (24 - index * 8)).toByte()
                header[index + 4] = (height ushr (24 - index * 8)).toByte()
            }
            header[8] = 8 // Bits per channel.
            header[9] = 2 // RGB, no alpha; compression/filter/interlace methods remain zero.
            chunk(IHDR, header)

            for (move in orderedMoves) {
                var consumed = 0
                while (consumed < move.height) {
                    checkActive()
                    val count = minOf(blockRows, move.height - consumed)
                    readArgbRows(move.sourceY + consumed, count, pixels)
                    for (row in 0 until count) {
                        checkActive()
                        var channel = 0
                        val offset = row * width
                        for (x in 0 until width) {
                            val argb = pixels[offset + x]
                            require(argb ushr 24 == 255) { "RGB PNG requires opaque pixels" }
                            current[channel++] = (argb ushr 16).toByte()
                            current[channel++] = (argb ushr 8).toByte()
                            current[channel++] = argb.toByte()
                        }
                        var upScore = 0L
                        var subScore = 0L
                        for (index in 0 until rowBytes) {
                            val value = current[index].toInt() and 255
                            upScore += abs((value - (previous[index].toInt() and 255)).toByte().toInt())
                            val left = if (index >= 3) current[index - 3].toInt() and 255 else 0
                            subScore += abs((value - left).toByte().toInt())
                        }
                        val useUp = upScore <= subScore
                        filtered[0] = if (useUp) 2 else 1
                        for (index in 0 until rowBytes) {
                            val predictor = if (useUp) previous[index].toInt() and 255
                                else if (index >= 3) current[index - 3].toInt() and 255 else 0
                            filtered[index + 1] = ((current[index].toInt() and 255) - predictor).toByte()
                        }
                        deflater.setInput(filtered)
                        while (!deflater.needsInput()) drain()
                        val spare = previous
                        previous = current
                        current = spare
                    }
                    consumed += count
                }
            }
            checkActive()
            deflater.finish()
            while (!deflater.finished()) drain()
            if (idatSize > 0) chunk(IDAT, idat, idatSize)
            chunk(IEND, EMPTY)
        } finally {
            deflater.end()
        }
    }

    private fun validateMoves(
        height: Int,
        moves: List<ImageSegmentMove>,
        checkActive: () -> Unit,
    ): List<ImageSegmentMove> {
        if (moves.isEmpty()) return listOf(ImageSegmentMove(0, 0, height))
        val positive = moves.filter { move ->
            checkActive()
            require(move.height >= 0 && move.sourceY >= 0 && move.targetY >= 0 &&
                move.sourceY.toLong() + move.height <= height.toLong() &&
                move.targetY.toLong() + move.height <= height.toLong()) { "PNG move is outside the image" }
            move.height > 0
        }
        fun validateCoverage(ordered: List<ImageSegmentMove>, source: Boolean) {
            var next = 0L
            for (move in ordered) {
                checkActive()
                val start = if (source) move.sourceY else move.targetY
                require(start.toLong() == next) { "PNG moves overlap or leave missing rows" }
                next += move.height
            }
            require(next == height.toLong()) { "PNG moves do not cover the image" }
        }
        validateCoverage(positive.sortedBy { it.sourceY }, source = true)
        return positive.sortedBy { it.targetY }.also { validateCoverage(it, source = false) }
    }

    private const val MAX_BLOCK_ROWS = 32
    private const val BLOCK_PIXELS = 64 * 1024
    private const val IDAT_BYTES = 64 * 1024
    private const val MAX_SCRATCH_BYTES = 8L * 1024 * 1024
    private val SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val IHDR = byteArrayOf(73, 72, 68, 82)
    private val IDAT = byteArrayOf(73, 68, 65, 84)
    private val IEND = byteArrayOf(73, 69, 78, 68)
    private val EMPTY = byteArrayOf()
}
