package dev.jmx.client.core.protocol

import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmIdTest {
    @Test
    fun parsesDigitsJmPrefixAndUrl() {
        assertEquals("43210", (JmId.parse("43210") as JmxResult.Success).value)
        assertEquals("12341", (JmId.parse("JM12341") as JmxResult.Success).value)
        assertEquals("12341", (JmId.parse("jm12341") as JmxResult.Success).value)
        assertEquals("412038", (JmId.parse("https://xxx/photo/412038") as JmxResult.Success).value)
        assertEquals("412038", (JmId.parse("https://xxx/album/?id=412038") as JmxResult.Success).value)
    }

    @Test
    fun rejectsBlankAndGarbage() {
        assertTrue(JmId.parse("") is JmxResult.Failure)
        assertTrue(JmId.parse("  ") is JmxResult.Failure)
        assertTrue(JmId.parse("not-a-jm-id") is JmxResult.Failure)
    }
}
