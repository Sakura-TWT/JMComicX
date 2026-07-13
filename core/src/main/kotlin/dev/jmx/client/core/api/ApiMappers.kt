package dev.jmx.client.core.api

import com.google.gson.JsonObject

internal fun JsonObject.toAlbumSummary(): AlbumSummary {
    return AlbumSummary(
        id = stringOrNull("id", "album_id", "aid", "photo_id") ?: "",
        name = stringOrNull("name", "title"),
        author = stringOrNull("author", "authors"),
        imageCount = intOrNull("total_photo", "page_count", "images")
    )
}

internal fun JsonObject.toAlbumPage(): AlbumPage {
    val content = firstObjectList("content", "list", "data", "albums", "photos")
        .map { it.toAlbumSummary() }
    return AlbumPage(
        total = intOrNull("total", "count"),
        content = content,
        raw = toRawMap()
    )
}

internal fun JsonObject.toActionResult(): ActionResult {
    return ActionResult(
        status = stringOrNull("status", "state"),
        message = stringOrNull("msg", "message", "errorMsg"),
        type = stringOrNull("type"),
        raw = toRawMap()
    )
}

internal fun JsonObject.toCommentPage(): CommentPage {
    val comments = firstObjectList("comments", "content", "list", "data")
        .map { it.toCommentItem() }
    return CommentPage(
        total = intOrNull("total", "count"),
        comments = comments,
        raw = toRawMap()
    )
}

internal fun JsonObject.toCommentItem(): CommentItem {
    return CommentItem(
        id = stringOrNull("id", "comment_id"),
        userId = stringOrNull("uid", "user_id"),
        username = stringOrNull("username", "user_name", "name"),
        content = stringOrNull("content", "comment"),
        createdAt = stringOrNull("addtime", "created_at", "time"),
        likes = intOrNull("likes", "like"),
        replies = firstObjectList("replys", "replies").map { it.toCommentItem() }
    )
}

internal fun JsonObject.toDailyCheckInfo(): DailyCheckInfo {
    return DailyCheckInfo(
        dailyId = intOrNull("daily_id", "dailyId"),
        eventName = stringOrNull("event_name", "eventName"),
        currentProgress = stringOrNull("currentProgress", "current_progress"),
        records = recordList(),
        raw = toRawMap()
    )
}

private fun JsonObject.firstObjectList(vararg names: String): List<JsonObject> {
    for (name in names) {
        val list = get(name).asObjectListOrEmpty()
        if (list.isNotEmpty()) return list
    }
    return emptyList()
}

private fun JsonObject.recordList(): List<DailyRecord> {
    val record = get("record")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?: return emptyList()
    return record.flatMap { row ->
        if (row.isJsonArray) {
            row.asJsonArray.mapNotNull { it.asObjectOrNull()?.toDailyRecord() }
        } else {
            listOfNotNull(row.asObjectOrNull()?.toDailyRecord())
        }
    }
}

private fun JsonObject.toDailyRecord(): DailyRecord {
    return DailyRecord(
        date = stringOrNull("date"),
        signed = booleanOrNull("signed", "is_sign"),
        bonus = booleanOrNull("bonus", "has_bonus")
    )
}
