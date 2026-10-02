package app.prismia.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组顺序（手动序列 / 自动排列热度 / 拖拽落位 / 本机键的编解码）的纯函数用例。
 *
 * 顺序偏好是本机设置，不进导出格式，所以这里的断言就是这套行为的唯一保障。
 */
class BookshelfGroupOrderTest {
    private fun group(id: String, createdAt: Long) = BookshelfGroup(
        id = id,
        name = "分组 $id",
        matchFavoritesByTags = false,
        tagRules = emptyList(),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun scoreHalvesEveryHalfLife() {
        val usage = BookshelfGroupUsage(id = "a", score = 8.0, updatedAt = now - 7 * day)

        assertEquals(4.0, decayedGroupScore(usage, now), 0.0001)
        assertEquals(1.0, decayedGroupScore(usage.copy(updatedAt = now - 21 * day), now), 0.0001)
        assertEquals(8.0, decayedGroupScore(usage.copy(updatedAt = now), now), 0.0001)
    }

    @Test
    fun scoreTreatsMissingOrBackwardsClockSafely() {
        assertEquals(0.0, decayedGroupScore(null, now), 0.0001)
        assertEquals(0.0, decayedGroupScore(BookshelfGroupUsage("a", 0.0, now), now), 0.0001)
        // 时钟回拨时负时差按 0 算，否则分数会被 2 的正次幂放大。
        assertEquals(5.0, decayedGroupScore(BookshelfGroupUsage("a", 5.0, now + 30 * day), now), 0.0001)
    }

    @Test
    fun repeatedVisitsBeatOneOldBurst() {
        // 一个月前连点十次 vs 今天点两次：热度算法要让"最近在看的"靠前。
        val stale = BookshelfGroupUsage(id = "stale", score = 10.0, updatedAt = now - 30 * day)
        val fresh = BookshelfGroupUsage(id = "fresh", score = 2.0, updatedAt = now)

        assertTrue(decayedGroupScore(fresh, now) > decayedGroupScore(stale, now))
    }

    @Test
    fun visitAccumulatesInsteadOfRestarting() {
        // recordGroupVisit 的算式：先折算到当次访问时刻再加一。连着看就会越滚越高。
        var usage = BookshelfGroupUsage(id = "a", score = 0.0, updatedAt = 0L)
        repeat(4) { step ->
            val at = now + step * 1000L
            usage = usage.copy(score = decayedGroupScore(usage, at) + 1.0, updatedAt = at)
        }

        assertEquals(4.0, usage.score, 0.001)
    }

    @Test
    fun manualOrderFollowsStoredIndexes() {
        val groups = listOf(group("a", 1), group("b", 2), group("c", 3))

        val ordered = orderBookshelfGroups(
            groups = groups,
            order = listOf("c", "a", "b"),
            autoOrder = false,
            usage = emptyList(),
            now = now,
        )

        assertEquals(listOf("c", "a", "b"), ordered.map(BookshelfGroup::id))
    }

    @Test
    fun unregisteredGroupsGoToTheEndByCreatedAt() {
        val groups = listOf(group("new2", 30), group("a", 10), group("new1", 20))

        val ordered = orderBookshelfGroups(
            groups = groups,
            order = listOf("a"),
            autoOrder = false,
            usage = emptyList(),
            now = now,
        )

        // 新建的分组总出现在最右边，且彼此按创建时间先后。
        assertEquals(listOf("a", "new1", "new2"), ordered.map(BookshelfGroup::id))
    }

    @Test
    fun autoOrderSortsByDecayedScore() {
        val groups = listOf(group("a", 1), group("b", 2), group("c", 3))
        val usage = listOf(
            BookshelfGroupUsage(id = "a", score = 3.0, updatedAt = now - 21 * day),
            BookshelfGroupUsage(id = "b", score = 2.0, updatedAt = now),
            BookshelfGroupUsage(id = "c", score = 1.0, updatedAt = now - 7 * day),
        )

        val ordered = orderBookshelfGroups(groups, listOf("a", "b", "c"), autoOrder = true, usage, now)

        // b=2.0、c=0.5、a=0.375
        assertEquals(listOf("b", "c", "a"), ordered.map(BookshelfGroup::id))
    }

    @Test
    fun autoOrderFallsBackToManualOrderOnTies() {
        val groups = listOf(group("a", 1), group("b", 2), group("c", 3))

        val ordered = orderBookshelfGroups(groups, listOf("c", "b", "a"), autoOrder = true, emptyList(), now)

        // 从未访问过的分组分数都是 0，此时必须给出确定顺序，否则 tab 栏会在两次进入之间莫名换位。
        assertEquals(listOf("c", "b", "a"), ordered.map(BookshelfGroup::id))
    }

    @Test
    fun autoOrderKeepsManualOrderIntact() {
        val groups = listOf(group("a", 1), group("b", 2), group("c", 3))
        val manual = listOf("c", "b", "a")
        val usage = listOf(BookshelfGroupUsage(id = "a", score = 9.0, updatedAt = now))

        val auto = orderBookshelfGroups(groups, manual, autoOrder = true, usage, now)
        val backToManual = orderBookshelfGroups(groups, manual, autoOrder = false, usage, now)

        assertEquals(listOf("a", "c", "b"), auto.map(BookshelfGroup::id))
        // 关掉开关就原样回来：自动排列只覆盖显示顺序。
        assertEquals(manual, backToManual.map(BookshelfGroup::id))
    }

    @Test
    fun orderingIsStableForTrivialInput() {
        assertEquals(emptyList<BookshelfGroup>(), orderBookshelfGroups(emptyList(), listOf("a"), true, emptyList(), now))
        val single = listOf(group("a", 1))
        assertEquals(single, orderBookshelfGroups(single, emptyList(), true, emptyList(), now))
    }

    @Test
    fun orderingHandlesStaleOrderAndUsageEntries() {
        val groups = listOf(group("a", 1), group("b", 2))
        val order = listOf("deleted", "b", "a")
        val usage = listOf(BookshelfGroupUsage(id = "deleted", score = 99.0, updatedAt = now))

        // 已删除的分组留在 order/usage 里不该影响活着的那些。
        assertEquals(listOf("b", "a"), orderBookshelfGroups(groups, order, false, usage, now).map(BookshelfGroup::id))
        assertEquals(listOf("b", "a"), orderBookshelfGroups(groups, order, true, usage, now).map(BookshelfGroup::id))
    }

    @Test
    fun moveShiftsInsteadOfSwapping() {
        val order = listOf("a", "b", "c", "d")

        assertEquals(listOf("b", "c", "a", "d"), moveGroupOrder(order, from = 0, to = 2))
        assertEquals(listOf("a", "d", "b", "c"), moveGroupOrder(order, from = 3, to = 1))
    }

    @Test
    fun moveReturnsInputOnNoOpOrOutOfRange() {
        val order = listOf("a", "b", "c")

        assertEquals(order, moveGroupOrder(order, from = 1, to = 1))
        assertEquals(order, moveGroupOrder(order, from = -1, to = 1))
        assertEquals(order, moveGroupOrder(order, from = 1, to = 3))
        assertEquals(emptyList<String>(), moveGroupOrder(emptyList(), from = 0, to = 0))
    }

    @Test
    fun movePreservesEveryId() {
        val order = (1..41).map { "g$it" }

        // 41 个分组正是 issue #10 里过不去的那道上限，顺序调整不能丢任何一个。
        val moved = moveGroupOrder(order, from = 40, to = 0)

        assertEquals(order.size, moved.size)
        assertEquals(order.toSet(), moved.toSet())
        assertEquals("g41", moved.first())
    }

    @Test
    fun orderRoundTripsThroughStorage() {
        val order = listOf("a", "b", "c")

        assertEquals(order, decodeBookshelfGroupOrder(encodeBookshelfGroupOrder(order)))
        assertEquals(emptyList<String>(), decodeBookshelfGroupOrder(null))
        assertEquals(emptyList<String>(), decodeBookshelfGroupOrder("{不是数组}"))
        assertEquals(emptyList<String>(), decodeBookshelfGroupOrder(encodeBookshelfGroupOrder(emptyList())))
    }

    @Test
    fun orderDecodeDropsBlanksAndDuplicates() {
        assertEquals(listOf("a", "b"), decodeBookshelfGroupOrder("""["a"," ","b","a"]"""))
    }

    @Test
    fun usageRoundTripsThroughStorage() {
        val usage = listOf(
            BookshelfGroupUsage(id = "a", score = 3.5, updatedAt = now),
            BookshelfGroupUsage(id = "b", score = 0.25, updatedAt = now - day),
        )

        val decoded = decodeBookshelfGroupUsage(encodeBookshelfGroupUsage(usage))

        assertEquals(usage.map(BookshelfGroupUsage::id), decoded.map(BookshelfGroupUsage::id))
        assertEquals(3.5, decoded[0].score, 0.0001)
        assertEquals(now, decoded[0].updatedAt)
        assertEquals(0.25, decoded[1].score, 0.0001)
    }

    @Test
    fun usageDecodeSkipsUnusableEntries() {
        val raw = """[{"score":1.0},{"id":"a","score":0},{"id":"b","score":2.0},{"id":"b","score":9.0}]"""

        val decoded = decodeBookshelfGroupUsage(raw)

        // 无 id、零分（等于没访问过）、重复 id 都不该进来。
        assertEquals(listOf("b"), decoded.map(BookshelfGroupUsage::id))
        assertEquals(2.0, decoded.single().score, 0.0001)
        assertEquals(0L, decoded.single().updatedAt)
    }

    @Test
    fun usageDecodeToleratesGarbage() {
        assertEquals(emptyList<BookshelfGroupUsage>(), decodeBookshelfGroupUsage(null))
        assertEquals(emptyList<BookshelfGroupUsage>(), decodeBookshelfGroupUsage("не json"))
        assertEquals(emptyList<BookshelfGroupUsage>(), decodeBookshelfGroupUsage(""))
    }

    @Test
    fun dragTargetSwapsAtHalfARow() {
        val step = 72f

        // 行高一致，落位就是一道除法：过半行才算换格，否则边界上会来回抖。
        assertEquals(3, groupDragTargetIndex(fromIndex = 3, dragPx = 35f, rowStepPx = step, itemCount = 8))
        assertEquals(4, groupDragTargetIndex(fromIndex = 3, dragPx = 36f, rowStepPx = step, itemCount = 8))
        assertEquals(1, groupDragTargetIndex(fromIndex = 3, dragPx = -150f, rowStepPx = step, itemCount = 8))
        assertEquals(3, groupDragTargetIndex(fromIndex = 3, dragPx = 0f, rowStepPx = step, itemCount = 8))
    }

    @Test
    fun dragTargetClampsToTheList() {
        val step = 72f

        // 往上拖过头不能顶掉锁定的「全部」，往下拖过头也只能停在最后一行。
        assertEquals(0, groupDragTargetIndex(fromIndex = 2, dragPx = -9000f, rowStepPx = step, itemCount = 8))
        assertEquals(7, groupDragTargetIndex(fromIndex = 2, dragPx = 9000f, rowStepPx = step, itemCount = 8))
    }

    @Test
    fun dragTargetRefusesDegenerateInput() {
        assertEquals(3, groupDragTargetIndex(3, dragPx = 500f, rowStepPx = 0f, itemCount = 8))
        assertEquals(3, groupDragTargetIndex(3, dragPx = Float.NaN, rowStepPx = 72f, itemCount = 8))
        assertEquals(3, groupDragTargetIndex(3, dragPx = 500f, rowStepPx = Float.NaN, itemCount = 8))
        assertEquals(-1, groupDragTargetIndex(-1, dragPx = 500f, rowStepPx = 72f, itemCount = 8))
        assertEquals(9, groupDragTargetIndex(9, dragPx = 500f, rowStepPx = 72f, itemCount = 8))
        assertEquals(0, groupDragTargetIndex(0, dragPx = 500f, rowStepPx = 72f, itemCount = 0))
    }

    @Test
    fun autoScrollOnlyFiresNearTheEdges() {
        val step = 24f

        assertEquals(-step, groupOrderAutoScrollVelocity(30f, 0f, 1800f, 72f, step), 0.0001f)
        assertEquals(step, groupOrderAutoScrollVelocity(1780f, 0f, 1800f, 72f, step), 0.0001f)
        assertEquals(0f, groupOrderAutoScrollVelocity(900f, 0f, 1800f, 72f, step), 0.0001f)
        // 边界正好落在阈值上时不滚：滚动只在越过之后开始。
        assertEquals(0f, groupOrderAutoScrollVelocity(72f, 0f, 1800f, 72f, step), 0.0001f)
    }

    @Test
    fun autoScrollStaysOffInDegenerateViewports() {
        // 视口比两条边缘区还矮时，列表中间也算"边缘"，一按手柄就会自己跑。
        assertEquals(0f, groupOrderAutoScrollVelocity(60f, 0f, 120f, 72f, 24f), 0.0001f)
        assertEquals(0f, groupOrderAutoScrollVelocity(Float.NaN, 0f, 1800f, 72f, 24f), 0.0001f)
        assertEquals(0f, groupOrderAutoScrollVelocity(30f, 0f, 1800f, 0f, 24f), 0.0001f)
        assertEquals(0f, groupOrderAutoScrollVelocity(30f, 0f, 1800f, 72f, 0f), 0.0001f)
    }

    @Test
    fun rowSubtitleOnlyShowsWhatItHas() {
        assertEquals("12 部", groupOrderRowSubtitle(12, null))
        assertEquals("12 部 · 固定在第一位", groupOrderRowSubtitle(12, "固定在第一位"))
        // 数量还在后台算，这一格先空着，别先写个 0 部。
        assertEquals("固定在第一位", groupOrderRowSubtitle(null, "固定在第一位"))
        assertEquals(null, groupOrderRowSubtitle(null, null))
    }

    @Test
    fun searchPrefersTheExactNameOverALongerOne() {
        val names = listOf("全部", "日常向漫画", "日常", "热门")

        // 完全同名的那个哪怕排在后面也要赢：输"日常"要的显然不是「日常向漫画」。
        assertEquals(2, findGroupOrderMatch("日常", names))
    }

    @Test
    fun searchFallsBackFromPrefixToContains() {
        val names = listOf("全部", "同人志合集", "日系本子")

        // 没有同名的，先看谁从这两个字开头。
        assertEquals(1, findGroupOrderMatch("同人", names))
        // 连开头都没有，才退到"中间含有"。
        assertEquals(1, findGroupOrderMatch("合集", names))
    }

    @Test
    fun searchIgnoresCaseAndSurroundingSpaces() {
        val names = listOf("全部", " Doujin ", "CG 集")

        assertEquals(1, findGroupOrderMatch("doujin", names))
        assertEquals(1, findGroupOrderMatch("  DOUJIN  ", names))
        assertEquals(2, findGroupOrderMatch("cg", names))
    }

    @Test
    fun searchReturnsMinusOneWhenNothingMatches() {
        val names = listOf("全部", "日常")

        assertEquals(-1, findGroupOrderMatch("不存在的分组", names))
        // 空词不算"没找到"也不算找到某一行，一律 -1，调用方据此什么都不做。
        assertEquals(-1, findGroupOrderMatch("", names))
        assertEquals(-1, findGroupOrderMatch("   ", names))
        assertEquals(-1, findGroupOrderMatch("日常", emptyList()))
    }

    @Test
    fun searchIndexLinesUpWithTheListRow() {
        // 0 号永远是锁定的「全部」，它在列表里也正好是第 0 项，
        // 所以返回值可以直接喂给 animateScrollToItem，不必再换算。
        val names = listOf("全部") + listOf(group("a", 1), group("b", 2)).map(BookshelfGroup::name)

        assertEquals(0, findGroupOrderMatch("全部", names))
        assertEquals(1, findGroupOrderMatch("分组 a", names))
        assertEquals(2, findGroupOrderMatch("分组 b", names))
    }
}
