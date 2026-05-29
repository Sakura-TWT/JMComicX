package dev.jmx.client.data.models

data class LocalSetting(
    val apiList: List<String> = listOf(
        "https://www.cdnhth.club",
        "https://www.cdnmhwscc.vip",
        "https://www.jmapiproxyxxx.vip",
        "https://www.cdnxxx-proxy.xyz",
        "https://www.jmeadpoolcdn.life"
    ),
    val api: String = apiList[0],
    val themeList: List<String> = listOf(
        "auto",
        "light",
        "dark",
    ),
    val theme: String = "auto",
    val colorSchemeList: List<String> = listOf(
        "dynamic",
        "google",
        "jmx",
        "ocean",
        "forest",
        "sunset",
    ),
    val colorScheme: String = "dynamic",
    val shunt: String = "1",
    val shuntList: List<String> = listOf(
        "1",
        "2",
        "3",
        "4",
    ),
    // 阅读页预先加载的图片张数
    val prefetchCount: Int = 3,
    // ahead || nearby
    val imageLoadStrategy: String = "ahead",
    // scroll || page
    val readMode: String = "scroll",
    val showAlbumScrollReadTip: Boolean = true,
    val showAlbumPageReadTip: Boolean = true,
)
