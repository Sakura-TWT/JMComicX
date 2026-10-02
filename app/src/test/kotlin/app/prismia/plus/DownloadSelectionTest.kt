package app.prismia.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSelectionTest {
    private val ids = listOf("jm:chapter-1", "jm:chapter-2", "other:03", "004", "special")
    private val all = ids.toSet()

    @Test
    fun tapAddsAndRemovesOriginalStringIdentity() {
        val selected = toggleDownloadSelection(emptySet(), "004", all)
        assertEquals(setOf("004"), selected)
        assertEquals(emptySet<String>(), toggleDownloadSelection(selected, "004", all))
        assertEquals(emptySet<String>(), toggleDownloadSelection(selected, "4", emptySet()))
    }

    @Test
    fun tapDropsStaleIdsAndNeverSelectsDisabledIds() {
        assertEquals(setOf(ids[0]), toggleDownloadSelection(setOf("removed", ids[0]), "disabled", all))
    }

    @Test
    fun selectAllUsesOnlyEligibleIds() {
        assertEquals(all, toggleAllDownloadSelection(setOf("removed", ids[0]), all))
        assertEquals(emptySet<String>(), toggleAllDownloadSelection(all, all))
        assertEquals(emptySet<String>(), toggleAllDownloadSelection(all, emptySet()))
    }

    @Test
    fun oneRowLongPressSelectsAnchorImmediately() {
        assertEquals(setOf(ids[2]), paint(emptySet(), 2, 2))
    }

    @Test
    fun forwardDragFillsRowsSkippedByPointerEvents() {
        assertEquals(ids.subList(0, 4).toSet(), paint(emptySet(), 0, 3))
    }

    @Test
    fun backwardDragIsInclusive() {
        assertEquals(ids.subList(1, 5).toSet(), paint(emptySet(), 4, 1))
    }

    @Test
    fun deselectionModeRemovesWholeRangeAndPreservesUnrelatedRows() {
        assertEquals(setOf(ids[0], ids[4]), paint(all, 1, 3, selecting = false))
    }

    @Test
    fun revisitingAndReversingDirectionDoesNotTogglePaintedRows() {
        val first = paint(emptySet(), 1, 4)
        assertEquals(first, paint(first, 4, 2))
        assertEquals(first, paint(first, 2, 4))
    }

    @Test
    fun deselectionIsAlsoIdempotentWhenCrossingSameRows() {
        val first = paint(all, 1, 3, selecting = false)
        assertEquals(first, paint(first, 3, 1, selecting = false))
    }

    @Test
    fun disabledChaptersStayUntouchedWithinPaintedRange() {
        val eligible = all - ids[2]
        assertEquals(eligible, paintDownloadSelectionRange(emptySet(), ids, 0, 4, true, eligible))
        assertEquals(setOf(ids[2]), paintDownloadSelectionRange(all, ids, 0, 4, false, eligible))
    }

    @Test
    fun invalidIndicesAndEmptyListNeverInventASelection() {
        val original = setOf(ids[1])
        assertEquals(original, paint(original, -1, 2))
        assertEquals(original, paint(original, 0, ids.size))
        assertEquals(original, paintDownloadSelectionRange(original, emptyList(), 0, 0, true, all))
    }

    @Test
    fun consecutiveOffscreenHitsSelectEveryChapterWithoutGaps() {
        val many = (0..500).map { "chapter:$it" }
        var selected = paintDownloadSelectionRange(emptySet(), many, 10, 10, true, many.toSet())
        for ((from, to) in listOf(10 to 15, 15 to 48, 48 to 51, 51 to 150)) {
            selected = paintDownloadSelectionRange(selected, many, from, to, true, many.toSet())
        }
        assertEquals(many.subList(10, 151).toSet(), selected)
    }

    @Test
    fun paintDoesNotMutateItsInputs() {
        val selected = linkedSetOf(ids[0])
        val eligible = all.toMutableSet()
        paintDownloadSelectionRange(selected, ids, 1, 4, true, eligible)
        assertEquals(setOf(ids[0]), selected)
        assertEquals(all, eligible)
    }

    @Test
    fun hitTestingUsesActualVariableHeightRows() {
        val rows = listOf(DownloadSelectionRow(6, -20, 60), DownloadSelectionRow(7, 44, 100))
        assertEquals(6, downloadSelectionIndexAt(5f, rows))
        assertEquals(7, downloadSelectionIndexAt(100f, rows))
    }

    @Test
    fun hitTestingClampsOutsideViewportAndInsideSpacing() {
        val rows = listOf(DownloadSelectionRow(10, 0, 60), DownloadSelectionRow(11, 64, 60))
        assertEquals(10, downloadSelectionIndexAt(-30f, rows))
        assertEquals(10, downloadSelectionIndexAt(61f, rows))
        assertEquals(11, downloadSelectionIndexAt(63f, rows))
        assertEquals(11, downloadSelectionIndexAt(200f, rows))
    }

    @Test
    fun hitTestingTracksNewlyScrolledInRowsWithStationaryPointer() {
        val before = listOf(DownloadSelectionRow(0, 0, 60), DownloadSelectionRow(1, 60, 60))
        val after = listOf(DownloadSelectionRow(1, -30, 60), DownloadSelectionRow(2, 30, 60), DownloadSelectionRow(3, 90, 60))
        assertEquals(1, downloadSelectionIndexAt(110f, before))
        assertEquals(3, downloadSelectionIndexAt(110f, after))
        val selected = paint(emptySet(), 1, downloadSelectionIndexAt(110f, after)!!)
        assertEquals(ids.subList(1, 4).toSet(), selected)
    }

    @Test
    fun hitTestingRejectsInvalidCoordinatesAndUnmeasuredRows() {
        assertEquals(null, downloadSelectionIndexAt(12f, emptyList()))
        assertEquals(null, downloadSelectionIndexAt(Float.NaN, listOf(DownloadSelectionRow(0, 0, 40))))
        assertEquals(null, downloadSelectionIndexAt(Float.POSITIVE_INFINITY, listOf(DownloadSelectionRow(0, 0, 40))))
        assertEquals(null, downloadSelectionIndexAt(12f, listOf(DownloadSelectionRow(0, 0, 0))))
    }

    @Test
    fun autoScrollIsStationaryInMiddleAndSignedAtEdges() {
        assertEquals(0f, speed(150f), 0f)
        assertEquals(0f, speed(50f), 0f)
        assertEquals(0f, speed(250f), 0f)
        assertTrue(speed(20f) < 0f)
        assertTrue(speed(280f) > 0f)
        assertEquals(-speed(20f), speed(280f), 0.001f)
    }

    @Test
    fun autoScrollAcceleratesTowardEdgeAndIsBoundedOutsideViewport() {
        assertTrue(speed(290f) > speed(260f))
        assertEquals(600f, speed(300f), 0f)
        assertEquals(600f, speed(500f), 0f)
        assertEquals(-600f, speed(-100f), 0f)
    }

    @Test
    fun shortViewportDoesNotHaveOverlappingEdgeZones() {
        assertEquals(0f, downloadSelectionAutoScrollSpeed(20f, 40f, 56f, 600f), 0f)
        assertTrue(downloadSelectionAutoScrollSpeed(5f, 40f, 56f, 600f) < 0f)
        assertTrue(downloadSelectionAutoScrollSpeed(35f, 40f, 56f, 600f) > 0f)
    }

    @Test
    fun invalidScrollGeometryNeverProducesNaNOrMotion() {
        assertEquals(0f, speed(Float.NaN), 0f)
        assertEquals(0f, downloadSelectionAutoScrollSpeed(10f, 0f, 50f, 600f), 0f)
        assertEquals(0f, downloadSelectionAutoScrollSpeed(10f, 300f, -1f, 600f), 0f)
        assertEquals(0f, downloadSelectionAutoScrollSpeed(10f, 300f, 50f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, downloadSelectionAutoScrollSpeed(10f, Float.NaN, 50f, 600f), 0f)
        assertFalse(speed(30f).isNaN())
    }

    private fun paint(selected: Set<String>, from: Int, to: Int, selecting: Boolean = true): Set<String> =
        paintDownloadSelectionRange(selected, ids, from, to, selecting, all)

    private fun speed(y: Float): Float = downloadSelectionAutoScrollSpeed(y, 300f, 50f, 600f)
}
