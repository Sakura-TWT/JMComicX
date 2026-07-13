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

internal fun JsonObject.toAlbumDetail(): AlbumDetail {
    return AlbumDetail(
        id = stringOrNull("id", "album_id", "aid") ?: "",
        name = stringOrNull("name", "title"),
        description = stringOrNull("description", "desc"),
        authors = stringListOrEmpty("author", "authors"),
        imageCount = intOrNull("total_photo", "page_count", "images"),
        totalViews = intOrNull("total_views", "views"),
        likes = intOrNull("likes", "like_count"),
        commentTotal = intOrNull("comment_total", "comment_count"),
        tags = stringListOrEmpty("tags"),
        actors = stringListOrEmpty("actors", "roles"),
        works = stringListOrEmpty("works"),
        isFavorite = booleanOrNull("is_favorite", "favorite"),
        liked = booleanOrNull("liked", "is_liked"),
        related = firstObjectList("related_list", "related").mapNotNull { it.toRelatedAlbumOrNull() },
        series = firstObjectList("series", "chapters").mapNotNull { it.toAlbumChapterOrNull() },
        seriesId = stringOrNull("series_id", "seriesId"),
        price = intOrNull("price"),
        purchased = booleanOrNull("purchased", "is_buy", "isBuy"),
        raw = toRawMap()
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

internal fun JsonObject.toWeekInfo(): WeekInfo {
    return WeekInfo(
        categories = firstObjectList("categories", "category")
            .mapNotNull { it.toWeekCategoryOrNull() },
        types = firstObjectList("type", "types")
            .mapNotNull { it.toWeekTypeOrNull() },
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

private fun JsonObject.toWeekCategoryOrNull(): WeekCategory? {
    val id = stringOrNull("id") ?: return null
    return WeekCategory(
        id = id,
        time = stringOrNull("time"),
        title = stringOrNull("title", "name")
    )
}

private fun JsonObject.toRelatedAlbumOrNull(): RelatedAlbum? {
    val id = stringOrNull("id", "album_id", "aid") ?: return null
    return RelatedAlbum(
        id = id,
        name = stringOrNull("name", "title"),
        author = stringOrNull("author", "authors"),
        image = stringOrNull("image", "cover")
    )
}

private fun JsonObject.toAlbumChapterOrNull(): AlbumChapter? {
    val id = stringOrNull("id", "chapter_id", "photo_id") ?: return null
    return AlbumChapter(
        id = id,
        name = stringOrNull("name", "title"),
        sort = stringOrNull("sort")
    )
}

private fun JsonObject.toWeekTypeOrNull(): WeekType? {
    val id = stringOrNull("id") ?: return null
    return WeekType(
        id = id,
        title = stringOrNull("title", "name")
    )
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
