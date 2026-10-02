package app.prismia.plus.core.image

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class StreamingPngEncoderTest {
    @Test
    fun randomOpaquePixelsMatchReferenceRestorationForVariedGeometry() {
        val random = Random(197)
        for ((width, height, segments) in listOf(
            Triple(1, 1, 0), Triple(2, 1, 16), Triple(7, 11, 4),
            Triple(29, 65, 10), Triple(37, 131, 16), Triple(13, 3, 20),
            Triple(257, 67, 7), Triple(63, 96, 10), Triple(32_769, 2, 1),
        )) {
            val source = IntArray(width * height) { random.nextInt() or (255 shl 24) }
            val encoded = encode(width, height, source, ImagePipeline().restoreMoves(height, segments))
            assertPixels(width, height, reference(source, height, width, segments), encoded)
        }
    }

    @Test
    fun lineArtAndUnorderedMovesRemainPixelExact() {
        val width = 71
        val height = 139
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x % 9 == 0 || y % 11 == 0) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val encoded = encode(width, height, source, ImagePipeline().restoreMoves(height, 12).reversed())
        assertPixels(width, height, reference(source, height, width, 12), encoded)
    }

    @Test
    fun chunksHaveValidCrcAndBoundedIdatPayloads() {
        val width = 769
        val height = 353
        val random = Random(28)
        val source = IntArray(width * height) { random.nextInt() or (255 shl 24) }
        val bytes = encode(width, height, source, emptyList())
        val chunks = chunks(bytes)
        assertEquals("IHDR", chunks.first().type)
        assertEquals("IEND", chunks.last().type)
        assertEquals(13, chunks.first().data.size)
        val header = DataInputStream(chunks.first().data.inputStream())
        assertEquals(width, header.readInt())
        assertEquals(height, header.readInt())
        assertEquals(8, header.readUnsignedByte())
        assertEquals(2, header.readUnsignedByte())
        assertEquals(0, header.readUnsignedByte())
        assertEquals(0, header.readUnsignedByte())
        assertEquals(0, header.readUnsignedByte())
        val idat = chunks.filter { it.type == "IDAT" }
        assertTrue(idat.size > 1)
        assertTrue(idat.all { it.data.size in 1..65_536 })
        assertTrue(idat.dropLast(1).all { it.data.size == 65_536 })
        assertTrue(chunks.drop(1).dropLast(1).all { it.type == "IDAT" })
        assertEquals(0, chunks.last().data.size)
        assertPixels(width, height, source, bytes)
    }

    @Test
    fun selectsSubForGradientAndUpForRepeatedRows() {
        val width = 256
        val height = 3
        val source = IntArray(width * height) { index ->
            val channel = index % width
            0xff000000.toInt() or (channel shl 16) or (channel shl 8) or channel
        }
        val encoded = encode(width, height, source, emptyList())
        val compressed = ByteArrayOutputStream()
        chunks(encoded).filter { it.type == "IDAT" }.forEach { compressed.write(it.data) }
        val rows = InflaterInputStream(compressed.toByteArray().inputStream()).use { it.readBytes() }
        val stride = width * 3 + 1
        assertEquals(stride * height, rows.size)
        assertEquals(1, rows[0].toInt())
        assertEquals(2, rows[stride].toInt())
        assertEquals(2, rows[stride * 2].toInt())
        assertPixels(width, height, source, encoded)
    }

    @Test
    fun invalidDimensionsAndMovesFailBeforeOutputOrPixelRead() {
        fun rejected(width: Int, height: Int, moves: List<ImageSegmentMove>) {
            val output = ByteArrayOutputStream()
            var reads = 0
            expectThrows<IllegalArgumentException> {
                StreamingPngEncoder.encode(width, height, output, { _, _, _ -> reads++ }, moves)
            }
            assertEquals(0, reads)
            assertEquals(0, output.size())
        }
        rejected(0, 1, emptyList())
        rejected(1, 0, emptyList())
        rejected(-1, 4, emptyList())
        rejected(1, -1, emptyList())
        rejected(Int.MAX_VALUE, Int.MAX_VALUE, emptyList())
        rejected(1_000_000, 1, emptyList()) // Explicit scratch-memory guard, not just Int overflow.
        rejected(1, 4, listOf(ImageSegmentMove(0, 0, 3))) // Missing coverage.
        rejected(1, 4, listOf(ImageSegmentMove(0, 0, 2), ImageSegmentMove(1, 2, 2))) // Source overlap.
        rejected(1, 4, listOf(ImageSegmentMove(0, 0, 2), ImageSegmentMove(2, 1, 2))) // Target overlap.
        rejected(1, 4, listOf(ImageSegmentMove(-1, 0, 4)))
        rejected(1, 4, listOf(ImageSegmentMove(0, -1, 4)))
        rejected(1, 4, listOf(ImageSegmentMove(0, 0, -1)))
        rejected(1, 4, listOf(ImageSegmentMove(0, 0, 0)))
        rejected(1, 4, listOf(ImageSegmentMove(Int.MAX_VALUE, 0, Int.MAX_VALUE)))
        rejected(1, 4, listOf(ImageSegmentMove(0, Int.MAX_VALUE, Int.MAX_VALUE)))
        rejected(1, 4, listOf(ImageSegmentMove(0, 0, 4), ImageSegmentMove(5, 4, 0)))
    }

    @Test
    fun nonopaquePixelsAreRejectedRatherThanSilentlyFlattened() {
        expectThrows<IllegalArgumentException> {
            encode(2, 1, intArrayOf(0xffffffff.toInt(), 0x7f123456), emptyList())
        }
    }

    @Test
    fun providerReadsBulkBlocksFromOneReusedBoundedArray() {
        val width = 1024
        val height = 1_027
        val moves = ImagePipeline().restoreMoves(height, 10)
        val expectedRows = moves.flatMap { move -> (move.sourceY until move.sourceY + move.height).toList() }
        val readRows = mutableListOf<Int>()
        var firstArray: IntArray? = null
        var bulkReads = 0
        var emittedBeforeLastRead = false
        val output = CountingOutput()
        StreamingPngEncoder.encode(width, height, output, { sourceY, rowCount, dest ->
            assertTrue(rowCount in 1..32)
            assertEquals(width * 32, dest.size)
            if (firstArray == null) firstArray = dest else assertSame(firstArray, dest)
            if (rowCount == 32) bulkReads++
            if (readRows.isNotEmpty() && output.bytes > 100_000) emittedBeforeLastRead = true
            for (row in 0 until rowCount) {
                readRows += sourceY + row
                for (x in 0 until width) dest[row * width + x] = pixel(x, sourceY + row)
            }
        }, moves)
        assertEquals(expectedRows, readRows)
        assertTrue(bulkReads > 0)
        assertTrue(emittedBeforeLastRead)
        assertFalse(output.closed)
    }

    @Test
    fun wideImagesReduceProviderBlockRowsToKeepScratchBounded() {
        val width = 8192
        val height = 65
        var calls = 0
        var firstArray: IntArray? = null
        StreamingPngEncoder.encode(width, height, CountingOutput(), { _, rowCount, dest ->
            calls++
            assertTrue(rowCount in 1..8)
            assertEquals(65_536, dest.size)
            if (firstArray == null) firstArray = dest else assertSame(firstArray, dest)
            dest.fill(0xff123456.toInt())
        }, emptyList())
        assertEquals(9, calls)
    }

    @Test
    fun cancellationBeforeStartDoesNotTouchOutputOrProvider() {
        val failure = CancellationException("synthetic cancellation")
        val output = CountingOutput()
        val actual = expectThrows<CancellationException> {
            StreamingPngEncoder.encode(10, 10, output, { _, _, _ -> fail("Unexpected pixel read") }, emptyList()) {
                throw failure
            }
        }
        assertSame(failure, actual)
        assertEquals(0L, output.bytes)
        assertFalse(output.closed)
    }

    @Test
    fun cancellationAfterProviderPropagatesAndLeavesOutputOpen() {
        val failure = CancellationException("synthetic cancellation")
        val output = CountingOutput()
        var cancelled = false
        var reads = 0
        val actual = expectThrows<CancellationException> {
            StreamingPngEncoder.encode(32, 100, output, { _, _, dest ->
                reads++
                dest.fill(0xffabcdef.toInt())
                cancelled = true
            }, emptyList(), checkActive = { if (cancelled) throw failure })
        }
        assertSame(failure, actual)
        assertEquals(1, reads)
        assertFalse(output.closed)
        assertTrue(output.bytes > 0)
        // A cancelled invocation must not poison subsequent encoder/deflater state.
        assertPixels(1, 1, intArrayOf(-1), encode(1, 1, intArrayOf(-1), emptyList()))
    }

    @Test
    fun cancellationDuringCompressedOutputStopsBeforeNextRead() {
        val failure = CancellationException("synthetic cancellation")
        var cancelled = false
        var reads = 0
        val output = object : CountingOutput() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (length == 65_536) cancelled = true
            }
        }
        val actual = expectThrows<CancellationException> {
            StreamingPngEncoder.encode(1024, 200, output, { sourceY, rowCount, dest ->
                reads++
                assertFalse(cancelled)
                for (row in 0 until rowCount) for (x in 0 until 1024) {
                    dest[row * 1024 + x] = pixel(x, sourceY + row)
                }
            }, emptyList(), checkActive = { if (cancelled) throw failure })
        }
        assertSame(failure, actual)
        assertTrue(reads in 1..6)
        assertFalse(output.closed)
    }

    @Test
    fun failingProviderPropagatesSameExceptionWithoutClosingOutput() {
        val failure = IOException("synthetic pixel read failure")
        val output = CountingOutput()
        val actual = expectThrows<IOException> {
            StreamingPngEncoder.encode(1, 1, output, { _, _, _ -> throw failure }, emptyList())
        }
        assertSame(failure, actual)
        assertFalse(output.closed)
    }

    @Test
    fun failingOutputPropagatesSameExceptionWithoutClosingIt() {
        for (failAfter in listOf(0L, 10L, 70_000L)) {
            val failure = IOException("synthetic output failure")
            val output = object : CountingOutput() {
                override fun write(value: Int) {
                    if (bytes >= failAfter) throw failure
                    super.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (this.bytes + length > failAfter) throw failure
                    super.write(bytes, offset, length)
                }
            }
            val actual = expectThrows<IOException> {
                StreamingPngEncoder.encode(1024, 200, output, { sourceY, rowCount, dest ->
                    for (row in 0 until rowCount) for (x in 0 until 1024) {
                        dest[row * 1024 + x] = pixel(x, sourceY + row)
                    }
                }, emptyList())
            }
            assertSame(failure, actual)
            assertFalse(output.closed)
        }
    }

    @Test
    fun successDoesNotFlushOrCloseCallerStream() {
        val output = CountingOutput()
        StreamingPngEncoder.encode(1, 1, output, { _, _, dest -> dest[0] = -1 }, emptyList())
        assertTrue(output.bytes > 0)
        assertEquals(0, output.flushes)
        assertFalse(output.closed)
        output.write(123)
    }

    private fun encode(width: Int, height: Int, source: IntArray, moves: List<ImageSegmentMove>): ByteArray {
        val output = ByteArrayOutputStream()
        StreamingPngEncoder.encode(width, height, output, { y, rows, dest ->
            source.copyInto(dest, 0, y * width, (y + rows) * width)
        }, moves)
        return output.toByteArray()
    }

    private fun reference(source: IntArray, height: Int, width: Int, segments: Int): IntArray {
        val rows = ByteArray(source.size * 3)
        source.forEachIndexed { index, pixel ->
            rows[index * 3] = (pixel ushr 16).toByte()
            rows[index * 3 + 1] = (pixel ushr 8).toByte()
            rows[index * 3 + 2] = pixel.toByte()
        }
        val restored = ImagePipeline().restoreRows(rows, height, width * 3, segments)
        return IntArray(source.size) { index ->
            0xff000000.toInt() or ((restored[index * 3].toInt() and 255) shl 16) or
                ((restored[index * 3 + 1].toInt() and 255) shl 8) or (restored[index * 3 + 2].toInt() and 255)
        }
    }

    private fun assertPixels(width: Int, height: Int, expected: IntArray, png: ByteArray) {
        val image = requireNotNull(ImageIO.read(png.inputStream()))
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        assertFalse(image.colorModel.hasAlpha())
        assertArrayEquals(expected, image.getRGB(0, 0, width, height, null, 0, width))
    }

    private data class Chunk(val type: String, val data: ByteArray)

    private fun chunks(png: ByteArray): List<Chunk> {
        val input = DataInputStream(png.inputStream())
        val signature = ByteArray(8).also(input::readFully)
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), signature)
        val chunks = mutableListOf<Chunk>()
        while (input.available() > 0) {
            val size = input.readInt()
            assertTrue(size in 0..65_536)
            val type = ByteArray(4).also(input::readFully)
            val data = ByteArray(size).also(input::readFully)
            val expectedCrc = input.readInt().toLong() and 0xffffffffL
            val crc = CRC32().apply { update(type); update(data) }
            assertEquals(expectedCrc, crc.value)
            chunks += Chunk(type.toString(Charsets.US_ASCII), data)
        }
        return chunks
    }

    private fun pixel(x: Int, y: Int): Int {
        var value = x * 0x1f123bb5 + y * 0x05491333
        value = (value xor (value ushr 16)) * 0x45d9f3b
        return value or 0xff000000.toInt()
    }

    private open class CountingOutput : OutputStream() {
        var bytes = 0L
        var closed = false
        var flushes = 0
        override fun write(value: Int) { bytes++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { this.bytes += length }
        override fun close() { closed = true }
        override fun flush() { flushes++ }
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
