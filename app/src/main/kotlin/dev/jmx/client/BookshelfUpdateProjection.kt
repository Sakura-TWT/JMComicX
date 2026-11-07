package dev.jmx.client

/** Keep update ordering separate from local metadata timestamps and direction. */
internal fun projectBookshelf(
    entries: List<BookshelfEntry>, groupId: String, order: BookshelfSortOrder,
    direction: BookshelfSortDirection, records: Map<String, AlbumUpdateRecord>,
): List<BookshelfEntry> {
    val ordered = sortBookshelf(entries.filter { groupId == ALL_BOOKSHELF_GROUP_ID || groupId in it.groupIds }, order, direction)
    if (order != BookshelfSortOrder.UPDATED) return ordered
    val (pending, other) = ordered.partition { records[it.albumId]?.hasUpdate == true }
    return pending + other
}

/** Confirm only the observations visible at click time, never a later discovery. */
internal fun acknowledgeAlbumUpdates(
    current: Map<String, AlbumUpdateRecord>, snapshot: Map<String, AlbumUpdateRecord>,
): Map<String, AlbumUpdateRecord> = current.mapValues { (id, record) ->
    val clicked = snapshot[id]
    if (clicked == null || clicked.generation != record.generation) record
    else record.copy(seenChapterCount = maxOf(record.seenChapterCount, minOf(clicked.latestChapterCount, record.latestChapterCount)))
}

internal fun mergeAlbumObservation(existing: AlbumUpdateRecord?, id: String, count: Int, at: Long): AlbumUpdateRecord {
    val normalized = count.coerceAtLeast(0)
    if (existing == null) return AlbumUpdateRecord(id, normalized, normalized, at)
    if (at < existing.checkedAt) return existing
    return existing.copy(latestChapterCount = normalized, seenChapterCount = minOf(existing.seenChapterCount, normalized), checkedAt = at)
}
