package app.prismia.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionTest {
    @Test
    fun treatsSoonToExpireAccessTokenAsUnavailable() {
        val session = VideoSession("access", accessTokenExpiresAtEpochSeconds = 1_040)
        assertTrue(session.isUsable(1_000))
        assertFalse(session.isUsable(1_011))
    }
}
