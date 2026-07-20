package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class BookshelfSortOrder(val label: String) {
    NAME("名称"),
    UPDATED("更新时间"),
    RECENTLY_READ("最近阅读"),
}

internal data class BookshelfGroup(
    val id: String,
    val name: String,
    val matchFavoritesByTags: Boolean,
    val tagRules: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class BookshelfEntry(
    val albumId: String,
    val name: String,
    val author: String,
    val coverUrl: String,
    val imageHost: String,
    val addedAt: Long,
    val updatedAt: Long = addedAt,
    val groupIds: Set<String> = emptySet(),
    val lastReadAt: Long? = null,
    val lastChapterId: String? = null,
    val lastChapterName: String? = null,
    val lastPageIndex: Int = 0,
    val lastPageCount: Int? = null,
) {
    fun toHomeAlbum(): HomeAlbum = HomeAlbum(
        id = albumId,
        name = name,
        author = author,
        coverUrl = coverUrl,
        imageHost = imageHost,
    )

    fun progressSummary(): String {
        val chapter = lastChapterName?.takeIf(String::isNotBlank)
            ?: lastChapterId?.takeIf(String::isNotBlank)?.let { "JM$it" }
            ?: return "尚未阅读"
        val page = (lastPageIndex + 1).coerceAtLeast(1)
        val pageText = lastPageCount?.takeIf { it > 0 }?.let { "$page/$it 页" } ?: "第 $page 页"
        return "$chapter · $pageText"
    }
}

internal class BookshelfRepository(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        BOOKSHELF_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    fun entries(
        groupId: String = ALL_BOOKSHELF_GROUP_ID,
        order: BookshelfSortOrder = sortOrder(),
    ): List<BookshelfEntry> = synchronized(lock) {
        val entries = readEntries().let { current ->
            if (groupId == ALL_BOOKSHELF_GROUP_ID) current else current.filter { groupId in it.groupIds }
        }
        sortBookshelf(entries, order)
    }

    fun entry(albumId: String): BookshelfEntry? = synchronized(lock) {
        readEntries().firstOrNull { it.albumId == albumId }
    }

    fun groups(): List<BookshelfGroup> = synchronized(lock) {
        readGroups().sortedBy(BookshelfGroup::createdAt)
    }

    fun contains(albumId: String): Boolean = entry(albumId) != null

    fun add(album: HomeAlbum, groupIds: Set<String> = emptySet()): Boolean = synchronized(lock) {
        val validGroupIds = groupIds.intersect(readGroups().mapTo(mutableSetOf(), BookshelfGroup::id))
        val current = readEntries()
        val (updated, added) = addToBookshelf(current, album, now(), validGroupIds)
        writeEntries(updated)
        added
    }

    fun addAllToGroup(albums: List<HomeAlbum>, groupId: String): Int = synchronized(lock) {
        if (readGroups().none { it.id == groupId }) return@synchronized 0
        var current = readEntries()
        var changed = 0
        albums.distinctBy(HomeAlbum::id).forEach { album ->
            val before = current.firstOrNull { it.albumId == album.id }
            val result = addToBookshelf(current, album, now(), setOf(groupId))
            current = result.first
            val after = current.firstOrNull { it.albumId == album.id }
            if (before != after) changed++
        }
        if (changed > 0) writeEntries(current)
        changed
    }

    fun addEntriesToGroups(albumIds: Set<String>, groupIds: Set<String>): Int = synchronized(lock) {
        val validGroupIds = groupIds.intersect(readGroups().mapTo(mutableSetOf(), BookshelfGroup::id))
        if (albumIds.isEmpty() || validGroupIds.isEmpty()) return@synchronized 0
        val (updated, changed) = assignBookshelfGroups(
            entries = readEntries(),
            albumIds = albumIds,
            groupIds = validGroupIds,
            updatedAt = now(),
        )
        if (changed > 0) writeEntries(updated)
        changed
    }

    fun remove(albumId: String): Boolean = synchronized(lock) {
        val current = readEntries()
        val updated = current.filterNot { it.albumId == albumId }
        if (updated.size == current.size) return@synchronized false
        writeEntries(updated)
        true
    }

    fun createGroup(
        name: String,
        matchFavoritesByTags: Boolean,
        tagRules: List<String>,
    ): BookshelfGroup? = synchronized(lock) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty) ?: return@synchronized null
        val current = readGroups()
        if (
            current.size >= MAX_BOOKSHELF_GROUPS ||
            current.any { it.name.equals(normalizedName, ignoreCase = true) }
        ) {
            return@synchronized null
        }
        val timestamp = now()
        val group = BookshelfGroup(
            id = "group-${UUID.randomUUID()}",
            name = normalizedName,
            matchFavoritesByTags = matchFavoritesByTags,
            tagRules = tagRules.mapNotNull(::normalizeSearchTag).distinct(),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        writeGroups(current + group)
        group
    }

    fun updateGroup(
        groupId: String,
        name: String,
        matchFavoritesByTags: Boolean,
        tagRules: List<String>,
    ): BookshelfGroup? = synchronized(lock) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty) ?: return@synchronized null
        val current = readGroups()
        if (current.any { it.id != groupId && it.name.equals(normalizedName, ignoreCase = true) }) {
            return@synchronized null
        }
        var updatedGroup: BookshelfGroup? = null
        val updated = current.map { group ->
            if (group.id != groupId) {
                group
            } else {
                group.copy(
                    name = normalizedName,
                    matchFavoritesByTags = matchFavoritesByTags,
                    tagRules = tagRules.mapNotNull(::normalizeSearchTag).distinct(),
                    updatedAt = now(),
                ).also { updatedGroup = it }
            }
        }
        if (updatedGroup != null) writeGroups(updated)
        updatedGroup
    }

    fun deleteGroup(groupId: String): Boolean = synchronized(lock) {
        val currentGroups = readGroups()
        val updatedGroups = currentGroups.filterNot { it.id == groupId }
        if (updatedGroups.size == currentGroups.size) return@synchronized false
        writeGroups(updatedGroups)
        writeEntries(
            readEntries().map { entry ->
                if (groupId in entry.groupIds) entry.copy(groupIds = entry.groupIds - groupId) else entry
            },
        )
        true
    }

    fun recordProgress(
        albumId: String,
        chapterId: String,
        chapterName: String,
        pageIndex: Int,
        pageCount: Int,
    ): Boolean = synchronized(lock) {
        val current = readEntries()
        val updated = updateBookshelfProgress(
            entries = current,
            albumId = albumId,
            chapterId = chapterId,
            chapterName = chapterName,
            pageIndex = pageIndex,
            pageCount = pageCount,
            readAt = now(),
        )
        if (updated == current) return@synchronized false
        writeEntries(updated)
        true
    }

    fun sortOrder(): BookshelfSortOrder {
        val stored = preferences.getString(BOOKSHELF_SORT_KEY, null)
        if (stored == LEGACY_RECENTLY_ADDED_SORT) return BookshelfSortOrder.UPDATED
        return BookshelfSortOrder.entries.firstOrNull { it.name == stored }
            ?: BookshelfSortOrder.RECENTLY_READ
    }

    fun setSortOrder(order: BookshelfSortOrder) {
        preferences.edit { putString(BOOKSHELF_SORT_KEY, order.name) }
    }

    private fun readEntries(): List<BookshelfEntry> {
        val encoded = preferences.getString(BOOKSHELF_ENTRIES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.toBookshelfEntryOrNull()?.let(::add)
                }
            }.distinctBy(BookshelfEntry::albumId)
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<BookshelfEntry>) {
        val array = JSONArray()
        entries.take(MAX_BOOKSHELF_ENTRIES).forEach { array.put(it.toJson()) }
        preferences.edit { putString(BOOKSHELF_ENTRIES_KEY, array.toString()) }
    }

    private fun readGroups(): List<BookshelfGroup> {
        val encoded = preferences.getString(BOOKSHELF_GROUPS_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.toBookshelfGroupOrNull()?.let(::add)
                }
            }.distinctBy(BookshelfGroup::id)
        }.getOrDefault(emptyList())
    }

    private fun writeGroups(groups: List<BookshelfGroup>) {
        val array = JSONArray()
        groups.take(MAX_BOOKSHELF_GROUPS).forEach { array.put(it.toJson()) }
        preferences.edit { putString(BOOKSHELF_GROUPS_KEY, array.toString()) }
    }
}

internal fun sortBookshelf(
    entries: List<BookshelfEntry>,
    order: BookshelfSortOrder,
): List<BookshelfEntry> = when (order) {
    BookshelfSortOrder.NAME -> entries.sortedWith(
        compareBy<BookshelfEntry> { it.name.lowercase(Locale.ROOT) }
            .thenBy(BookshelfEntry::albumId),
    )
    BookshelfSortOrder.UPDATED -> entries.sortedWith(
        compareByDescending<BookshelfEntry>(BookshelfEntry::updatedAt)
            .thenByDescending(BookshelfEntry::addedAt),
    )
    BookshelfSortOrder.RECENTLY_READ -> entries.sortedWith(
        compareByDescending<BookshelfEntry> { it.lastReadAt ?: Long.MIN_VALUE }
            .thenByDescending(BookshelfEntry::updatedAt),
    )
}

internal fun addToBookshelf(
    entries: List<BookshelfEntry>,
    album: HomeAlbum,
    addedAt: Long,
    groupIds: Set<String> = emptySet(),
): Pair<List<BookshelfEntry>, Boolean> {
    val existing = entries.firstOrNull { it.albumId == album.id }
    val entry = existing?.copy(
        name = album.name,
        author = album.author,
        coverUrl = album.coverUrl,
        imageHost = album.imageHost,
        groupIds = existing.groupIds + groupIds,
        updatedAt = if (
            existing.name != album.name ||
            existing.author != album.author ||
            existing.coverUrl != album.coverUrl ||
            existing.imageHost != album.imageHost ||
            !existing.groupIds.containsAll(groupIds)
        ) {
            addedAt
        } else {
            existing.updatedAt
        },
    ) ?: BookshelfEntry(
        albumId = album.id,
        name = album.name,
        author = album.author,
        coverUrl = album.coverUrl,
        imageHost = album.imageHost,
        addedAt = addedAt,
        updatedAt = addedAt,
        groupIds = groupIds,
    )
    return (listOf(entry) + entries.filterNot { it.albumId == album.id }) to (existing == null)
}

internal fun updateBookshelfProgress(
    entries: List<BookshelfEntry>,
    albumId: String,
    chapterId: String,
    chapterName: String,
    pageIndex: Int,
    pageCount: Int,
    readAt: Long,
): List<BookshelfEntry> {
    if (entries.none { it.albumId == albumId }) return entries
    return entries.map { entry ->
        if (entry.albumId != albumId) entry else entry.copy(
            lastReadAt = readAt,
            lastChapterId = chapterId,
            lastChapterName = chapterName,
            lastPageIndex = pageIndex.coerceAtLeast(0),
            lastPageCount = pageCount.takeIf { it > 0 },
        )
    }
}

internal fun assignBookshelfGroups(
    entries: List<BookshelfEntry>,
    albumIds: Set<String>,
    groupIds: Set<String>,
    updatedAt: Long,
): Pair<List<BookshelfEntry>, Int> {
    var changed = 0
    val updated = entries.map { entry ->
        if (entry.albumId !in albumIds || entry.groupIds.containsAll(groupIds)) {
            entry
        } else {
            changed++
            entry.copy(
                groupIds = entry.groupIds + groupIds,
                updatedAt = updatedAt,
            )
        }
    }
    return updated to changed
}

internal fun parseBookshelfTagRules(value: String): List<String> = value
    .split(BOOKSHELF_TAG_RULE_DELIMITERS)
    .mapNotNull(::normalizeSearchTag)
    .distinct()

internal fun matchesBookshelfTagRules(albumTags: List<String>, rules: List<String>): Boolean {
    if (rules.isEmpty()) return false
    val available = albumTags.mapNotNull(::normalizeSearchTag).distinct()
    return rules.mapNotNull(::normalizeSearchTag).all { target ->
        available.any { tag -> tag == target || tag.contains(target) || target.contains(tag) }
    }
}

internal fun HomeAlbum.matchesBookshelfPickerQuery(query: String): Boolean {
    val rawQuery = query.trim()
    if (rawQuery.isEmpty()) return true
    val vehicleNumber = rawQuery.lowercase(Locale.ROOT).removePrefix("jm").trim()
    if (vehicleNumber.isNotEmpty() && id.contains(vehicleNumber, ignoreCase = true)) return true
    val normalizedQuery = normalizeSearchTag(rawQuery) ?: return false
    return listOf(name, author).any { value ->
        normalizeSearchTag(value)?.contains(normalizedQuery) == true
    }
}

private fun BookshelfEntry.toJson(): JSONObject = JSONObject().apply {
    put("album_id", albumId)
    put("name", name)
    put("author", author)
    put("cover_url", coverUrl)
    put("image_host", imageHost)
    put("added_at", addedAt)
    put("updated_at", updatedAt)
    put("group_ids", JSONArray(groupIds.toList()))
    lastReadAt?.let { put("last_read_at", it) }
    lastChapterId?.let { put("last_chapter_id", it) }
    lastChapterName?.let { put("last_chapter_name", it) }
    put("last_page_index", lastPageIndex)
    lastPageCount?.let { put("last_page_count", it) }
}

private fun BookshelfGroup.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("match_favorites_by_tags", matchFavoritesByTags)
    put("tag_rules", JSONArray(tagRules))
    put("created_at", createdAt)
    put("updated_at", updatedAt)
}

private fun JSONObject.toBookshelfEntryOrNull(): BookshelfEntry? {
    val albumId = optString("album_id").trim().takeIf(String::isNotEmpty) ?: return null
    val addedAt = optLong("added_at", 0L)
    return BookshelfEntry(
        albumId = albumId,
        name = optString("name").trim().ifBlank { "JM$albumId" },
        author = optString("author").trim().ifBlank { "未知作者" },
        coverUrl = optString("cover_url").trim(),
        imageHost = optString("image_host").trim(),
        addedAt = addedAt,
        updatedAt = optLong("updated_at", addedAt),
        groupIds = optStringList("group_ids").toSet(),
        lastReadAt = optNullableLong("last_read_at"),
        lastChapterId = optString("last_chapter_id").trim().takeIf(String::isNotEmpty),
        lastChapterName = optString("last_chapter_name").trim().takeIf(String::isNotEmpty),
        lastPageIndex = optInt("last_page_index", 0).coerceAtLeast(0),
        lastPageCount = optNullableInt("last_page_count")?.takeIf { it > 0 },
    )
}

private fun JSONObject.toBookshelfGroupOrNull(): BookshelfGroup? {
    val id = optString("id").trim().takeIf(String::isNotEmpty) ?: return null
    val name = optString("name").trim().takeIf(String::isNotEmpty) ?: return null
    val createdAt = optLong("created_at", 0L)
    return BookshelfGroup(
        id = id,
        name = name,
        matchFavoritesByTags = optBoolean("match_favorites_by_tags", false),
        tagRules = optStringList("tag_rules").mapNotNull(::normalizeSearchTag).distinct(),
        createdAt = createdAt,
        updatedAt = optLong("updated_at", createdAt),
    )
}

private fun JSONObject.optStringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        repeat(array.length()) { index ->
            array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }
}

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optNullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

internal const val ALL_BOOKSHELF_GROUP_ID = "all"
private const val BOOKSHELF_PREFERENCES = "jmx_bookshelf"
private const val BOOKSHELF_ENTRIES_KEY = "entries"
private const val BOOKSHELF_GROUPS_KEY = "groups"
private const val BOOKSHELF_SORT_KEY = "sort_order"
private const val LEGACY_RECENTLY_ADDED_SORT = "RECENTLY_ADDED"
private const val MAX_BOOKSHELF_ENTRIES = 500
private const val MAX_BOOKSHELF_GROUPS = 40
private val BOOKSHELF_TAG_RULE_DELIMITERS = Regex("[\\s,，、;；]+")
