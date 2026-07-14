package dev.jmx.client.core.protocol

object JmxMagicConstants {

    const val ORDER_BY_LATEST = "mr"
    const val ORDER_BY_VIEW = "mv"
    const val ORDER_BY_PICTURE = "mp"
    const val ORDER_BY_LIKE = "tf"
    const val ORDER_BY_SCORE = "tr"
    const val ORDER_BY_COMMENT = "md"

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

    const val DEFAULT_AUTHOR = "default_author"
    const val PAGE_SIZE_SEARCH = 80
    const val PAGE_SIZE_FAVORITE = 20

    fun categoriesFilterOrder(orderBy: String, time: String): String {
        return if (time == TIME_ALL) orderBy else "${orderBy}_$time"
    }
}
