package dev.jmx.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateLogicTest {
    @Test
    fun comparesReleaseVersionsWithoutPrefixOrBuildSuffix() {
        assertTrue(isVersionNewer("v1.0.11", "0.13.0-dev"))
        assertTrue(isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(isVersionNewer("v2.0.0", "2.0.0"))
        assertFalse(isVersionNewer("v1.0.11", "2.0.0"))
        assertFalse(isVersionNewer("v1.0.11", "1.0.11"))
        assertFalse(isVersionNewer("1.0.10", "1.0.11"))
        assertFalse(isVersionNewer("1.0.11-beta", "1.0.11"))
    }
}
