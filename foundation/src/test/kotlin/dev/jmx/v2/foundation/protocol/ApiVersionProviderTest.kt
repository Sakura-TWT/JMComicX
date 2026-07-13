package dev.jmx.v2.foundation.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiVersionProviderTest {
    @Test
    fun updatesOnlyValidVersion() {
        val provider = MutableApiVersionProvider("1.0.0")

        assertTrue(provider.update("2.0.26"))
        assertEquals("2.0.26", provider.current())
        assertFalse(provider.update("bad"))
        assertEquals("2.0.26", provider.current())
    }
}
