package app.prismia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoSessionCodecTest {
    @Test
    fun roundTripsNullableRefreshTokenAndExpiry() {
        val original = VideoSession("access-token", "refresh-token", 1_800_000_000L)

        assertEquals(original, VideoSessionCodec.decode(VideoSessionCodec.encode(original)))
    }

    @Test
    fun roundTripsSessionWithoutExpiryOrRefreshToken() {
        val original = VideoSession("access-token")

        assertEquals(original, VideoSessionCodec.decode(VideoSessionCodec.encode(original)))
    }

    @Test
    fun rejectsTrailingBytesAndCorruptPayloads() {
        val encoded = VideoSessionCodec.encode(VideoSession("access-token"))

        assertThrows(VideoSessionFormatException::class.java) {
            VideoSessionCodec.decode(encoded + 0x01)
        }
        assertThrows(VideoSessionFormatException::class.java) {
            VideoSessionCodec.decode(encoded.copyOf().also { it[0] = 0 })
        }
    }

    @Test
    fun rejectsOversizedTokenBeforeEncoding() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoSessionCodec.encode(VideoSession("x".repeat(16 * 1024 + 1)))
        }
    }
}
