package app.prismia.plus

import android.content.Context
import androidx.core.content.edit
import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.text.Normalizer
import java.util.Locale

/**
 * 搜索标签过滤条件。
 *
 * JM 服务端原生支持标签级的包含（`+标签`）与排除（`-标签`）语法，且同一次查询可以同时
 * 包含与排除多个标签，因此这里用两个集合表达，而不是旧版的单一 INCLUDE/EXCLUDE 模式。
 * 过滤在服务端一次性完成，客户端不再逐条拉取详情做匹配。
 */
internal data class SearchTagFilter(
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
) {
    val enabled: Boolean get() = includeTags.isNotEmpty() || excludeTags.isNotEmpty()

    /** 生效的标签总数，用于入口按钮上的数字角标。 */
    val activeCount: Int get() = normalizedIncludeTags.size + normalizedExcludeTags.size

    val normalizedIncludeTags: List<String>
        get() = includeTags.mapNotNull(::normalizeSearchTag).distinct()

    val normalizedExcludeTags: List<String>
        get() = excludeTags.mapNotNull(::normalizeSearchTag).distinct()
        .filterNot { it in normalizedIncludeTags }

    fun toggleInclude(tag: String): SearchTagFilter {
        val normalized = normalizeSearchTag(tag) ?: return this
        return if (normalized in normalizedIncludeTags) {
            copy(includeTags = includeTags.filter { normalizeSearchTag(it) != normalized })
        } else {
            copy(
                includeTags = includeTags + normalized,
                excludeTags = excludeTags.filter { normalizeSearchTag(it) != normalized },
            )
        }
    }

    fun toggleExclude(tag: String): SearchTagFilter {
        val normalized = normalizeSearchTag(tag) ?: return this
        return if (normalized in normalizedExcludeTags) {
            copy(excludeTags = excludeTags.filter { normalizeSearchTag(it) != normalized })
        } else {
            copy(
                excludeTags = excludeTags + normalized,
                includeTags = includeTags.filter { normalizeSearchTag(it) != normalized },
            )
        }
    }

    fun stateOf(tag: String): SearchTagState {
        val normalized = normalizeSearchTag(tag) ?: return SearchTagState.NONE
        return when (normalized) {
            in normalizedIncludeTags -> SearchTagState.INCLUDE
            in normalizedExcludeTags -> SearchTagState.EXCLUDE
            else -> SearchTagState.NONE
        }
    }

    companion object {
        val EMPTY = SearchTagFilter()
    }
}

internal enum class SearchTagState {
    NONE,
    INCLUDE,
    EXCLUDE,
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
