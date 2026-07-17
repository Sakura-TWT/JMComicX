package dev.jmx.client

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

internal class SearchHistoryStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(
        SEARCH_HISTORY_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun load(): List<String> {
        val encoded = preferences.getString(SEARCH_HISTORY_KEY, null)
        val current = encoded?.let { value -> runCatching {
            val array = JSONArray(value)
            buildList {
                repeat(array.length()) { index ->
                    array.optString(index)
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
            }.distinctBy { it.lowercase() }.take(SEARCH_HISTORY_LIMIT)
        }.getOrDefault(emptyList()) }.orEmpty()
        if (current.isNotEmpty() || preferences.getBoolean(LEGACY_HISTORY_MIGRATED_KEY, false)) {
            return current
        }

        val migrated = SecureCredentialStore(applicationContext)
            .readLegacySearchHistory()
            .take(SEARCH_HISTORY_LIMIT)
        preferences.edit {
            putBoolean(LEGACY_HISTORY_MIGRATED_KEY, true)
            if (migrated.isNotEmpty()) putString(SEARCH_HISTORY_KEY, JSONArray(migrated).toString())
        }
        return migrated
    }

    fun record(current: List<String>, query: String): List<String> =
        persist(searchHistoryWith(current, query))

    fun remove(current: List<String>, query: String): List<String> =
        persist(current.filterNot { it == query })

    private fun persist(history: List<String>): List<String> {
        preferences.edit {
            putString(SEARCH_HISTORY_KEY, JSONArray(history).toString())
        }
        return history
    }
}

internal fun searchHistoryWith(
    current: List<String>,
    query: String,
    limit: Int = SEARCH_HISTORY_LIMIT,
): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty() || limit <= 0) return current.take(limit.coerceAtLeast(0))
    return buildList {
        add(normalized)
        current.forEach { item ->
            if (!item.equals(normalized, ignoreCase = true)) add(item)
        }
    }.take(limit)
}

private const val SEARCH_HISTORY_PREFERENCES = "jmx_search_history"
private const val SEARCH_HISTORY_KEY = "queries"
private const val LEGACY_HISTORY_MIGRATED_KEY = "legacy_history_migrated"
private const val SEARCH_HISTORY_LIMIT = 20
