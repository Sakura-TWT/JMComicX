package dev.jmx.client

import dev.jmx.client.core.result.JmxError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextTest {
    @Test
    fun authenticationFailuresRequestLogin() {
        assertTrue(JmxError.Http(401, "expired").requiresLogin())
        assertTrue(JmxError.Api(403, "denied").requiresLogin())
        assertFalse(JmxError.Http(500, "server").requiresLogin())
        assertFalse(JmxError.Network("offline").requiresLogin())
    }
}
