package app.prismia.plus.core.network

import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.protocol.HttpMethod

/**
 * 单条路由的缓存时效。
 *
 * @param freshMillis 视为新鲜的时长：命中即直接返回，不发请求。
 * @param staleMillis 可容忍的过期时长：命中先返回旧值，同时在后台静默校验并回写。
 *   为 0 表示不接受过期内容（过期即当作未命中）。必须 >= [freshMillis]。
 */
data class ResponseCacheRule(
    val freshMillis: Long,
    val staleMillis: Long = freshMillis,
)

interface ResponseCachePolicy {
    /** 返回 null 表示该请求不缓存。 */
    fun ruleFor(request: ApiRequest): ResponseCacheRule?
}

/**
 * 默认策略：只缓存"与登录态无关"的列表与配置类 GET 路由。
 *
 * 刻意不缓存的：
 * - 所有 POST（登录、收藏、点赞、签到、评论）——都是写操作。
 * - [ApiRoute.Favorite] / [ApiRoute.WatchList]：跟账号绑定，缓存键里没有会话身份，
 *   换账号会串数据。
 * - [ApiRoute.Album] / [ApiRoute.Chapter]：响应里带 `liked` / 收藏态等个人化字段，
 *   缓存后会显示过期的收藏状态。要缓存需要先把这些字段从缓存内容里剥离。
 * - [ApiRoute.Search]：键空间无上限，且用户对搜索结果的新鲜度最敏感。
 * - [ApiRoute.Forum] / [ApiRoute.Comment]：评论区被当作实时内容。
 * - [ApiRoute.Daily]：虽然是 GET，但返回的是"这个账号今天签没签、当期活动编号是多少"，
 *   与 [ApiRoute.Favorite] 同属账号绑定数据。缓存它有两处实际后果：
 *   自动签到会读到旧的"今天已签到"而直接跳过；提交签到用的 `daily_id` 也来自这份响应，
 *   活动换期后会拿着过期编号去提交。
 */
class DefaultResponseCachePolicy(
    private val rules: Map<ApiRoute, ResponseCacheRule> = DefaultRules,
) : ResponseCachePolicy {
    override fun ruleFor(request: ApiRequest): ResponseCacheRule? {
        if (request.route.method != HttpMethod.Get) return null
        if (!request.requireSuccessCode) return null
        return rules[request.route]
    }

    companion object {
        private const val MINUTE = 60 * 1000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        val DefaultRules: Map<ApiRoute, ResponseCacheRule> = mapOf(
            // 配置类：官方客户端把 setting 放进 localStorage 长期复用，只在请求上加 t= 破缓存。
            ApiRoute.Setting to ResponseCacheRule(freshMillis = 6 * HOUR, staleMillis = 7 * DAY),
            ApiRoute.CategoriesFilter to ResponseCacheRule(freshMillis = 6 * HOUR, staleMillis = 7 * DAY),
            // 首页：新鲜期短，过期后仍先出旧内容再后台校验，冷启动不再是空白 Loading。
            ApiRoute.Promote to ResponseCacheRule(freshMillis = 3 * MINUTE, staleMillis = 3 * DAY),
            ApiRoute.PromoteList to ResponseCacheRule(freshMillis = 3 * MINUTE, staleMillis = 1 * DAY),
            ApiRoute.Week to ResponseCacheRule(freshMillis = 30 * MINUTE, staleMillis = 3 * DAY),
            ApiRoute.WeekFilter to ResponseCacheRule(freshMillis = 30 * MINUTE, staleMillis = 3 * DAY),
        )
    }
}
