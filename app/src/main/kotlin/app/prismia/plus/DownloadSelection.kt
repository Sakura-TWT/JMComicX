package app.prismia.plus

import kotlin.math.abs

/** Selection is keyed by the original source ID; never parse chapter/album IDs as numbers. */
internal fun toggleDownloadSelection(
    selectedIds: Set<String>,
    id: String,
    eligibleIds: Set<String>,
): Set<String> {
    val validSelection = selectedIds.intersect(eligibleIds)
    if (id !in eligibleIds) return validSelection
    return if (id in validSelection) validSelection - id else validSelection + id
}

internal fun toggleAllDownloadSelection(
    selectedIds: Set<String>,
    eligibleIds: Set<String>,
): Set<String> = if (eligibleIds.isNotEmpty() && selectedIds.containsAll(eligibleIds)) {
    emptySet()
} else {
    eligibleIds.toSet()
}

/**
 * Paint the entire segment between consecutive pointer hits, including skipped/offscreen rows.
 * The mode is fixed by the first long-pressed row. Revisiting a row is idempotent, so reversing
 * direction never toggles an already visited chapter back or disturbs an unrelated selection.
 */
internal fun paintDownloadSelectionRange(
    selectedIds: Set<String>,
    orderedIds: List<String>,
    fromIndex: Int,
    toIndex: Int,
    selecting: Boolean,
    eligibleIds: Set<String>,
): Set<String> {
    if (fromIndex !in orderedIds.indices || toIndex !in orderedIds.indices) return selectedIds
    val affected = (minOf(fromIndex, toIndex)..maxOf(fromIndex, toIndex))
        .mapNotNull { index -> orderedIds[index].takeIf { it in eligibleIds } }
        .toSet()
    return if (selecting) selectedIds + affected else selectedIds - affected
}

/** Lazy-list coordinates, relative to the viewport rather than the whole dialog/window. */
internal data class DownloadSelectionRow(val index: Int, val offset: Int, val size: Int)

/** Clamp to the closest visible row at an edge or inside a gap between rows. */
internal fun downloadSelectionIndexAt(
    pointerY: Float,
    visibleRows: List<DownloadSelectionRow>,
): Int? {
    if (!pointerY.isFinite()) return null
    val rows = visibleRows.filter { it.size > 0 }
    return rows.firstOrNull { pointerY >= it.offset && pointerY < it.offset.toFloat() + it.size }
        ?.index ?: rows.minByOrNull {
        minOf(abs(pointerY - it.offset), abs(pointerY - (it.offset.toFloat() + it.size)))
    }?.index
}

/** Signed pixels/second; outside the viewport keeps the same bounded edge speed. */
internal fun downloadSelectionAutoScrollSpeed(
    pointerY: Float,
    viewportHeight: Float,
    edgeSize: Float,
    maxSpeed: Float,
): Float {
    if (!pointerY.isFinite() || !viewportHeight.isFinite() || !edgeSize.isFinite() ||
        !maxSpeed.isFinite() || viewportHeight <= 0f || edgeSize <= 0f || maxSpeed <= 0f
    ) return 0f
    val edge = minOf(edgeSize, viewportHeight / 2f)
    val y = pointerY.coerceIn(0f, viewportHeight)
    return when {
        y < edge -> -maxSpeed * ((edge - y) / edge).let { it * it }
        y > viewportHeight - edge -> maxSpeed * ((y - viewportHeight + edge) / edge).let { it * it }
        else -> 0f
    }
}
