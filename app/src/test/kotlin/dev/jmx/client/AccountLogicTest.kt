package dev.jmx.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountLogicTest {
    @Test
    fun avatarUrlSupportsRelativeAndAbsoluteSources() {
        assertEquals(
            "https://img.test/media/users/nopic-Male.gif?v=0",
            resolveUserAvatarUrl("https://img.test/", "nopic-Male.gif?v=0"),
        )
        assertEquals(
            "https://cdn.test/avatar.jpg",
            resolveUserAvatarUrl("https://img.test", "https://cdn.test/avatar.jpg"),
        )
        assertNull(resolveUserAvatarUrl("https://img.test", " "))
    }

    @Test
    fun accountProgressAcceptsPlatformPercentAndCounts() {
        assertEquals(0.7595f, accountProgress(3190, 4200, 75.95), 0.0001f)
        assertEquals(0.008f, accountProgress(8, 1000), 0.0001f)
        assertEquals(1f, accountProgress(10, 5), 0f)
    }
}
