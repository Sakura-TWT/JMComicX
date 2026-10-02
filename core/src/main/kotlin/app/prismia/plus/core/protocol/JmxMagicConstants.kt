package app.prismia.plus.core.protocol

object JmxMagicConstants {

    const val ORDER_BY_LATEST = "mr"
    const val ORDER_BY_VIEW = "mv"
    const val ORDER_BY_PICTURE = "mp"
    const val ORDER_BY_LIKE = "tf"
    const val ORDER_BY_SCORE = "tr"
    const val ORDER_BY_COMMENT = "md"

    /**
     * 收藏夹排序：`mr` = 收藏时间（服务端默认），`mp` = 漫画更新时间。
     *
     * 收藏接口和搜索接口共用 `o` 参数，但取值语义不同（搜索里 `mp` 是"按图片数"），
     * 所以单列一组常量——在收藏页读到 [ORDER_BY_PICTURE] 只会引起误解。
     * 语义是实测确认的：`o=mp` 返回的条目按漫画 `addtime` 严格递减，`o=mr` 与 `addtime` 无关。
     */
    const val FAVORITE_ORDER_BY_FAVORITE_TIME = ORDER_BY_LATEST
    const val FAVORITE_ORDER_BY_UPDATE_TIME = ORDER_BY_PICTURE

    const val ORDER_MONTH_RANKING = "mv_m"
    const val ORDER_WEEK_RANKING = "mv_w"
    const val ORDER_DAY_RANKING = "mv_t"

    const val TIME_TODAY = "t"
    const val TIME_WEEK = "w"
    const val TIME_MONTH = "m"
    const val TIME_ALL = "a"

    const val CATEGORY_ALL = "0"
    const val CATEGORY_DOUJIN = "doujin"
    const val CATEGORY_SINGLE = "single"
    const val CATEGORY_SHORT = "short"
    const val CATEGORY_ANOTHER = "another"
    const val CATEGORY_HANMAN = "hanman"
    const val CATEGORY_MEIMAN = "meiman"
    const val CATEGORY_DOUJIN_COSPLAY = "doujin_cosplay"
    const val CATEGORY_3D = "3D"
    const val CATEGORY_ENGLISH_SITE = "english_site"

    const val SUB_CHINESE = "chinese"
    const val SUB_JAPANESE = "japanese"
    const val SUB_ANOTHER_OTHER = "other"
    const val SUB_ANOTHER_3D = "3d"
    const val SUB_ANOTHER_COSPLAY = "cosplay"
    const val SUB_DOUJIN_CG = "CG"
    const val SUB_SINGLE_YOUTH = "youth"

    /**
     * 搜索 main_tag：0 = 全站（标题/标签/作者等全部字段），3 = 仅标签。
     * 标签过滤已通过 search_query 的 `+`/`-` 语法在服务端完成，因此搜索统一用全站模式，
     * 让自由关键词仍能命中标题与作者。
     */
    const val MAIN_TAG_ALL = 0
    const val MAIN_TAG_TAG = 3

    const val DEFAULT_AUTHOR = "default_author"
    const val PAGE_SIZE_SEARCH = 80
    const val PAGE_SIZE_FAVORITE = 20

    fun categoriesFilterOrder(orderBy: String, time: String): String {
        return if (time == TIME_ALL) orderBy else "${orderBy}_$time"
    }
}
