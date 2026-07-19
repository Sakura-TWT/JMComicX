package dev.jmx.client

import androidx.compose.ui.geometry.Rect
import dev.jmx.client.core.api.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class AlbumDetailTransitionTest {
    @Test
    fun actionResultRejectsNonSuccessStatus() {
        assertEquals(
            null,
            ActionResult("ok", "done", null, emptyMap()).rejectionMessageOrNull(),
        )
        assertEquals(
            "账号权限不足",
            ActionResult("error", "账号权限不足", null, emptyMap()).rejectionMessageOrNull(),
        )
        assertEquals(
            "勿短时间重复留言,请重新确认留言内容",
            ActionResult(null, "勿短时间重复留言,请重新确认留言内容", null, emptyMap())
                .rejectionMessageOrNull(),
        )
    }

    @Test
    fun curvedCoverBoundsKeepsEndpointsAndBendsAwayFromStraightLine() {
        val start = Rect(left = 720f, top = 1500f, right = 1020f, bottom = 1900f)
        val end = Rect(left = 44f, top = 280f, right = 396f, bottom = 749f)

        val atStart = curvedCoverBounds(start, end, progress = 0f, maxBend = 280f)
        val atMiddle = curvedCoverBounds(start, end, progress = 0.5f, maxBend = 280f)
        val atEnd = curvedCoverBounds(start, end, progress = 1f, maxBend = 280f)

        assertRectEquals(start, atStart)
        assertRectEquals(end, atEnd)

        val linearMidX = (start.left + end.left) / 2f
        val linearMidY = (start.top + end.top) / 2f
        val bendDistance = hypot(atMiddle.left - linearMidX, atMiddle.top - linearMidY)
        assertTrue("Bezier midpoint must visibly deviate from a straight path", bendDistance > 40f)
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }
}
