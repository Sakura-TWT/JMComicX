package dev.jmx.client

import dev.jmx.client.core.api.WeekType

/** Human-readable fallbacks for the three IDs verified in actual /week metadata. Wire IDs stay intact. */
internal fun WeekType.displayTitle(): String {
    val name = title?.trim().orEmpty()
    if (name.isNotBlank() && !name.equals(id, ignoreCase = true)) return name
    return when (id.lowercase()) {
        "manga" -> "日漫"
        "another" -> "其他"
        "hanman" -> "韩漫"
        else -> name.ifBlank { "类型 $id" }
    }
}
