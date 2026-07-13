package dev.jmx.v2.foundation.protocol

object JmxProtocolConstants {
    const val DefaultApiVersion = "2.0.26"
    const val AppTokenSecret = "185Hcomic3PAPP7R"
    const val ChapterTokenSecret = "18comicAPPContent"
    const val DataSecret = "185Hcomic3PAPP7R"
    const val DomainServerSecret = "diosfjckwpqpdfjkvnqQjsik"

    const val Scramble220980 = 220980
    const val Scramble268850 = 268850
    const val Scramble421926 = 421926

    val DefaultApiHosts = listOf(
        "https://www.cdnaspa.club",
        "https://www.cdnaspa.vip",
        "https://www.cdnplaystation6.cc",
        "https://www.cdnplaystation6.vip"
    )

    val DefaultImageHosts = listOf(
        "https://cdn-msp.jmapiproxy1.cc",
        "https://cdn-msp.jmapiproxy2.cc",
        "https://cdn-msp2.jmapiproxy2.cc",
        "https://cdn-msp3.jmapiproxy2.cc",
        "https://cdn-msp.jmapinodeudzn.net",
        "https://cdn-msp3.jmapinodeudzn.net"
    )

    val DomainServerUrls = listOf(
        "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
        "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt"
    )

    const val MobileUserAgent =
        "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
}
