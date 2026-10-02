package app.prismia.plus

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一部漫画的更新追踪记录。
 *
 * [seenChapterCount] 是"用户上次看到这部漫画时它有多少话"，也就是基线；
 * [latestChapterCount] 是最近一次扫描时服务端给出的话数。两者之差就是待提示的更新量。
 *
 * 之所以存话数而不是存时间戳：收藏/书架摘要里根本没有任何时间字段（`AlbumSummary` 只有
 * id/name/author/imageCount），唯一能稳定反映"又更新了几话"的量就是 `/album` 里的章节列表长度。
 */
internal data class AlbumUpdateRecord(
    val albumId: String,
    val seenChapterCount: Int,
    val latestChapterCount: Int,
    val checkedAt: Long,
) {
    /**
     * 待提示的新增话数。
     *
     * 负数会被夹到 0：作者删话、或服务端把多话合并成一话时话数会变少，
     * 那不是"更新"，不该冒红点，也不该让基线看起来比现实大。
     */
    val pendingChapters: Int get() = (latestChapterCount - seenChapterCount).coerceAtLeast(0)

    val hasUpdate: Boolean get() = pendingChapters > 0
}

/**
 * 更新追踪的持久化存储。
 *
 * 只记账，不出网：扫描由 [AlbumUpdateScanner] 负责，扫完把话数喂给 [observe]。
 * 基线的清除时机由界面决定——用户点开漫画详情就算"看到了"，不要求真的翻页阅读。
 */
internal class AlbumUpdateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        ALBUM_UPDATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    fun records(): Map<String, AlbumUpdateRecord> = synchronized(lock) { readRecords() }

    fun record(albumId: String): AlbumUpdateRecord? = synchronized(lock) {
        readRecords()[albumId.trim()]
    }

    /** 有更新的漫画部数，也就是"我的"页漫画收藏右边那个数字。 */
    fun pendingAlbumCount(): Int = records().values.count(AlbumUpdateRecord::hasUpdate)

    fun pendingAlbumIds(): Set<String> = records().values
        .filter(AlbumUpdateRecord::hasUpdate)
        .mapTo(mutableSetOf(), AlbumUpdateRecord::albumId)

    /**
     * 记下一次观测到的话数。
     *
     * 第一次见到某部漫画时基线直接设成当前话数——刚加入书架就冒出"更新 30 话"是噪音，
     * 不是信息。之后话数变多才会攒出待提示量。
     */
    fun observe(albumId: String, chapterCount: Int, at: Long = System.currentTimeMillis()) {
        observeAll(mapOf(albumId to chapterCount), at)
    }

    fun observeAll(chapterCounts: Map<String, Int>, at: Long = System.currentTimeMillis()) {
        if (chapterCounts.isEmpty()) return
        synchronized(lock) {
            val current = readRecords().toMutableMap()
            chapterCounts.forEach { (rawId, rawCount) ->
                val albumId = rawId.trim()
                if (albumId.isEmpty()) return@forEach
                val count = rawCount.coerceAtLeast(0)
                val existing = current[albumId]
                current[albumId] = if (existing == null) {
                    AlbumUpdateRecord(albumId, count, count, at)
                } else {
                    existing.copy(latestChapterCount = count, checkedAt = at)
                }
            }
            writeRecords(current)
        }
    }

    /** 用户点开了这部漫画：把基线抬到最新话数，红点/更新条随之消失。 */
    fun markSeen(albumId: String, at: Long = System.currentTimeMillis()) {
        val id = albumId.trim()
        if (id.isEmpty()) return
        synchronized(lock) {
            val current = readRecords().toMutableMap()
            val existing = current[id] ?: return
            if (existing.seenChapterCount == existing.latestChapterCount) return
            current[id] = existing.copy(
                seenChapterCount = existing.latestChapterCount,
                checkedAt = at,
            )
            writeRecords(current)
        }
    }

    /**
     * 点开详情页时顺手对账。
     *
     * 详情页本来就要拉 `/album`，那份数据比上次扫描更新，所以这里同时刷新最新话数并抬基线，
     * 省掉"先扫到 +2、点进去看到 +3、退出来还剩 +1"这种灵异残留。
     */
    fun markSeenWithChapterCount(
        albumId: String,
        chapterCount: Int,
        at: Long = System.currentTimeMillis(),
    ) {
        val id = albumId.trim()
        if (id.isEmpty()) return
        val count = chapterCount.coerceAtLeast(0)
        synchronized(lock) {
            val current = readRecords().toMutableMap()
            current[id] = AlbumUpdateRecord(id, count, count, at)
            writeRecords(current)
        }
    }

    /**
     * 已知的收藏漫画 id。
     *
     * "我的 → 漫画收藏"的角标只能数收藏里的更新，而书架和收藏是两套集合：一部只在书架、
     * 没被收藏的漫画，它的更新不该算进收藏角标。收藏列表又要登录才拿得到，所以把上一次
     * 成功枚举的收藏 id 落盘，冷启动扫描被最小间隔挡掉时也能算出正确的角标。
     */
    fun favoriteAlbumIds(): Set<String> =
        preferences.getStringSet(ALBUM_UPDATE_FAVORITES_KEY, null)
            ?.mapTo(mutableSetOf()) { it.trim() }
            ?.filterTo(mutableSetOf()) { it.isNotEmpty() }
            ?: emptySet()

    fun setFavoriteAlbumIds(albumIds: Set<String>) {
        val normalized = albumIds.mapTo(mutableSetOf()) { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
        preferences.edit { putStringSet(ALBUM_UPDATE_FAVORITES_KEY, normalized) }
    }

    /** 不再关注的漫画（移出书架且取消收藏）要清掉，否则记录会无限堆积。 */
    fun retainOnly(albumIds: Set<String>) {
        val keep = albumIds.mapTo(mutableSetOf()) { it.trim() }.filter { it.isNotEmpty() }.toSet()
        synchronized(lock) {
            val current = readRecords()
            if (current.keys.all { it in keep }) return
            writeRecords(current.filterKeys { it in keep })
        }
    }

    fun lastScanAt(): Long = preferences.getLong(ALBUM_UPDATE_LAST_SCAN_KEY, 0L)

    fun setLastScanAt(at: Long) {
        preferences.edit { putLong(ALBUM_UPDATE_LAST_SCAN_KEY, at) }
    }

    /**
     * 调试用：把基线往回调，制造"有更新"的假象。
     *
     * 真实更新可能几天都等不到一次，没有这个开关就没法验证红点、角标、消失时机这些逻辑。
     * [chapters] 是想伪造的新增话数。
     */
    fun simulateUpdates(albumIds: List<String>, chapters: Int, at: Long = System.currentTimeMillis()): Int {
        if (albumIds.isEmpty() || chapters <= 0) return 0
        synchronized(lock) {
            val current = readRecords().toMutableMap()
            var affected = 0
            albumIds.forEach { rawId ->
                val id = rawId.trim()
                if (id.isEmpty()) return@forEach
                val existing = current[id]
                // 没扫过的漫画也要能模拟：假设它现在有 chapters 话，基线为 0。
                val latest = existing?.latestChapterCount ?: chapters
                current[id] = AlbumUpdateRecord(
                    albumId = id,
                    seenChapterCount = (latest - chapters).coerceAtLeast(0),
                    latestChapterCount = latest.coerceAtLeast(chapters),
                    checkedAt = at,
                )
                affected++
            }
            writeRecords(current)
            return affected
        }
    }

    /** 调试用：把所有更新提示按"已看过"处理，方便反复验证。 */
    fun clearAllPending(at: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val current = readRecords()
            if (current.values.none(AlbumUpdateRecord::hasUpdate)) return
            writeRecords(
                current.mapValues { (_, record) ->
                    record.copy(seenChapterCount = record.latestChapterCount, checkedAt = at)
                },
            )
        }
    }

    private fun readRecords(): Map<String, AlbumUpdateRecord> {
        val raw = preferences.getString(ALBUM_UPDATE_RECORDS_KEY, null) ?: return emptyMap()
        return decodeAlbumUpdateRecords(raw)
    }

    private fun writeRecords(records: Map<String, AlbumUpdateRecord>) {
        // 上限保护：书架有 500 上限，收藏可以更多，但没人需要无限长的历史记录。
        val trimmed = if (records.size <= MAX_ALBUM_UPDATE_RECORDS) {
            records
        } else {
            records.values
                .sortedWith(
                    // 留着有更新的，其余按最近检查时间保留。
                    compareByDescending<AlbumUpdateRecord> { it.hasUpdate }
                        .thenByDescending(AlbumUpdateRecord::checkedAt),
                )
                .take(MAX_ALBUM_UPDATE_RECORDS)
                .associateBy(AlbumUpdateRecord::albumId)
        }
        preferences.edit {
            putString(ALBUM_UPDATE_RECORDS_KEY, encodeAlbumUpdateRecords(trimmed))
        }
    }
}

/**
 * "我的 → 漫画收藏"角标要显示的数字：收藏里有几部漫画更新了。
 *
 * 只看收藏成员，[favoriteAlbumIds] 之外的书架漫画即使有更新也不算——
 * 这正是"只在书架的漫画却让收藏冒红点"的成因。
 */
internal fun countPendingFavorites(
    records: Map<String, AlbumUpdateRecord>,
    favoriteAlbumIds: Set<String>,
): Int = records.values.count { it.hasUpdate && it.albumId in favoriteAlbumIds }

internal fun encodeAlbumUpdateRecords(records: Map<String, AlbumUpdateRecord>): String {
    val array = JSONArray()
    records.values.forEach { record ->
        array.put(
            JSONObject().apply {
                put("id", record.albumId)
                put("seen", record.seenChapterCount)
                put("latest", record.latestChapterCount)
                put("checkedAt", record.checkedAt)
            },
        )
    }
    return array.toString()
}

internal fun decodeAlbumUpdateRecords(raw: String): Map<String, AlbumUpdateRecord> {
    if (raw.isBlank()) return emptyMap()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
    val result = LinkedHashMap<String, AlbumUpdateRecord>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val id = item.optString("id").trim()
        if (id.isEmpty()) continue
        val latest = item.optInt("latest", 0).coerceAtLeast(0)
        val seen = item.optInt("seen", latest).coerceIn(0, latest)
        result[id] = AlbumUpdateRecord(
            albumId = id,
            seenChapterCount = seen,
            latestChapterCount = latest,
            checkedAt = item.optLong("checkedAt", 0L),
        )
    }
    return result
}

private const val ALBUM_UPDATE_PREFERENCES = "jmx_album_updates"
private const val ALBUM_UPDATE_RECORDS_KEY = "records"
private const val ALBUM_UPDATE_LAST_SCAN_KEY = "last_scan_at"
private const val ALBUM_UPDATE_FAVORITES_KEY = "favorite_ids"
private const val MAX_ALBUM_UPDATE_RECORDS = 1000
