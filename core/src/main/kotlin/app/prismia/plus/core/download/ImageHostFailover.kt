package app.prismia.plus.core.download

import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.result.JmxError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 图片地址的候补选择：一次下载失败后，决定"下一个该试哪个地址"。
 *
 * 禁漫的图片同时放在多台 CDN 上（见 [JmxProtocolConstants.DefaultImageHosts]），任一台都取得到；
 * 章节内图片的后缀也不统一，而 /chapter_view_template 给出的后缀偶尔与实际存放的不一致。
 * 单地址一次性下载因此有两类几乎必然遇到的失败：机器不通/过载，以及后缀猜错。
 * 接口侧有 [app.prismia.plus.core.network.ApiEndpointManager] 兜着，图片侧此前没有任何兜底，
 * 而阅读与批量下载的请求量几乎全在图片侧。
 *
 * 换机器还是换后缀由错误类型决定，不做无脑穷举：后缀猜错在别的机器上一样是 404，
 * 机器不通换后缀也一样连不上，混着试只会白等超时。
 */
object ImageHostFailover {
    /**
     * 后缀尝试顺序。**刻意不含 .gif**：
     * [app.prismia.plus.core.image.ImageScramble.isGif] 用后缀判断是否需要还原分段，
     * 把 .webp 换成 .gif（或反过来）会连带改掉上层已经算好的还原决策，
     * 拿到的字节即使正确也会被错误地当成另一类图处理。分段数本身只取决于不带后缀的文件名，
     * 所以静态图之间互换是安全的。
     */
    val StillImageSuffixes: List<String> = listOf(".jpg", ".webp", ".png")

    /** 图片路径前缀；其他路径一律不改写——下载器是通用的，不该对任意地址乱猜候补。 */
    private const val MediaPathPrefix = "/media/"

    private enum class Axis { Host, Suffix }

    /**
     * @param triedUrls 本次下载已经试过的地址（含首个），用于避免绕回去重复试同一个。
     * @param preserveHostOrder [hosts] 是否已按优先级排好。传 true 时不再轮转，直接按给定顺序试——
     *   调用方按线路健康度排过序时必须传 true，否则轮转会把最健康的那台排到最后。
     * @return 下一个该试的地址；返回 null 表示这个错误不值得换地址重试，或候补已用尽。
     */
    fun next(
        currentUrl: String,
        error: JmxError,
        triedUrls: Set<String>,
        hosts: List<String> = JmxProtocolConstants.DefaultImageHosts,
        suffixes: List<String> = StillImageSuffixes,
        preserveHostOrder: Boolean = false
    ): String? {
        val axis = failoverAxis(error) ?: return null
        val parsed = currentUrl.toHttpUrlOrNull() ?: return null
        if (!parsed.encodedPath.startsWith(MediaPathPrefix)) return null
        val candidates = when (axis) {
            Axis.Host -> hostVariants(parsed, hosts, preserveHostOrder)
            Axis.Suffix -> suffixVariants(parsed, suffixes)
        }
        return candidates.firstOrNull { it !in triedUrls }
    }

    /**
     * 这个错误该不该记到"这台机器不行"的账上。
     *
     * 404/410 是后缀猜错，与机器无关；把它算成机器的失败会让整张线路表被逐个冤枉降级。
     */
    fun indicatesHostFailure(error: JmxError): Boolean = failoverAxis(error) == Axis.Host

    private fun failoverAxis(error: JmxError): Axis? = when (error) {
        // 连不上/超时/内容不完整：这台机器现在不行，换机器。
        is JmxError.Network -> Axis.Host
        is JmxError.Http -> when (error.code) {
            // 目录里有这一页但这个后缀不存在。换机器无意义——各 CDN 是同一份存储。
            404, 410 -> Axis.Suffix
            // 地区封锁、超时、限流：换机器有机会绕开。
            403, 408, 429 -> Axis.Host
            else -> if (error.code >= 500) Axis.Host else null
        }
        // 响应类型不符（CDN 塞了错误页）：这台机器返回的不是图，换机器。
        is JmxError.Schema -> Axis.Host
        // 其余（业务错误、解码错误等）换地址救不回来。
        else -> null
    }

    /**
     * 同路径换机器。默认从内置表里当前机器**之后**的一台开始轮，轮完再回到表头，
     * 这样不同用户（起始机器不同）的重试压力不会全砸在表里第一台上。
     * [preserveHostOrder] 为 true 时（调用方已按健康度排序）只剔除当前机器，不再轮转。
     */
    private fun hostVariants(
        parsed: HttpUrl,
        hosts: List<String>,
        preserveHostOrder: Boolean
    ): List<String> {
        val normalized = hosts.mapNotNull { it.imageHostOrNull() }.distinct()
        if (normalized.isEmpty()) return emptyList()
        val ordered = if (preserveHostOrder) {
            normalized.filterNot { it.equals(parsed.host, ignoreCase = true) }
        } else {
            val currentIndex = normalized.indexOfFirst { it.equals(parsed.host, ignoreCase = true) }
            if (currentIndex < 0) {
                normalized
            } else {
                normalized.subList(currentIndex + 1, normalized.size) + normalized.subList(0, currentIndex)
            }
        }
        return ordered.map { host -> parsed.newBuilder().host(host).build().toString() }
    }

    /** 同机器换后缀。当前后缀不在 [suffixes] 里（例如 .gif）时不产生候补。 */
    private fun suffixVariants(parsed: HttpUrl, suffixes: List<String>): List<String> {
        val segments = parsed.pathSegments
        val fileName = segments.lastOrNull().orEmpty()
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex <= 0) return emptyList()
        val currentSuffix = fileName.substring(dotIndex)
        if (suffixes.none { it.equals(currentSuffix, ignoreCase = true) }) return emptyList()
        val stem = fileName.substring(0, dotIndex)
        return suffixes
            .filterNot { it.equals(currentSuffix, ignoreCase = true) }
            .map { suffix ->
                parsed.newBuilder()
                    .setPathSegment(segments.lastIndex, stem + suffix)
                    .build()
                    .toString()
            }
    }

    /** 内置表里的图片主机可带可不带 scheme，这里统一取出主机名。 */
    private fun String.imageHostOrNull(): String? {
        val raw = trim()
        if (raw.isEmpty()) return null
        val withScheme = if ("://" in raw) raw else "https://$raw"
        return withScheme.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }
    }
}
