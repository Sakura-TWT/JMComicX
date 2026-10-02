package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架排序字段。[defaultDirection] 是该字段在「正序」下的自然顺序：
 * 名称 A→Z，时间类最新/最近在前。用户没有显式选过方向时沿用它，所以本次改动不改变既有默认表现。
 */
internal enum class BookshelfSortOrder(
    val label: String,
    val defaultDirection: BookshelfSortDirection,
) {
    NAME("名称", BookshelfSortDirection.ASCENDING),
    UPDATED("更新时间", BookshelfSortDirection.ASCENDING),
    ADDED("最近添加", BookshelfSortDirection.ASCENDING),
    RECENTLY_READ("最近阅读", BookshelfSortDirection.ASCENDING),
}

/**
 * 排序方向。
 *
 * 「正序」= 该字段的自然顺序：名称 A→Z，更新时间/最近阅读则是**最新在前**
 * （时间类"顺着时间轴"意味着最近的排最前，与列表默认看到的顺序一致）。
 * 「倒序」反过来：名称 Z→A，时间类最久/最早在前。
 */
internal enum class BookshelfSortDirection(val label: String) {
    ASCENDING("正序"),
    DESCENDING("倒序"),
}

internal enum class BookshelfAuthorMatchSource(val label: String) {
    FAVORITES("仅我的收藏"),
    ALL_WORKS("所有作品"),
}

internal data class BookshelfGroup(
    val id: String,
    val name: String,
    val matchFavoritesByTags: Boolean,
    val tagRules: List<String>,
    val matchByAuthors: Boolean = false,
    val authorRules: List<String> = emptyList(),
    val authorMatchSource: BookshelfAuthorMatchSource = BookshelfAuthorMatchSource.FAVORITES,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 一个分组的使用热度。
 *
 * 只存两个数、不存访问历史：[score] 是"折算到 [updatedAt] 那一刻"的衰减计数，
 * 要比较时再统一折算到当前时刻（见 [decayedGroupScore]）。
 */
internal data class BookshelfGroupUsage(
    val id: String,
    val score: Double,
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

/** 导入/导出用的书架快照：只有内容，不含排序偏好这类本机设置。 */
internal data class BookshelfSnapshot(
    val entries: List<BookshelfEntry>,
    val groups: List<BookshelfGroup>,
)

internal data class BookshelfImportOutcome(
    val addedEntries: Int,
    val mergedEntries: Int,
    val addedGroups: Int,
    val reusedGroups: Int,
    val droppedEntries: Int,
)

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
        direction: BookshelfSortDirection = sortDirection(order),
    ): List<BookshelfEntry> = synchronized(lock) {
        val entries = readEntries().let { current ->
            if (groupId == ALL_BOOKSHELF_GROUP_ID) current else current.filter { groupId in it.groupIds }
        }
        sortBookshelf(entries, order, direction)
    }

    fun entry(albumId: String): BookshelfEntry? = synchronized(lock) {
        readEntries().firstOrNull { it.albumId == albumId }
    }

    /**
     * 分组的展示顺序，也就是 tab 栏从左到右的顺序。
     *
     * 自动排列开着时按热度降序，关掉时按用户手动拖出来的 [BOOKSHELF_GROUP_ORDER_KEY]。
     * 两份顺序各存各的：自动排列只覆盖显示，关掉后手动顺序原样回来。
     */
    fun groups(): List<BookshelfGroup> = synchronized(lock) {
        orderBookshelfGroups(
            groups = readGroups(),
            order = readGroupOrder(),
            autoOrder = autoGroupOrder(),
            usage = readGroupUsage(),
            now = now(),
        )
    }

    fun autoGroupOrder(): Boolean = preferences.getBoolean(BOOKSHELF_GROUP_AUTO_ORDER_KEY, false)

    fun setAutoGroupOrder(enabled: Boolean) {
        preferences.edit { putBoolean(BOOKSHELF_GROUP_AUTO_ORDER_KEY, enabled) }
    }

    /** 保存手动顺序；表里没提到的分组仍按 createdAt 缀在后面。 */
    fun setGroupOrder(orderedIds: List<String>) = synchronized(lock) {
        val known = readGroups().mapTo(mutableSetOf(), BookshelfGroup::id)
        writeGroupOrder(orderedIds.distinct().filter { it in known })
    }

    /**
     * 记一次分组访问。
     *
     * 写入是 O(1)：把旧分数衰减到此刻再 +1，不追加历史。
     * 只在用户真的切到某个分组时调用，"全部"不计——它不参与排序。
     */
    fun recordGroupVisit(groupId: String) {
        if (groupId == ALL_BOOKSHELF_GROUP_ID) return
        synchronized(lock) {
            if (readGroups().none { it.id == groupId }) return
            val timestamp = now()
            val current = readGroupUsage()
            val existing = current.firstOrNull { it.id == groupId }
            val updated = BookshelfGroupUsage(
                id = groupId,
                score = decayedGroupScore(existing, timestamp) + 1.0,
                updatedAt = timestamp,
            )
            writeGroupUsage(current.filterNot { it.id == groupId } + updated)
        }
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
        matchByAuthors: Boolean = false,
        authorRules: List<String> = emptyList(),
        authorMatchSource: BookshelfAuthorMatchSource = BookshelfAuthorMatchSource.FAVORITES,
    ): BookshelfGroup? = synchronized(lock) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty) ?: return@synchronized null
        val current = readGroups()
        // 这里刻意没有数量上限：40 个的旧上限只会让第 41 次创建静默失败（issue #10）。
        if (current.any { it.name.equals(normalizedName, ignoreCase = true) }) {
            return@synchronized null
        }
        val normalizedTags = tagRules.mapNotNull(::normalizeSearchTag).distinct()
        val normalizedAuthors = authorRules.mapNotNull(::normalizeSearchTag).distinct()
        val timestamp = now()
        val group = BookshelfGroup(
            id = "group-${UUID.randomUUID()}",
            name = normalizedName,
            matchFavoritesByTags = matchFavoritesByTags && normalizedTags.isNotEmpty(),
            tagRules = normalizedTags,
            matchByAuthors = matchByAuthors && normalizedAuthors.isNotEmpty(),
            authorRules = normalizedAuthors,
            authorMatchSource = authorMatchSource,
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
        matchByAuthors: Boolean = false,
        authorRules: List<String> = emptyList(),
        authorMatchSource: BookshelfAuthorMatchSource = BookshelfAuthorMatchSource.FAVORITES,
    ): BookshelfGroup? = synchronized(lock) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty) ?: return@synchronized null
        val current = readGroups()
        if (current.any { it.id != groupId && it.name.equals(normalizedName, ignoreCase = true) }) {
            return@synchronized null
        }
        val normalizedTags = tagRules.mapNotNull(::normalizeSearchTag).distinct()
        val normalizedAuthors = authorRules.mapNotNull(::normalizeSearchTag).distinct()
        var updatedGroup: BookshelfGroup? = null
        val updated = current.map { group ->
            if (group.id != groupId) {
                group
            } else {
                group.copy(
                    name = normalizedName,
                    matchFavoritesByTags = matchFavoritesByTags && normalizedTags.isNotEmpty(),
                    tagRules = normalizedTags,
                    matchByAuthors = matchByAuthors && normalizedAuthors.isNotEmpty(),
                    authorRules = normalizedAuthors,
                    authorMatchSource = authorMatchSource,
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
        // 顺手清掉这个分组的顺序与热度残留：留着不会影响排序（两处都按现存分组过滤），
        // 但同名分组被重建后会莫名继承旧热度。
        writeGroupOrder(readGroupOrder().filterNot { it == groupId })
        writeGroupUsage(readGroupUsage().filterNot { it.id == groupId })
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

    /**
     * 排序方向按字段分别记住，而不是一个全局开关。
     *
     * 理由是"倒序"在不同字段下含义完全不同（名称 Z→A 和更新时间最新在前），
     * 共用一个开关会让切字段时方向莫名其妙地翻过来。没存过就用字段自己的习惯方向。
     */
    fun sortDirection(order: BookshelfSortOrder = sortOrder()): BookshelfSortDirection {
        val stored = preferences.getString(bookshelfSortDirectionKey(order), null)
        return BookshelfSortDirection.entries.firstOrNull { it.name == stored }
            ?: order.defaultDirection
    }

    fun setSortDirection(order: BookshelfSortOrder, direction: BookshelfSortDirection) {
        preferences.edit { putString(bookshelfSortDirectionKey(order), direction.name) }
    }

    /**
     * 取一份可导出的书架快照。
     *
     * [groupIds] 传 null 表示"全部"，此时 [includeGroups] 决定要不要带上分组定义；
     * 按分组导出时分组定义一定会带上——不带的话对方收到的是一堆无处安放的漫画。
     *
     * 条目里的 groupIds 会被裁到真正导出的分组集合上：留着悬空的分组 id，
     * 对方导入后就会出现"漫画属于一个看不见的分组"。
     */
    fun snapshot(
        groupIds: Set<String>? = null,
        includeGroups: Boolean = true,
    ): BookshelfSnapshot = synchronized(lock) {
        bookshelfSnapshotOf(
            entries = readEntries(),
            groups = readGroups().sortedBy(BookshelfGroup::createdAt),
            groupIds = groupIds,
            includeGroups = includeGroups,
        )
    }

    /** 整体替换：本地书架与分组全部作废，只留导入内容。 */
    fun replaceWith(snapshot: BookshelfSnapshot): BookshelfImportOutcome = synchronized(lock) {
        val (next, outcome) = replaceBookshelfWith(snapshot)
        writeGroups(next.groups)
        writeEntries(next.entries)
        outcome
    }

    /** 合并导入：只做加法，细则见 [mergeBookshelfWith]。 */
    fun mergeFrom(snapshot: BookshelfSnapshot): BookshelfImportOutcome = synchronized(lock) {
        val (next, outcome) = mergeBookshelfWith(
            entries = readEntries(),
            groups = readGroups(),
            snapshot = snapshot,
            mergedAt = now(),
        )
        if (outcome.addedGroups > 0) writeGroups(next.groups)
        if (outcome.addedEntries > 0 || outcome.mergedEntries > 0) writeEntries(next.entries)
        outcome
    }

    private fun readEntries(): List<BookshelfEntry> {
        val encoded = preferences.getString(BOOKSHELF_ENTRIES_KEY, null) ?: return emptyList()
        return runCatching { JSONArray(encoded).toBookshelfEntries() }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<BookshelfEntry>) {
        val array = JSONArray()
        entries.take(MAX_BOOKSHELF_ENTRIES).forEach { array.put(it.toJson()) }
        preferences.edit { putString(BOOKSHELF_ENTRIES_KEY, array.toString()) }
    }

    private fun readGroups(): List<BookshelfGroup> {
        val encoded = preferences.getString(BOOKSHELF_GROUPS_KEY, null) ?: return emptyList()
        return runCatching { JSONArray(encoded).toBookshelfGroups() }.getOrDefault(emptyList())
    }

    private fun writeGroups(groups: List<BookshelfGroup>) {
        val array = JSONArray()
        groups.take(BOOKSHELF_GROUP_SAFETY_LIMIT).forEach { array.put(it.toJson()) }
        preferences.edit { putString(BOOKSHELF_GROUPS_KEY, array.toString()) }
    }

    private fun readGroupOrder(): List<String> =
        decodeBookshelfGroupOrder(preferences.getString(BOOKSHELF_GROUP_ORDER_KEY, null))

    private fun writeGroupOrder(orderedIds: List<String>) {
        preferences.edit { putString(BOOKSHELF_GROUP_ORDER_KEY, encodeBookshelfGroupOrder(orderedIds)) }
    }

    private fun readGroupUsage(): List<BookshelfGroupUsage> =
        decodeBookshelfGroupUsage(preferences.getString(BOOKSHELF_GROUP_USAGE_KEY, null))

    private fun writeGroupUsage(usage: List<BookshelfGroupUsage>) {
        preferences.edit { putString(BOOKSHELF_GROUP_USAGE_KEY, encodeBookshelfGroupUsage(usage)) }
    }
}

/** 方向按字段分开存，key 里带上字段名。 */
private fun bookshelfSortDirectionKey(order: BookshelfSortOrder): String =
    BOOKSHELF_SORT_DIRECTION_KEY_PREFIX + order.name

/**
 * 先按字段构造"正序"（自然顺序）比较器，再按方向决定是否整体反转。
 *
 * 自然顺序不是简单的"由小到大"：名称是 A→Z，时间类却是**最新在前**（时间递减），
 * 因为对时间字段来说"顺着时间"就是最近的排最前。反转（[BookshelfSortDirection.DESCENDING]）
 * 后与改动前的降序比较器逐项等价（含次级排序键），所以默认方向下排序表现不变。
 */
internal fun sortBookshelf(
    entries: List<BookshelfEntry>,
    order: BookshelfSortOrder,
    direction: BookshelfSortDirection = order.defaultDirection,
): List<BookshelfEntry> {
    val natural: Comparator<BookshelfEntry> = when (order) {
        BookshelfSortOrder.NAME ->
            compareBy<BookshelfEntry> { it.name.lowercase(Locale.ROOT) }
                .thenBy(BookshelfEntry::albumId)
        // 时间类正序 = 最新在前，所以主键降序、次级键也降序。
        BookshelfSortOrder.UPDATED ->
            compareByDescending<BookshelfEntry>(BookshelfEntry::updatedAt)
                .thenByDescending(BookshelfEntry::addedAt)
        BookshelfSortOrder.ADDED ->
            compareByDescending<BookshelfEntry>(BookshelfEntry::addedAt)
                .thenBy(BookshelfEntry::albumId)
        BookshelfSortOrder.RECENTLY_READ ->
            compareByDescending<BookshelfEntry> { it.lastReadAt ?: Long.MIN_VALUE }
                .thenByDescending(BookshelfEntry::updatedAt)
    }
    val comparator = when (direction) {
        BookshelfSortDirection.ASCENDING -> natural
        BookshelfSortDirection.DESCENDING -> natural.reversed()
    }
    return entries.sortedWith(comparator)
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

internal fun parseBookshelfTagRules(value: String): List<String> = parseBookshelfRules(value)

internal fun parseBookshelfAuthorRules(value: String): List<String> = parseBookshelfRules(value)

private fun parseBookshelfRules(value: String): List<String> = value
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

/** 作者规则要求全部命中，兼容服务端将多位作者合并到同一字段的返回形式。 */
internal fun matchesBookshelfAuthorRules(author: String, rules: List<String>): Boolean {
    if (rules.isEmpty()) return false
    val available = normalizeSearchTag(author) ?: return false
    return rules.mapNotNull(::normalizeSearchTag).all { target -> available.contains(target) }
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
    put("match_by_authors", matchByAuthors)
    put("author_rules", JSONArray(authorRules))
    put("author_match_source", authorMatchSource.name)
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
        matchByAuthors = optBoolean("match_by_authors", false),
        authorRules = optStringList("author_rules").mapNotNull(::normalizeSearchTag).distinct(),
        authorMatchSource = BookshelfAuthorMatchSource.entries.firstOrNull {
            it.name == optString("author_match_source")
        } ?: BookshelfAuthorMatchSource.FAVORITES,
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

private fun JSONArray.toBookshelfEntries(): List<BookshelfEntry> {
    val parsed = mutableListOf<BookshelfEntry>()
    repeat(length()) { index -> optJSONObject(index)?.toBookshelfEntryOrNull()?.let(parsed::add) }
    return parsed.distinctBy(BookshelfEntry::albumId)
}

private fun JSONArray.toBookshelfGroups(): List<BookshelfGroup> {
    val parsed = mutableListOf<BookshelfGroup>()
    repeat(length()) { index -> optJSONObject(index)?.toBookshelfGroupOrNull()?.let(parsed::add) }
    return parsed.distinctBy(BookshelfGroup::id)
}

/**
 * 把一条热度折算到 [now] 时刻。
 *
 * 半衰期 [BOOKSHELF_GROUP_USAGE_HALF_LIFE_MILLIS]：7 天不碰，分数减半。
 * 这样"常看"与"最近看"是同一个数在管，不需要分别存频次和时间戳再加权。
 * [usage] 为 null（从未访问）算 0；时钟回拨导致的负时差按 0 处理，否则分数会被放大。
 */
internal fun decayedGroupScore(usage: BookshelfGroupUsage?, now: Long): Double {
    if (usage == null || usage.score <= 0.0) return 0.0
    val elapsed = (now - usage.updatedAt).coerceAtLeast(0L)
    if (elapsed == 0L) return usage.score
    val halfLives = elapsed.toDouble() / BOOKSHELF_GROUP_USAGE_HALF_LIFE_MILLIS
    return usage.score * 2.0.pow(-halfLives)
}

/**
 * 分组的最终展示顺序。
 *
 * [autoOrder] 关：按 [order] 里的下标排，没登记过的分组按 createdAt 追加到末尾——
 * 新建的分组总是出现在最右边，符合"刚建的在后面"的直觉。
 * [autoOrder] 开：按折算到 [now] 的热度降序，并列时退回手动顺序、再退回 createdAt，
 * 保证同样的输入永远得到同样的顺序（否则 tab 栏会在两次进入之间莫名换位）。
 *
 * 手动顺序不会被自动排列覆盖写掉，所以关掉开关就原样回来。
 */
internal fun orderBookshelfGroups(
    groups: List<BookshelfGroup>,
    order: List<String>,
    autoOrder: Boolean,
    usage: List<BookshelfGroupUsage>,
    now: Long,
): List<BookshelfGroup> {
    if (groups.size <= 1) return groups
    val manualRank = order.withIndex().associate { (index, id) -> id to index }
    // 手动名次缺失时排在所有登记过的分组之后，彼此再按 createdAt 分先后。
    fun manualOf(group: BookshelfGroup): Int = manualRank[group.id] ?: Int.MAX_VALUE
    if (!autoOrder) {
        return groups.sortedWith(
            compareBy<BookshelfGroup> { manualOf(it) }
                .thenBy(BookshelfGroup::createdAt)
                .thenBy(BookshelfGroup::id),
        )
    }
    val usageById = usage.associateBy(BookshelfGroupUsage::id)
    return groups.sortedWith(
        compareByDescending<BookshelfGroup> { decayedGroupScore(usageById[it.id], now) }
            .thenBy { manualOf(it) }
            .thenBy(BookshelfGroup::createdAt)
            .thenBy(BookshelfGroup::id),
    )
}

/**
 * 把 [from] 位置的分组挪到 [to] 位置（拖拽落位）。
 *
 * 与 swap 的区别：中间的元素整体让位，这才是拖拽的视觉预期。
 * 越界或原地不动时原样返回，调用方不必先判断。
 */
internal fun moveGroupOrder(order: List<String>, from: Int, to: Int): List<String> {
    if (from == to || from !in order.indices || to !in order.indices) return order
    val mutable = order.toMutableList()
    mutable.add(to, mutable.removeAt(from))
    return mutable
}

internal fun encodeBookshelfGroupOrder(orderedIds: List<String>): String =
    JSONArray(orderedIds).toString()

internal fun decodeBookshelfGroupOrder(encoded: String?): List<String> {
    val array = runCatching { JSONArray(encoded ?: return emptyList()) }.getOrNull() ?: return emptyList()
    return buildList {
        repeat(array.length()) { index ->
            array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }.distinct()
}

internal fun encodeBookshelfGroupUsage(usage: List<BookshelfGroupUsage>): String {
    val array = JSONArray()
    usage.forEach { item ->
        array.put(
            JSONObject().apply {
                put("id", item.id)
                put("score", item.score)
                put("updated_at", item.updatedAt)
            },
        )
    }
    return array.toString()
}

internal fun decodeBookshelfGroupUsage(encoded: String?): List<BookshelfGroupUsage> {
    val array = runCatching { JSONArray(encoded ?: return emptyList()) }.getOrNull() ?: return emptyList()
    val parsed = mutableListOf<BookshelfGroupUsage>()
    repeat(array.length()) { index ->
        val item = array.optJSONObject(index) ?: return@repeat
        val id = item.optString("id").trim().takeIf(String::isNotEmpty) ?: return@repeat
        val score = item.optDouble("score", 0.0)
        if (!score.isFinite() || score <= 0.0) return@repeat
        parsed += BookshelfGroupUsage(id = id, score = score, updatedAt = item.optLong("updated_at", 0L))
    }
    return parsed.distinctBy(BookshelfGroupUsage::id)
}

/**
 * 导出范围裁剪。
 *
 * [groupIds] 为 null 表示"全部"，此时 [includeGroups] 决定要不要带上分组定义；
 * 按分组导出时分组定义一定会带上，否则对方拿到的漫画会挂在看不见的分组 id 上。
 * 条目里的 groupIds 也会裁到真正导出的分组集合上，避免留下悬空的分组 id。
 */
internal fun bookshelfSnapshotOf(
    entries: List<BookshelfEntry>,
    groups: List<BookshelfGroup>,
    groupIds: Set<String>?,
    includeGroups: Boolean,
): BookshelfSnapshot {
    val kept = when {
        groupIds != null -> groups.filter { it.id in groupIds }
        includeGroups -> groups
        else -> emptyList()
    }
    val keptIds = kept.mapTo(mutableSetOf(), BookshelfGroup::id)
    return BookshelfSnapshot(
        entries = entries
            .filter { groupIds == null || it.groupIds.any { id -> id in keptIds } }
            .map { it.copy(groupIds = it.groupIds.intersect(keptIds)) },
        groups = kept,
    )
}

/** 整体替换后的书架内容与统计；超出上限的部分计入 droppedEntries。 */
internal fun replaceBookshelfWith(
    snapshot: BookshelfSnapshot,
): Pair<BookshelfSnapshot, BookshelfImportOutcome> {
    val groups = snapshot.groups.distinctBy(BookshelfGroup::id).take(BOOKSHELF_GROUP_SAFETY_LIMIT)
    val validGroupIds = groups.mapTo(mutableSetOf(), BookshelfGroup::id)
    val incoming = snapshot.entries.distinctBy(BookshelfEntry::albumId)
    val entries = incoming
        .map { it.copy(groupIds = it.groupIds.intersect(validGroupIds)) }
        .take(MAX_BOOKSHELF_ENTRIES)
    return BookshelfSnapshot(entries = entries, groups = groups) to BookshelfImportOutcome(
        addedEntries = entries.size,
        mergedEntries = 0,
        addedGroups = groups.size,
        reusedGroups = 0,
        droppedEntries = incoming.size - entries.size,
    )
}

/**
 * 合并导入：只做加法。
 *
 * 分组按**名称**归并而不是按 id：分组 id 是随机 UUID，照搬会让书架上出现两个同名分组。
 * 已有漫画只补分组归属，本地阅读进度一律不动——别人读到哪与我无关；
 * 新漫画保留文件里带的进度，于是"导出自己的书架再导回来"是无损的。
 */
internal fun mergeBookshelfWith(
    entries: List<BookshelfEntry>,
    groups: List<BookshelfGroup>,
    snapshot: BookshelfSnapshot,
    mergedAt: Long,
    freshGroupId: () -> String = { "group-${UUID.randomUUID()}" },
): Pair<BookshelfSnapshot, BookshelfImportOutcome> {
    val remappedGroupIds = mutableMapOf<String, String>()
    val newGroups = mutableListOf<BookshelfGroup>()
    var reusedGroups = 0
    snapshot.groups.distinctBy(BookshelfGroup::id).forEach { group ->
        val matched = (groups + newGroups).firstOrNull { it.name.equals(group.name, ignoreCase = true) }
        if (matched != null) {
            remappedGroupIds[group.id] = matched.id
            reusedGroups++
            return@forEach
        }
        if (groups.size + newGroups.size >= BOOKSHELF_GROUP_SAFETY_LIMIT) return@forEach
        // id 撞车只会发生在"导出自己的书架再导回来"，换个新 id 即可，名称归并已经兜住了重复。
        val id = if (groups.any { it.id == group.id }) freshGroupId() else group.id
        remappedGroupIds[group.id] = id
        newGroups += group.copy(id = id)
    }

    var current = entries
    var addedEntries = 0
    var mergedEntries = 0
    var droppedEntries = 0
    snapshot.entries.distinctBy(BookshelfEntry::albumId).forEach { incoming ->
        val groupIds = incoming.groupIds.mapNotNullTo(mutableSetOf()) { remappedGroupIds[it] }
        val existing = current.firstOrNull { it.albumId == incoming.albumId }
        when {
            existing == null && current.size >= MAX_BOOKSHELF_ENTRIES -> droppedEntries++
            existing == null -> {
                current = listOf(incoming.copy(groupIds = groupIds)) + current
                addedEntries++
            }
            !existing.groupIds.containsAll(groupIds) -> {
                current = current.map { entry ->
                    if (entry.albumId != incoming.albumId) {
                        entry
                    } else {
                        entry.copy(groupIds = entry.groupIds + groupIds, updatedAt = mergedAt)
                    }
                }
                mergedEntries++
            }
        }
    }
    return BookshelfSnapshot(entries = current, groups = groups + newGroups) to BookshelfImportOutcome(
        addedEntries = addedEntries,
        mergedEntries = mergedEntries,
        addedGroups = newGroups.size,
        reusedGroups = reusedGroups,
        droppedEntries = droppedEntries,
    )
}

/**
 * 导出文件的固定格式。
 *
 * 外层信封只加校验和统计字段，`entries` / `groups` 与本地存储用的是同一套编码，
 * 所以格式天然可逆：导出的文件导回来能完整还原书架。
 */
internal fun encodeBookshelfSnapshot(snapshot: BookshelfSnapshot, exportedAt: Long): String {
    val entries = JSONArray()
    snapshot.entries.take(MAX_BOOKSHELF_ENTRIES).forEach { entries.put(it.toJson()) }
    val groups = JSONArray()
    snapshot.groups.take(BOOKSHELF_GROUP_SAFETY_LIMIT).forEach { groups.put(it.toJson()) }
    return JSONObject().apply {
        put("format", BOOKSHELF_TRANSFER_FORMAT)
        put("version", BOOKSHELF_TRANSFER_VERSION)
        put("app", "JMComicX")
        put("exported_at", exportedAt)
        put("entry_count", entries.length())
        put("group_count", groups.length())
        put("groups", groups)
        put("entries", entries)
    }.toString(2)
}

/**
 * 解析导出文件。
 *
 * 只认 `format` 字段，不卡 `version`：字段都是可选读取的，未来加字段的新版文件在老版本上
 * 也能读出它认识的那部分，而不是直接告诉用户"文件不支持"。
 */
internal fun decodeBookshelfSnapshot(text: String): BookshelfSnapshot? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (root.optString("format") != BOOKSHELF_TRANSFER_FORMAT) return null
    return BookshelfSnapshot(
        entries = root.optJSONArray("entries")?.toBookshelfEntries() ?: emptyList(),
        groups = root.optJSONArray("groups")?.toBookshelfGroups() ?: emptyList(),
    )
}

internal const val BOOKSHELF_TRANSFER_FORMAT = "jmcomicx-bookshelf"
internal const val BOOKSHELF_TRANSFER_VERSION = 1
internal const val ALL_BOOKSHELF_GROUP_ID = "all"
private const val BOOKSHELF_PREFERENCES = "jmx_bookshelf"
private const val BOOKSHELF_ENTRIES_KEY = "entries"
private const val BOOKSHELF_GROUPS_KEY = "groups"
private const val BOOKSHELF_SORT_KEY = "sort_order"
private const val BOOKSHELF_SORT_DIRECTION_KEY_PREFIX = "sort_direction_"
private const val BOOKSHELF_GROUP_ORDER_KEY = "group_order"
private const val BOOKSHELF_GROUP_USAGE_KEY = "group_usage"
private const val BOOKSHELF_GROUP_AUTO_ORDER_KEY = "group_auto_order"
private const val LEGACY_RECENTLY_ADDED_SORT = "RECENTLY_ADDED"
private const val MAX_BOOKSHELF_ENTRIES = 500

/**
 * 分组数量的安全阀，不是产品意义上的上限。
 *
 * 旧的 40 上限会让第 41 次"添加分组"静默失败（issue #10），已经去掉；
 * 这里留一个远高于任何正常用法的数，只为挡住畸形导入文件把整份 SharedPreferences 撑爆。
 */
private const val BOOKSHELF_GROUP_SAFETY_LIMIT = 1000

/** 分组热度的半衰期：7 天不访问，分数减半。 */
internal const val BOOKSHELF_GROUP_USAGE_HALF_LIFE_MILLIS = 7L * 24 * 60 * 60 * 1000
private val BOOKSHELF_TAG_RULE_DELIMITERS = Regex("[\\s,，、;；]+")
