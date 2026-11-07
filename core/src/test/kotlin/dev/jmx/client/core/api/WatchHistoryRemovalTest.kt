package dev.jmx.client.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryRemovalTest {
    @Test
    fun singleDeleteAcceptsOnlyNumericAlbumIds() {
        assertEquals("123456", watchHistoryDeleteIdOrNull(" 123456 "))
        assertNull(watchHistoryDeleteIdOrNull(""))
        assertNull(watchHistoryDeleteIdOrNull("all"))
        assertNull(watchHistoryDeleteIdOrNull("12a"))
    }

    @Test
    fun removalTreatsMissingStatusAsSuccessAndRejectsExplicitFailure() {
        assertTrue(ActionResult(status = null, message = null, type = null, raw = emptyMap()).acceptsHistoryRemoval())
        assertTrue(ActionResult(status = "OK", message = null, type = null, raw = emptyMap()).acceptsHistoryRemoval())
        assertTrue(ActionResult(status = "success", message = null, type = null, raw = emptyMap()).acceptsHistoryRemoval())
        assertFalse(ActionResult(status = "fail", message = "no", type = null, raw = emptyMap()).acceptsHistoryRemoval())
    }
}
