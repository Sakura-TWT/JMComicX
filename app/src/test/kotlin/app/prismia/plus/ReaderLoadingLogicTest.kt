package app.prismia.plus

import app.prismia.plus.core.api.AlbumChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器加载算法（页面几何 / 预取窗口 / 分段解码 / 换章手势）的纯函数用例。
 *
 * 这些函数都是"量大之后才出问题"的地方，边界比主路径更值得钉住：
 * 四百页的章节、跳到中段、首末章拉动，都是靠这里的取值挡住回归。
 */
class ReaderLoadingLogicTest {
    @Test
    fun heightPermilleIsRatioTimesThousand() {
        assertEquals(1420, readerHeightPermille(1000, 1420))
        assertEquals(1420, readerHeightPermille(500, 710))
        // 宽或高缺一个就是无效测量，交给中位数兜底而不是算出个 0 高度占位。
        assertEquals(0, readerHeightPermille(0, 1420))
        assertEquals(0, readerHeightPermille(1000, 0))
    }

    @Test
    fun heightPermilleSurvivesLargeImages() {
        // 1000 * 20000 会溢出 Int，必须走 Long 再收回来。
        assertEquals(20_000, readerHeightPermille(1000, 20_000))
    }

    @Test
    fun medianIgnoresUnmeasuredPages() {
        assertEquals(1400, readerMedianPermille(listOf(0, 1400, 0)))
        assertEquals(0, readerMedianPermille(emptyList()))
        assertEquals(0, readerMedianPermille(listOf(0, 0)))
    }

    @Test
    fun medianResistsOneOversizedSpread() {
        // 一页跨页大图（8000）不该把整章的估算带偏——这正是不用均值的原因。
        val values = listOf(1400, 1410, 8000, 1420, 1405)
        assertEquals(1410, readerMedianPermille(values))
        assertTrue(readerMedianPermille(values) < values.average().toInt())
    }

    @Test
    fun pageAspectPrefersMeasuredThenEstimateThenFallback() {
        assertEquals(1.5f, readerPageAspect(1500, 1400, 1.42f), 0.0001f)
        assertEquals(1.4f, readerPageAspect(0, 1400, 1.42f), 0.0001f)
        assertEquals(1.42f, readerPageAspect(0, 0, 1.42f), 0.0001f)
    }

    @Test
    fun pageAspectClampsMalformedImages() {
        // 1×20000 的畸形图不该撑出一个几万 dp 高的 item。
        assertTrue(readerPageAspect(20_000_000, 0, 1.42f) <= 20f)
        assertTrue(readerPageAspect(1, 0, 1.42f) >= 0.05f)
    }

    @Test
    fun geometryRoundTripsThroughText() {
        val permille = listOf(1400, 0, 1410, 1405)
        assertEquals("1400,,1410,1405", encodeReaderPageGeometry(permille))
        assertEquals(permille, decodeReaderPageGeometry(encodeReaderPageGeometry(permille)))
    }

    @Test
    fun geometryDropsTrailingBlanks() {
        // 四百页只测了前三页时，落盘的不该是三百九十多个逗号。
        assertEquals("1400,1410", encodeReaderPageGeometry(listOf(1400, 1410, 0, 0, 0)))
        assertEquals("", encodeReaderPageGeometry(listOf(0, 0)))
        assertEquals("", encodeReaderPageGeometry(emptyList()))
    }

    @Test
    fun geometryDecodeToleratesGarbage() {
        assertEquals(emptyList<Int>(), decodeReaderPageGeometry(null))
        assertEquals(emptyList<Int>(), decodeReaderPageGeometry(""))
        assertEquals(listOf(1400, 0, 0, 1410), decodeReaderPageGeometry("1400,,abc,1410"))
        assertEquals(listOf(0, 1400), decodeReaderPageGeometry("-3, 1400 "))
    }

    @Test
    fun geometryIndexPutsCurrentChapterFirst() {
        val index = readerGeometryIndex(previous = "b,c", chapterId = "a", limit = 40)

        assertEquals(listOf("a", "b", "c"), index.kept)
        assertTrue(index.evicted.isEmpty())
    }

    @Test
    fun geometryIndexRefreshesRevisitedChapter() {
        val index = readerGeometryIndex(previous = "b,c,a", chapterId = "a", limit = 40)

        // 重读的那一话回到队首且不出现两次，否则上限会被同一话占掉多个名额。
        assertEquals(listOf("a", "b", "c"), index.kept)
    }

    @Test
    fun geometryIndexEvictsBeyondLimit() {
        val index = readerGeometryIndex(previous = "b,c,d", chapterId = "a", limit = 2)

        assertEquals(listOf("a", "b"), index.kept)
        assertEquals(listOf("c", "d"), index.evicted)
    }

    @Test
    fun estimatedPageBytesFollowsViewportAndAspect() {
        // 1080 宽、1.42 高宽比、ARGB_8888：约 6.6MB，正是"混淆页峰值从 14MB 降到 7MB"的那个量级。
        val bytes = readerEstimatedPageBytes(1080, 1.42f)
        assertEquals(1080L * 1533L * 4L, bytes)
        assertEquals(0L, readerEstimatedPageBytes(0, 1.42f))
        assertEquals(0L, readerEstimatedPageBytes(1080, 0f))
        assertEquals(0L, readerEstimatedPageBytes(1080, Float.NaN))
    }

    @Test
    fun prefetchDepthScalesWithMemoryBudget() {
        val pageBytes = readerEstimatedPageBytes(1080, 1.42f)

        // 48MB（无 largeHeap 时的量级）：一半预算约装得下 3 页。
        assertEquals(3, readerPrefetchDepth(48L * 1024 * 1024, pageBytes))
        // 256MB：深度顶到上限，不会无限开下去把已看过的页全挤出去。
        assertEquals(12, readerPrefetchDepth(256L * 1024 * 1024, pageBytes))
    }

    @Test
    fun prefetchDepthNeverFallsBelowTwo() {
        // 预算算不出来（拿不到 memoryCache）时退回旧行为的 2 页，而不是 0 页。
        assertEquals(2, readerPrefetchDepth(0L, 4_000_000L))
        assertEquals(2, readerPrefetchDepth(48L * 1024 * 1024, 0L))
        assertEquals(2, readerPrefetchDepth(1L, 4_000_000L))
    }

    @Test
    fun prefetchPlanOpensDeeperWhenFlippingFast() {
        val budget = 256L * 1024 * 1024
        val pageBytes = readerEstimatedPageBytes(1080, 1.42f)

        val idle = readerPrefetchPlan(budget, pageBytes, velocity = 0f)
        val fast = readerPrefetchPlan(budget, pageBytes, velocity = 8f)

        assertEquals(2, idle.ahead)
        assertTrue(fast.ahead > idle.ahead)
        // 反方向始终留至少一页：回翻头一页不必重下。
        assertTrue(idle.behind >= 1)
        assertTrue(fast.behind <= 3)
    }

    @Test
    fun prefetchPlanStaysWithinDepth() {
        val pageBytes = readerEstimatedPageBytes(1080, 1.42f)
        val plan = readerPrefetchPlan(48L * 1024 * 1024, pageBytes, velocity = 20f)

        assertEquals(readerPrefetchDepth(48L * 1024 * 1024, pageBytes), plan.ahead)
    }

    @Test
    fun prefetchPlanIgnoresGarbageVelocity() {
        val budget = 256L * 1024 * 1024
        val pageBytes = readerEstimatedPageBytes(1080, 1.42f)

        assertEquals(2, readerPrefetchPlan(budget, pageBytes, Float.NaN).ahead)
        // 方向由窗口那一步管，速度只看大小。
        assertEquals(
            readerPrefetchPlan(budget, pageBytes, 6f).ahead,
            readerPrefetchPlan(budget, pageBytes, -6f).ahead,
        )
    }

    @Test
    fun prefetchWindowStartsNearCurrentPage() {
        val window = readerPrefetchWindow(center = 200, ahead = 4, behind = 2, forward = true, pageCount = 400)

        // 当前页不进窗口（UI 自己在加载它），最近的先发，同距时顺着滚动方向优先。
        assertEquals(listOf(201, 199, 202, 198, 203, 204), window)
    }

    @Test
    fun prefetchWindowFlipsSpansWhenScrollingBackward() {
        val window = readerPrefetchWindow(center = 200, ahead = 4, behind = 2, forward = false, pageCount = 400)

        assertEquals(listOf(199, 201, 198, 202, 197, 196), window)
    }

    @Test
    fun prefetchWindowClampsToChapterBounds() {
        assertEquals(listOf(1, 2), readerPrefetchWindow(0, ahead = 2, behind = 2, forward = true, pageCount = 3))
        assertEquals(listOf(1, 0), readerPrefetchWindow(2, ahead = 2, behind = 2, forward = false, pageCount = 3))
        assertEquals(emptyList<Int>(), readerPrefetchWindow(0, 4, 2, true, pageCount = 0))
        assertEquals(emptyList<Int>(), readerPrefetchWindow(0, 0, 0, true, pageCount = 5))
    }

    @Test
    fun prefetchWindowSurvivesOutOfRangeCenter() {
        // 换章那一帧 currentPageIndex 还是上一话的页码，不能因此丢掉整个窗口。
        assertEquals(listOf(3, 2), readerPrefetchWindow(999, ahead = 2, behind = 2, forward = false, pageCount = 5))
        assertEquals(listOf(1, 2), readerPrefetchWindow(-5, ahead = 2, behind = 2, forward = true, pageCount = 5))
    }

    @Test
    fun prefetchWindowHasNoDuplicates() {
        val window = readerPrefetchWindow(5, ahead = 12, behind = 3, forward = true, pageCount = 20)

        assertEquals(window.size, window.distinct().size)
        assertFalse(window.contains(5))
    }

    @Test
    fun pageVelocitySmoothsSpikes() {
        val first = readerPageVelocity(previousVelocity = 0f, pageDelta = 1, elapsedSeconds = 0.1f)

        // 瞬时 10 页/秒，平滑后只走三成多，窗口不会被一次快翻顶到最深。
        assertTrue(first > 3f && first < 4f)
        assertTrue(readerPageVelocity(first, pageDelta = 0, elapsedSeconds = 1f) < first)
    }

    @Test
    fun pageVelocityIgnoresBadTimestamps() {
        assertEquals(4f, readerPageVelocity(4f, pageDelta = 3, elapsedSeconds = 0f), 0.0001f)
        assertEquals(4f, readerPageVelocity(4f, pageDelta = 3, elapsedSeconds = -1f), 0.0001f)
        assertEquals(4f, readerPageVelocity(4f, pageDelta = 3, elapsedSeconds = Float.NaN), 0.0001f)
    }

    @Test
    fun pageVelocityIsBoundedAndDirectionless() {
        val spike = readerPageVelocity(20f, pageDelta = 400, elapsedSeconds = 0.016f)

        assertTrue(spike <= 20f)
        assertEquals(
            readerPageVelocity(0f, pageDelta = 3, elapsedSeconds = 0.2f),
            readerPageVelocity(0f, pageDelta = -3, elapsedSeconds = 0.2f),
            0.0001f,
        )
    }

    @Test
    fun segmentRectsCoverTargetWithoutGapOrOverlap() {
        val rects = readerSegmentRects(sourceHeight = 1077, segmentCount = 10)

        assertEquals(10, rects.size)
        assertEquals(0, rects.first().targetTop)
        assertEquals(1077, rects.last().targetBottom)
        rects.zipWithNext { previous, next -> assertEquals(previous.targetBottom, next.targetTop) }
    }

    @Test
    fun segmentRectsKeepSourceResolution() {
        // 每段必须原样搬运：只要在段内做缩放，接缝两侧的重采样相位就会错开，
        // 页面上会出现一条条等距的横线（2.6.0 开发中真机复现过）。
        val rects = readerSegmentRects(sourceHeight = 1077, segmentCount = 10)

        rects.forEach { rect ->
            assertEquals(rect.sourceBottom - rect.sourceTop, rect.targetBottom - rect.targetTop)
        }
        assertEquals(1077, rects.sumOf { it.targetBottom - it.targetTop })
    }

    @Test
    fun segmentRectsReadEverySourceRowExactlyOnce() {
        val rects = readerSegmentRects(sourceHeight = 1077, segmentCount = 10)
        val covered = rects.flatMap { it.sourceTop until it.sourceBottom }

        assertEquals(1077, covered.size)
        assertEquals(1077, covered.distinct().size)
        assertEquals(0, covered.min())
        assertEquals(1076, covered.max())
    }

    @Test
    fun segmentRectsRejectDegenerateInput() {
        assertTrue(readerSegmentRects(sourceHeight = 0, segmentCount = 10).isEmpty())
        // 单段图不需要还原，交给整图路径。
        assertTrue(readerSegmentRects(sourceHeight = 1077, segmentCount = 1).isEmpty())
        assertTrue(readerSegmentRects(sourceHeight = -8, segmentCount = 10).isEmpty())
    }

    @Test
    fun sampleSizeStaysPowerOfTwoAboveTarget() {
        // 采样后仍要 ≥ 视口宽，否则放大回去就是糊的。
        assertEquals(1, readerSampleSize(1080, 1080))
        assertEquals(2, readerSampleSize(2160, 1080))
        assertEquals(4, readerSampleSize(4320, 1080))
        assertEquals(2, readerSampleSize(2400, 1080))
        assertEquals(1, readerSampleSize(1000, 1080))
    }

    @Test
    fun sampleSizeGuardsAgainstZero() {
        assertEquals(1, readerSampleSize(0, 1080))
        assertEquals(1, readerSampleSize(1080, 0))
    }

    @Test
    fun scrambledSampleSizeOnlyDownsamplesOnCleanSegmentBoundaries() {
        // 降采样是把相邻若干行并成一行。分段边界落在这样一组行中间，合出来的那行就混进
        // 邻段的像素，还原后又是一条横线——所以只有段高与余数都能整除时才敢降。
        // 2160×1500、10 段：段高 150 能被 2 整除，余数 0。
        assertEquals(2, readerScrambledSampleSize(2160, 1500, 1080, 10))
        // 2160×1505：余数 5 除不尽 2，只能按原分辨率解。
        assertEquals(1, readerScrambledSampleSize(2160, 1505, 1080, 10))
        // 4320×3000：段高 300 能被 4 整除，一次降到 4 倍。
        assertEquals(4, readerScrambledSampleSize(4320, 3000, 1080, 10))
        // 4320×1500：段高 150 被 4 除不尽，退到 2 而不是直接放弃。
        assertEquals(2, readerScrambledSampleSize(4320, 1500, 1080, 10))
    }

    @Test
    fun scrambledSampleSizeKeepsNativeResolutionForOrdinaryPages() {
        // JM 的页宽多在 1000–1300、视口 1080：本来就是 1 倍这一档。
        assertEquals(1, readerScrambledSampleSize(1130, 1500, 1080, 10))
        assertEquals(1, readerScrambledSampleSize(1000, 1477, 1080, 10))
        // 单段页不用还原，没有接缝要保，按普通图片的规则降就行。
        assertEquals(2, readerScrambledSampleSize(2160, 1505, 1080, 1))
        // 量不到高度时不冒险。
        assertEquals(1, readerScrambledSampleSize(2160, 0, 1080, 10))
    }

    @Test
    fun flipStateArmsExactlyAtThreshold() {
        val threshold = 96f

        assertFalse(continuousFlipState(95f, threshold, canFlip = true).armed)
        assertTrue(continuousFlipState(96f, threshold, canFlip = true).armed)
        assertEquals(0.5f, continuousFlipState(48f, threshold, true).progress, 0.0001f)
        // 越过阈值后进度不再涨，提示条不会溢出。
        assertEquals(1f, continuousFlipState(500f, threshold, true).progress, 0.0001f)
    }

    @Test
    fun flipStateStaysIdleOnFirstOrLastChapter() {
        // 首章上拉、末章下拉只出提示：不累加、不震动、不换章。
        val state = continuousFlipState(500f, 96f, canFlip = false)

        assertEquals(0f, state.progress, 0.0001f)
        assertFalse(state.armed)
    }

    @Test
    fun flipStateIgnoresGarbageOverscroll() {
        assertFalse(continuousFlipState(0f, 96f, true).armed)
        assertFalse(continuousFlipState(-40f, 96f, true).armed)
        assertFalse(continuousFlipState(Float.NaN, 96f, true).armed)
        assertFalse(continuousFlipState(120f, 0f, true).armed)
    }

    @Test
    fun flipOverscrollAccumulatesWithResistance() {
        // 阻尼是提示卡有时间被看清的前提：手指走的距离要明显大于阈值。
        var overscroll = 0f
        repeat(4) { overscroll = readerFlipOverscroll(overscroll, -60f, 0.55f) }

        assertEquals(132f, overscroll, 0.0001f)
        // 方向由调用方判定，这里只累加位移大小，上拉下拉共用一套。
        assertEquals(33f, readerFlipOverscroll(0f, 60f, 0.55f), 0.0001f)
    }

    @Test
    fun flipOverscrollKeepsGarbageFromLeakingIn() {
        // 起点是 NaN 或负数就从 0 重新起算，这一段位移本身照常记。
        assertEquals(33f, readerFlipOverscroll(Float.NaN, -60f, 0.55f), 0.0001f)
        assertEquals(33f, readerFlipOverscroll(-10f, -60f, 0.55f), 0.0001f)
        // 位移或阻尼不可用时原样返回已累计的值，绝不能把 NaN 传下去——
        // 那会让 continuousFlipState 的进度也变成 NaN，提示卡的轨道直接画不出来。
        assertEquals(0f, readerFlipOverscroll(-10f, Float.NaN, 0.55f), 0.0001f)
        assertEquals(10f, readerFlipOverscroll(10f, Float.NaN, 0.55f), 0.0001f)
        assertEquals(10f, readerFlipOverscroll(10f, -60f, 0f), 0.0001f)
        assertEquals(10f, readerFlipOverscroll(10f, -60f, Float.NaN), 0.0001f)
    }

    @Test
    fun flipRewindUnwindsWhatWasPulled() {
        // 拉出 132 之后反向走 60：按同一阻尼折算，退回 33，剩 99。
        val rewind = readerFlipRewind(132f, 60f, 0.55f)

        assertEquals(99f, rewind.overscroll, 0.0001f)
        // 这一段手指位移整段归提示卡，不能再传给列表——传下去列表就会先滚起来，
        // 用户会看到"进度条不动、画面却在退"。
        assertEquals(60f, rewind.consumed, 0.0001f)
    }

    @Test
    fun flipRewindOnlyEatsWhatItNeeds() {
        // 只剩 11 时来一段 60 的反向位移：折算后只需要 20，多出来的 40 得还给列表，
        // 否则退到 0 的那一帧列表会僵住一下。
        val rewind = readerFlipRewind(11f, 60f, 0.55f)

        assertEquals(0f, rewind.overscroll, 0.0001f)
        assertEquals(20f, rewind.consumed, 0.0001f)
    }

    @Test
    fun flipRewindKeepsTheSignOfTheDrag() {
        // 章首下拉过阈值后再往上拉：位移是负的，吃掉的也必须是负的，
        // 符号弄反会让列表朝反方向跳一下。
        val rewind = readerFlipRewind(132f, -60f, 0.55f)

        assertEquals(99f, rewind.overscroll, 0.0001f)
        assertEquals(-60f, rewind.consumed, 0.0001f)
    }

    @Test
    fun flipRewindIsTheInverseOfAccumulation() {
        // 拉出去和退回来共用一个阻尼，手感才对称：拉四下、退四下应当正好回到 0。
        // 不对称的话"拉过阈值再退回来"会退不干净，松手照样换章。
        var overscroll = 0f
        repeat(4) { overscroll = readerFlipOverscroll(overscroll, -60f, 0.55f) }
        repeat(4) { overscroll = readerFlipRewind(overscroll, 60f, 0.55f).overscroll }

        assertEquals(0f, overscroll, 0.0001f)
    }

    @Test
    fun flipRewindRefusesGarbage() {
        assertEquals(0f, readerFlipRewind(Float.NaN, 60f, 0.55f).overscroll, 0.0001f)
        assertEquals(0f, readerFlipRewind(-10f, 60f, 0.55f).overscroll, 0.0001f)
        // 位移或阻尼不可用时原样返回，且一点位移都不吃：凭空吞掉列表的滚动比不退更糟。
        assertEquals(132f, readerFlipRewind(132f, Float.NaN, 0.55f).overscroll, 0.0001f)
        assertEquals(0f, readerFlipRewind(132f, Float.NaN, 0.55f).consumed, 0.0001f)
        assertEquals(132f, readerFlipRewind(132f, 60f, 0f).overscroll, 0.0001f)
        assertEquals(0f, readerFlipRewind(132f, 60f, 0f).consumed, 0.0001f)
    }

    @Test
    fun flipCancellationRequiresOnlyAReasonableReverseTravel() {
        val threshold = 132f
        assertFalse(readerFlipShouldCancelAfterReverse(0.49f, threshold))
        assertTrue(readerFlipShouldCancelAfterReverse(0.5f, threshold))
    }

    @Test
    fun flipCancellationFailsClosedForInvalidGeometry() {
        assertFalse(readerFlipShouldCancelAfterReverse(Float.NaN, 132f))
        assertFalse(readerFlipShouldCancelAfterReverse(20f, Float.NaN))
        assertFalse(readerFlipShouldCancelAfterReverse(20f, 0f))
    }

    @Test
    fun flipTargetNamesTheChapterItWillOpen() {
        // 只走 sort 与序号这两条分支：话名那条要过 Html.fromHtml（见 displayName），
        // 而纯 JVM 单测里的 android.jar 是桩，一调就抛 Stub!。话名本身由目录页负责。
        val chapters = listOf(
            AlbumChapter(id = "1", name = null, sort = null),
            AlbumChapter(id = "2", name = null, sort = "  2  "),
        )
        val counts = mapOf("2" to 24, "1" to 0)

        val target = readerFlipTarget(chapters, 1, counts)

        assertEquals("第 2 话", target?.label)
        assertEquals(24, target?.pageCount)
        // 没预热到、或预热出来是 0 页的，一律当未知：卡片宁可只写话名。
        assertEquals("第 1 话", readerFlipTarget(chapters, 0, counts)?.label)
        assertEquals(null, readerFlipTarget(chapters, 0, counts)?.pageCount)
        // 越界就是没有下一话/上一话，提示卡据此改写文案。
        assertEquals(null, readerFlipTarget(chapters, 2, counts))
        assertEquals(null, readerFlipTarget(chapters, -1, counts))
    }

    @Test
    fun flipCardSummaryOnlyPromisesWhatItKnows() {
        assertEquals(
            "第 3 话 · 全 24 页",
            readerFlipCardSummary(ReaderFlipTarget("第 3 话", 24)),
        )
        // 相邻章还没预热出来时页数是未知的，宁可不写也不能写 0 页。
        assertEquals("第 3 话", readerFlipCardSummary(ReaderFlipTarget("第 3 话", null)))
        assertEquals(null, readerFlipCardSummary(ReaderFlipTarget("   ", 24)))
        assertEquals(null, readerFlipCardSummary(null))
    }
}
