package dev.jmx.client.core.image

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageIoRowCodecTest {
    private val codec = ImageIoRowCodec()

    @Test
    fun decodesAndEncodesPngRows() {
        val source = imageBytes(
            format = "png",
            width = 2,
            height = 2,
            pixels = intArrayOf(
                Color.RED.rgb,
                Color.GREEN.rgb,
                Color.BLUE.rgb,
                Color.WHITE.rgb
            )
        )

        val decoded = codec.decode(source, "image/png")

        assertTrue(decoded is JmxResult.Success)
        val rows = (decoded as JmxResult.Success).value
        assertEquals(2, rows.width)
        assertEquals(2, rows.height)
        assertEquals(8, rows.bytesPerRow)
        val swapped = rows.copy(
            rows = rows.rows.copyOfRange(rows.bytesPerRow, rows.rows.size) +
                rows.rows.copyOfRange(0, rows.bytesPerRow)
        )

        val encoded = codec.encode(swapped, "image/png")

        assertTrue(encoded is JmxResult.Success)
        val restored = (encoded as JmxResult.Success).value
        assertEquals("image/png", restored.contentType)
        val image = ImageIO.read(ByteArrayInputStream(restored.bytes))
        assertEquals(Color.BLUE.rgb, image.getRGB(0, 0))
        assertEquals(Color.WHITE.rgb, image.getRGB(1, 0))
        assertEquals(Color.RED.rgb, image.getRGB(0, 1))
        assertEquals(Color.GREEN.rgb, image.getRGB(1, 1))
    }

    @Test
    fun jpegInputFallsBackToJpegOutputFormat() {
        val source = imageBytes(
            format = "jpg",
            width = 1,
            height = 1,
            pixels = intArrayOf(Color.RED.rgb)
        )

        val decoded = codec.decode(source, "image/jpeg")

        assertTrue(decoded is JmxResult.Success)
        val encoded = codec.encode((decoded as JmxResult.Success).value, "image/jpeg")
        assertTrue(encoded is JmxResult.Success)
        assertEquals("image/jpeg", (encoded as JmxResult.Success).value.contentType)
        assertTrue(encoded.value.bytes.isNotEmpty())
    }

    @Test
    fun invalidBytesReturnDecodeError() {
        val decoded = codec.decode(byteArrayOf(1, 2, 3), "image/png")

        assertTrue(decoded is JmxResult.Failure)
        assertTrue((decoded as JmxResult.Failure).error is JmxError.Decode)
    }

    private fun imageBytes(
        format: String,
        width: Int,
        height: Int,
        pixels: IntArray
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, pixels[index++])
            }
        }
        val output = ByteArrayOutputStream()
        ImageIO.write(image, format, output)
        return output.toByteArray()
    }
}
