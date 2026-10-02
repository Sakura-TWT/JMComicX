package dev.jmx.client.core.protocol

/**
 * @param cacheBuster 每次实际出网时附加 `t=<unix 秒>` 查询参数。
 *   官方客户端只对 /setting 这么做：这条响应会被中间层（CDN / 运营商缓存）缓住，
 *   而它同时承载了 API 版本号与图片主机，拿到旧值会连带整个会话都用错参数。
 *   该参数由 [dev.jmx.client.core.network.JmxHttpClient] 在构建 URL 时追加，
 *   不进入 ApiRequest.query，因此不影响并发去重键与本地响应缓存键
 *   （否则每秒一个新键，本地缓存对 setting 直接失效）。
 */
enum class ApiRoute(
    val path: String,
    val method: HttpMethod = HttpMethod.Get,
    val encryptedJson: Boolean = true,
    val tokenSecret: String = JmxProtocolConstants.AppTokenSecret,
    val cacheBuster: Boolean = false
) {
    Setting("/setting", cacheBuster = true),
    Login("/login", method = HttpMethod.Post),
    Album("/album"),
    Chapter("/chapter"),
    Search("/search"),
    CategoriesFilter("/categories/filter"),
    Favorite("/favorite"),
    FavoriteAction("/favorite", method = HttpMethod.Post),
    FavoriteFolderAction("/favorite_folder", method = HttpMethod.Post),
    Like("/like", method = HttpMethod.Post),
    Promote("/promote"),
    PromoteList("/promote_list"),
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
