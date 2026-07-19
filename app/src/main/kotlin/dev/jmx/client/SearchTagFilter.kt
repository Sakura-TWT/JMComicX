package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.text.Normalizer
import java.util.Locale

internal enum class SearchTagFilterMode {
    INCLUDE,
    EXCLUDE,
}

internal data class SearchTagFilter(
    val mode: SearchTagFilterMode = SearchTagFilterMode.INCLUDE,
    val tags: List<String> = emptyList(),
) {
    val enabled: Boolean get() = tags.isNotEmpty()

    val normalizedTags: List<String>
        get() = tags.mapNotNull(::normalizeSearchTag).distinct()

    fun matches(albumTags: List<String>): Boolean {
        val wanted = normalizedTags
        if (wanted.isEmpty()) return true
        val available = albumTags.mapNotNull(::normalizeSearchTag).distinct()
        val matches = wanted.map { target ->
            available.any { tag -> tag == target || tag.contains(target) || target.contains(tag) }
        }
        return when (mode) {
            SearchTagFilterMode.INCLUDE -> matches.all { it }
            SearchTagFilterMode.EXCLUDE -> matches.none { it }
        }
    }
}

internal val DEFAULT_SEARCH_TAGS = listOf(
    "全彩",
    "黑白",
    "无修正",
    "有修正",
    "连载中",
    "已完结",
    "韩漫",
    "日漫",
    "同人",
    "女性向",
    "男性向",
    "短篇",
    "长篇",
    "单行本",
    "合集",
    "中文",
)

internal fun normalizeSearchTag(value: String): String? {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() }
        ?: return null
    return runCatching { ZhConverterUtil.toSimple(normalized) }
        .getOrDefault(normalized)
        .trim()
        .takeIf { it.isNotBlank() }
}

internal class SearchTagStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SEARCH_TAG_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun load(): List<String> = preferences.getStringSet(USER_TAGS_KEY, emptySet())
        .orEmpty()
        .mapNotNull(::normalizeSearchTag)
        .distinct()
        .sorted()

    fun add(tag: String) {
        val normalized = normalizeSearchTag(tag) ?: return
        val updated = preferences.getStringSet(USER_TAGS_KEY, emptySet())
            .orEmpty()
            .mapNotNull(::normalizeSearchTag)
            .toMutableSet()
            .apply { add(normalized) }
        preferences.edit { putStringSet(USER_TAGS_KEY, updated) }
    }

    fun remove(tag: String) {
        val normalized = normalizeSearchTag(tag) ?: return
        val updated = preferences.getStringSet(USER_TAGS_KEY, emptySet())
            .orEmpty()
            .mapNotNull(::normalizeSearchTag)
            .filterNot { it == normalized }
            .toSet()
        preferences.edit { putStringSet(USER_TAGS_KEY, updated) }
    }
}

private const val SEARCH_TAG_PREFERENCES = "jmx_search_tags"
private const val USER_TAGS_KEY = "user_tags"
