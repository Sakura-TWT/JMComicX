package dev.jmx.client.core.image

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageIoRowCodec : ImageRowCodec {
    override fun decode(bytes: ByteArray, contentType: String?): JmxResult<DecodedImageRows> {
        return runCatching {
            val image = ImageIO.read(ByteArrayInputStream(bytes))
                ?: return JmxResult.Failure(
                    JmxError.Decode("图片解码失败：ImageIO 不支持 ${contentType ?: "unknown"}")
                )
            val width = image.width
            val height = image.height
            val rows = ByteArray(width * height * BYTES_PER_PIXEL)
            var offset = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val argb = image.getRGB(x, y)
                    rows[offset++] = (argb ushr 24).toByte()
                    rows[offset++] = (argb ushr 16).toByte()
                    rows[offset++] = (argb ushr 8).toByte()
                    rows[offset++] = argb.toByte()
                }
            }
            JmxResult.Success(
                DecodedImageRows(
                    width = width,
                    height = height,
                    bytesPerRow = width * BYTES_PER_PIXEL,
                    rows = rows
                )
            )
        }.getOrElse {
            JmxResult.Failure(JmxError.Decode("图片解码异常", cause = it))
        }
    }

    override fun encode(image: DecodedImageRows, sourceContentType: String?): JmxResult<RestoredImageBytes> {
        return runCatching {
            if (image.bytesPerRow != image.width * BYTES_PER_PIXEL) {
                return JmxResult.Failure(
                    JmxError.Schema(
                        "ImageIO 行数据格式不匹配",
                        field = "image.bytesPerRow"
                    )
                )
            }
            val format = outputFormat(sourceContentType)
            val imageType = if (format.supportsAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            val buffered = BufferedImage(image.width, image.height, imageType)
            var offset = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val a = image.rows[offset++].toInt() and 0xFF
                    val r = image.rows[offset++].toInt() and 0xFF
                    val g = image.rows[offset++].toInt() and 0xFF
                    val b = image.rows[offset++].toInt() and 0xFF
                    val alpha = if (format.supportsAlpha) a else 0xFF
                    buffered.setRGB(x, y, (alpha shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
            val output = ByteArrayOutputStream()
            if (!ImageIO.write(buffered, format.name, output)) {
                return JmxResult.Failure(
                    JmxError.Decode("图片编码失败：ImageIO 不支持 ${format.name}")
                )
            }
            JmxResult.Success(
                RestoredImageBytes(
                    bytes = output.toByteArray(),
                    contentType = format.contentType
                )
            )
        }.getOrElse {
            JmxResult.Failure(JmxError.Decode("图片编码异常", cause = it))
        }
    }

    private fun outputFormat(contentType: String?): OutputImageFormat {
        return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/jpeg",
            "image/jpg" -> OutputImageFormat("jpg", "image/jpeg", supportsAlpha = false)
            "image/gif" -> OutputImageFormat("gif", "image/gif", supportsAlpha = false)
            else -> OutputImageFormat("png", "image/png", supportsAlpha = true)
        }
    }

    private data class OutputImageFormat(
        val name: String,
        val contentType: String,
        val supportsAlpha: Boolean
    )

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
