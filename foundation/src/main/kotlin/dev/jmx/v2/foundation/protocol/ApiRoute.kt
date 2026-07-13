package dev.jmx.v2.foundation.protocol

enum class ApiRoute(
    val path: String,
    val method: HttpMethod = HttpMethod.Get,
    val encryptedJson: Boolean = true,
    val tokenSecret: String = JmxProtocolConstants.AppTokenSecret
) {
    Setting("/setting"),
    Login("/login", method = HttpMethod.Post),
    Album("/album"),
    Chapter("/chapter"),
    Search("/search"),
    CategoriesFilter("/categories/filter"),
    Favorite("/favorite"),
    Like("/like", method = HttpMethod.Post),
    Promote("/promote"),
    Week("/week"),
    WeekFilter("/week/filter"),
    Forum("/forum"),
    Comment("/comment", method = HttpMethod.Post),
    Daily("/daily"),
    DailyCheck("/daily_chk", method = HttpMethod.Post),
    WatchList("/watch_list"),
    ChapterViewTemplate(
        path = "/chapter_view_template",
        encryptedJson = false,
        tokenSecret = JmxProtocolConstants.ChapterTokenSecret
    )
}

enum class HttpMethod {
    Get,
    Post
}
