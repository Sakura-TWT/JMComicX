package app.prismia.plus.core.download

import app.prismia.plus.core.result.JmxError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHostFailoverTest {
    private val hosts = listOf(
        "https://cdn-a.example.com",
        "https://cdn-b.example.com",
        "https://cdn-c.example.com"
    )

    /** 从当前机器之后的一台开始轮，不是每次都从表头开始——否则重试压力全砸在第一台上。 */
    @Test
    fun serverErrorRotatesToTheHostAfterTheCurrentOne() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-b.example.com/media/photos/500/00001.jpg",
            error = JmxError.Http(code = 503, message = "boom"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertEquals("https://cdn-c.example.com/media/photos/500/00001.jpg", next)
    }

    @Test
    fun rotationWrapsAroundToTheHeadOfTheList() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-c.example.com/media/photos/500/00001.jpg",
            error = JmxError.Network("connect timeout"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertEquals("https://cdn-a.example.com/media/photos/500/00001.jpg", next)
    }

    /**
     * 调用方按健康度排过序时不能再轮转：轮转会从"当前机器之后"开始，
     * 把排在最前的那台（也就是最健康的）挤到最后，健康度排序等于白排。
     */
    @Test
    fun preservesGivenOrderWhenAsked() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-b.example.com/media/photos/500/00001.jpg",
            error = JmxError.Http(code = 503, message = "boom"),
            triedUrls = emptySet(),
            hosts = listOf("https://cdn-c.example.com", "https://cdn-a.example.com"),
            preserveHostOrder = true
        )

        assertEquals("https://cdn-c.example.com/media/photos/500/00001.jpg", next)
    }

    @Test
    fun preservedOrderStillSkipsTheCurrentHost() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-a.example.com/media/photos/500/00001.jpg",
            error = JmxError.Network("connect timeout"),
            triedUrls = emptySet(),
            hosts = listOf("https://cdn-a.example.com", "https://cdn-b.example.com"),
            preserveHostOrder = true
        )

        assertEquals("https://cdn-b.example.com/media/photos/500/00001.jpg", next)
    }

    /** 只有"机器不行"这一类错误才该记到线路表上，后缀猜错与业务错误不算。 */
    @Test
    fun onlyHostAxisErrorsCountAgainstAHost() {
        assertTrue(ImageHostFailover.indicatesHostFailure(JmxError.Network("timeout")))
        assertTrue(ImageHostFailover.indicatesHostFailure(JmxError.Http(code = 503, message = "boom")))
        assertTrue(ImageHostFailover.indicatesHostFailure(JmxError.Http(code = 403, message = "blocked")))
        assertFalse(ImageHostFailover.indicatesHostFailure(JmxError.Http(code = 404, message = "missing")))
        assertFalse(ImageHostFailover.indicatesHostFailure(JmxError.Http(code = 410, message = "gone")))
        assertFalse(ImageHostFailover.indicatesHostFailure(JmxError.Decode("bad bytes")))
    }

    /** 404 是"这个后缀不存在"，各 CDN 是同一份存储，换机器一样 404，只能换后缀。 */
    @Test
    fun notFoundSwitchesSuffixAndKeepsTheHost() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-b.example.com/media/photos/500/00001.jpg",
            error = JmxError.Http(code = 404, message = "not found"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertEquals("https://cdn-b.example.com/media/photos/500/00001.webp", next)
    }

    /**
     * .gif 决定了"不需要还原分段"（见 ImageScramble.isGif），
     * 换到 .gif 或从 .gif 换出去都会让上层已算好的还原决策失效，因此两个方向都不做。
     */
    @Test
    fun gifIsNeverPartOfSuffixFailover() {
        val fromGif = ImageHostFailover.next(
            currentUrl = "https://cdn-b.example.com/media/photos/500/00001.gif",
            error = JmxError.Http(code = 404, message = "not found"),
            triedUrls = emptySet(),
            hosts = hosts
        )
        assertNull(fromGif)

        val toGif = ImageHostFailover.next(
            currentUrl = "https://cdn-b.example.com/media/photos/500/00001.jpg",
            error = JmxError.Http(code = 404, message = "not found"),
            triedUrls = setOf(
                "https://cdn-b.example.com/media/photos/500/00001.jpg",
                "https://cdn-b.example.com/media/photos/500/00001.webp",
                "https://cdn-b.example.com/media/photos/500/00001.png"
            ),
            hosts = hosts
        )
        assertNull(toGif)
    }

    @Test
    fun alreadyTriedCandidatesAreSkipped() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-a.example.com/media/photos/500/00001.jpg",
            error = JmxError.Http(code = 500, message = "boom"),
            triedUrls = setOf("https://cdn-b.example.com/media/photos/500/00001.jpg"),
            hosts = hosts
        )

        assertEquals("https://cdn-c.example.com/media/photos/500/00001.jpg", next)
    }

    /** 下载器是通用的：非 /media/ 的地址一律不改写，不该替调用方猜候补。 */
    @Test
    fun nonMediaPathsAreNeverRewritten() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-a.example.com/setting?lang=CN",
            error = JmxError.Network("connect timeout"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertNull(next)
    }

    /** 业务错误换地址救不回来，不该白等一个超时。 */
    @Test
    fun nonRetryableErrorsProduceNoCandidate() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-a.example.com/media/photos/500/00001.jpg",
            error = JmxError.Api(code = 401, message = "unauthorized"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertNull(next)
    }

    /** setting 下发的图片主机不在内置表里时，仍应能整表试一遍。 */
    @Test
    fun unknownCurrentHostFallsBackToTheWholeList() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-from-setting.example.net/media/photos/500/00001.jpg",
            error = JmxError.Http(code = 502, message = "bad gateway"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertEquals("https://cdn-a.example.com/media/photos/500/00001.jpg", next)
    }

    /** 响应类型不符说明这台机器塞了错误页而不是图，换机器。 */
    @Test
    fun contentTypeMismatchFailsOverByHost() {
        val next = ImageHostFailover.next(
            currentUrl = "https://cdn-a.example.com/media/photos/500/00001.jpg",
            error = JmxError.Schema("下载响应类型不匹配：text/html", field = "content-type"),
            triedUrls = emptySet(),
            hosts = hosts
        )

        assertEquals("https://cdn-b.example.com/media/photos/500/00001.jpg", next)
    }
}
