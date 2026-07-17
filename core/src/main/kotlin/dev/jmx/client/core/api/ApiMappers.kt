package dev.jmx.client.core.api

import com.google.gson.JsonObject
import com.google.gson.JsonElement

internal fun JsonObject.toAlbumSummary(): AlbumSummary {
    return AlbumSummary(
        id = stringOrNull("id", "album_id", "aid", "photo_id") ?: "",
        name = stringOrNull("name", "title"),
        author = stringOrNull("author", "authors") ?: stringListOrEmpty("author", "authors").firstOrNull(),
        imageCount = intOrNull("total_photo", "total_photos", "page_count", "images"),
        image = stringOrNull("image", "cover", "photo", "thumb", "thumbnail")
    )
}

internal fun JsonObject.toHomePromoteSection(): HomePromoteSection {
    return HomePromoteSection(
        id = stringOrNull("id") ?: "",
        title = stringOrNull("title", "name"),
        slug = stringOrNull("slug"),
        type = stringOrNull("type"),
        filterValue = stringOrNull("filter_val", "filterValue", "filter"),
        content = firstObjectList("content", "list", "albums", "photos")
            .map { it.toAlbumSummary() },
        raw = toRawMap()
    )
}

internal fun JsonObject.toAlbumDetail(): AlbumDetail {
    return AlbumDetail(
        id = stringOrNull("id", "album_id", "aid") ?: "",
        name = stringOrNull("name", "title"),
        description = stringOrNull("description", "desc"),
        authors = stringListOrEmpty("author", "authors"),
        imageCount = intOrNull("total_photo", "total_photos", "page_count", "images"),
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

internal fun JsonObject.toFavoritePage(): FavoritePage {
    val albumPage = toAlbumPage()
    val folders = firstObjectList("folder_list", "folders", "folder")
        .mapNotNull { it.toFavoriteFolderOrNull() }
    return FavoritePage(
        total = albumPage.total,
        content = albumPage.content,
        folders = folders,
        raw = albumPage.raw
    )
}

private fun JsonObject.toFavoriteFolderOrNull(): FavoriteFolder? {
    val id = stringOrNull("FID", "fid", "id", "0") ?: return null
    return FavoriteFolder(
        id = id,
        name = stringOrNull("name", "title", "2"),
        ownerUserId = stringOrNull("UID", "uid", "user_id", "1")
    )
}

internal fun JsonElement.toAlbumPageOrNull(): AlbumPage? {
    return when {
        isJsonObject -> asJsonObject.toAlbumPage()
        isJsonArray -> AlbumPage(
            total = null,
            content = asObjectListOrEmpty().map { it.toAlbumSummary() },
            raw = emptyMap()
        )
        else -> null
    }
}

internal fun JsonObject.toSearchPage(): SearchPage {
    val content = firstObjectList("content", "list", "data", "albums", "photos")
        .map { it.toAlbumSummary() }
    return SearchPage(
        total = intOrNull("total", "count"),
        redirectAlbumId = stringOrNull("redirect_aid", "redirectAid", "redirect_album_id"),
        content = content
    )
}

internal fun JsonElement.toSearchPageOrNull(): SearchPage? {
    return when {
        isJsonObject -> asJsonObject.toSearchPage()
        isJsonArray -> SearchPage(
            total = null,
            redirectAlbumId = null,
            content = asObjectListOrEmpty().map { it.toAlbumSummary() }
        )
        else -> null
    }
}

internal fun JsonObject.toUserProfile(): UserProfile {
    return UserProfile(
        id = intOrNull("uid", "id", "user_id"),
        username = stringOrNull("username", "user_name", "name"),
        email = stringOrNull("email"),
        avatar = stringOrNull("photo", "avatar"),
        level = intOrNull("level"),
        levelName = stringOrNull("level_name", "levelName"),
        currentLevelExp = intOrNull("exp", "currentLevelExp", "current_level_exp"),
        nextLevelExp = intOrNull("nextLevelExp", "next_level_exp"),
        expPercent = doubleOrNull("expPercent", "exp_percent"),
        currentFavoriteCount = intOrNull("album_favorites", "favorite_count", "currentFavoriteCount"),
        maxFavoriteCount = intOrNull("album_favorites_max", "max_favorite_count", "maxFavoriteCount"),
        coin = intOrNull("coin", "jcoin", "j_coin"),
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
        id = stringOrNull("id", "comment_id", "CID"),
        userId = stringOrNull("uid", "user_id", "UID"),
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
        threeDaysCoin = intOrNull("three_days_coin", "threeDaysCoin"),
        threeDaysExp = intOrNull("three_days_exp", "threeDaysExp"),
        sevenDaysCoin = intOrNull("seven_days_coin", "sevenDaysCoin"),
        sevenDaysExp = intOrNull("seven_days_exp", "sevenDaysExp"),
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

internal fun JsonObject.toPhotoDetail(): PhotoDetail {
    val pageArr = pageArrList()
    val id = stringOrNull("id", "photo_id", "aid", "album_id") ?: ""
    return PhotoDetail(
        id = id,
        name = stringOrNull("name", "title"),
        seriesId = stringOrNull("series_id", "seriesId", "album_id"),
        sort = intOrNull("sort"),
        tags = stringListOrEmpty("tags"),
        authors = stringListOrEmpty("author", "authors"),
        scrambleId = intOrNull("scramble_id", "scrambleId"),
        pageArr = pageArr,
        imageDomain = stringOrNull(
            "data_original_domain",
            "image_domain",
            "img_domain",
            "domain"
        ),
        imageCount = intOrNull("total_photo", "page_count", "images")
            ?: pageArr.size.takeIf { it > 0 },
        raw = toRawMap()
    )
}

private fun JsonObject.pageArrList(): List<String> {
    val direct = get("page_arr") ?: get("images") ?: get("pageArr")
    when {
        direct == null -> Unit
        direct.isJsonArray -> {
            return direct.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
            }.filter { it.isNotBlank() }
        }
        direct.isJsonPrimitive -> {
            val text = runCatching { direct.asString }.getOrNull()?.trim().orEmpty()
            if (text.startsWith("[")) {
                return runCatching {
                    com.google.gson.JsonParser.parseString(text).asJsonArray.mapNotNull { item ->
                        item.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
                    }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
            }
            if (text.isNotBlank()) return listOf(text)
        }
    }
    return firstObjectList("content", "list").mapNotNull {
        it.stringOrNull("name", "filename", "file", "url")
    }
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
