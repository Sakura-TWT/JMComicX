package dev.jmx.client.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class JmxMagicConstantsTest {
    @Test
    fun categoriesFilterOrderMatchesPythonRule() {
        assertEquals(
            "mv",
            JmxMagicConstants.categoriesFilterOrder(
                orderBy = JmxMagicConstants.ORDER_BY_VIEW,
                time = JmxMagicConstants.TIME_ALL
            )
        )
        assertEquals(
            "mv_w",
            JmxMagicConstants.categoriesFilterOrder(
                orderBy = JmxMagicConstants.ORDER_BY_VIEW,
                time = JmxMagicConstants.TIME_WEEK
            )
        )
        assertEquals(
            "mr_t",
            JmxMagicConstants.categoriesFilterOrder(
                orderBy = JmxMagicConstants.ORDER_BY_LATEST,
                time = JmxMagicConstants.TIME_TODAY
            )
        )
    }
}
