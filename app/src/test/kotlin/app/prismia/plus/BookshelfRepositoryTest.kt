package app.prismia.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookshelfRepositoryTest {
    private val firstAlbum = HomeAlbum(
        id = "1",
        name = "第一本",
        author = "作者甲",
        coverUrl = "https://img.test/1",
        imageHost = "https://img.test",
    )
    private val secondAlbum = firstAlbum.copy(id = "2", name = "第二本")

    /** 分组的匹配规则与导入导出无关，测试里只关心 id 与名称。 */
    private fun group(id: String, name: String, createdAt: Long) = BookshelfGroup(
        id = id,
        name = name,
        matchFavoritesByTags = false,
        tagRules = emptyList(),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun addingSameAlbumKeepsProgressAndDoesNotDuplicate() {
        val (initial, added) = addToBookshelf(emptyList(), firstAlbum, addedAt = 10L)
        val progressed = updateBookshelfProgress(
            entries = initial,
            albumId = "1",
            chapterId = "11",
            chapterName = "第一话",
            pageIndex = 4,
            pageCount = 20,
            readAt = 30L,
        )
        val (updated, addedAgain) = addToBookshelf(progressed, firstAlbum.copy(name = "更新标题"), 50L)

        assertTrue(added)
        assertFalse(addedAgain)
        assertEquals(1, updated.size)
        assertEquals("更新标题", updated.single().name)
        assertEquals(30L, updated.single().lastReadAt)
        assertEquals(4, updated.single().lastPageIndex)
    }

    @Test
    fun recentReadSortPutsUnreadEntriesAfterReadEntries() {
        val first = addToBookshelf(emptyList(), firstAlbum, 10L).first
        val second = addToBookshelf(first, secondAlbum, 20L).first
        val readFirst = updateBookshelfProgress(
            entries = second,
            albumId = "1",
            chapterId = "11",
            chapterName = "第一话",
            pageIndex = 1,
            pageCount = 5,
            readAt = 100L,
        )

        assertEquals(listOf("1", "2"), sortBookshelf(readFirst, BookshelfSortOrder.RECENTLY_READ).map { it.albumId })
        assertEquals(listOf("2", "1"), sortBookshelf(readFirst, BookshelfSortOrder.UPDATED).map { it.albumId })
    }

    @Test
    fun progressForUnknownAlbumDoesNotCreateShelfEntry() {
        val entries = updateBookshelfProgress(
            entries = emptyList(),
            albumId = "missing",
            chapterId = "1",
            chapterName = "第一话",
            pageIndex = 0,
            pageCount = 5,
            readAt = 10L,
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun addingExistingAlbumMergesGroupsWithoutLosingReadingProgress() {
        val first = addToBookshelf(
            entries = emptyList(),
            album = firstAlbum,
            addedAt = 10L,
            groupIds = setOf("group-a"),
        ).first
        val progressed = updateBookshelfProgress(
            entries = first,
            albumId = "1",
            chapterId = "11",
            chapterName = "第一话",
            pageIndex = 8,
            pageCount = 20,
            readAt = 30L,
        )

        val updated = addToBookshelf(progressed, firstAlbum, 50L, setOf("group-b")).first.single()

        assertEquals(setOf("group-a", "group-b"), updated.groupIds)
        assertEquals("11", updated.lastChapterId)
        assertEquals(8, updated.lastPageIndex)
        assertEquals(50L, updated.updatedAt)
    }

    @Test
    fun nameSortIsStableAndIndependentFromReadingTime() {
        val entries = listOf(
            BookshelfEntry("2", "Beta", "", "", "", 20L, lastReadAt = 100L),
            BookshelfEntry("1", "alpha", "", "", "", 10L),
        )

        assertEquals(listOf("1", "2"), sortBookshelf(entries, BookshelfSortOrder.NAME).map { it.albumId })
    }

    @Test
    fun omittingDirectionKeepsEachFieldsHabitualOrder() {
        val entries = listOf(
            BookshelfEntry("1", "alpha", "", "", "", addedAt = 10L, updatedAt = 10L, lastReadAt = 100L),
            BookshelfEntry("2", "Beta", "", "", "", addedAt = 20L, updatedAt = 20L, lastReadAt = 50L),
        )

        // 不传方向时必须与改动前逐项一致：名称 A→Z，时间类最新在前。
        BookshelfSortOrder.entries.forEach { order ->
            assertEquals(
                "默认方向应等于 ${order.name} 的 defaultDirection",
                sortBookshelf(entries, order).map { it.albumId },
                sortBookshelf(entries, order, order.defaultDirection).map { it.albumId },
            )
        }
        assertEquals(listOf("1", "2"), sortBookshelf(entries, BookshelfSortOrder.NAME).map { it.albumId })
        assertEquals(listOf("2", "1"), sortBookshelf(entries, BookshelfSortOrder.UPDATED).map { it.albumId })
        assertEquals(
            listOf("1", "2"),
            sortBookshelf(entries, BookshelfSortOrder.RECENTLY_READ).map { it.albumId },
        )
    }

    @Test
    fun reversingDirectionReversesEveryField() {
        val entries = listOf(
            BookshelfEntry("1", "alpha", "", "", "", addedAt = 10L, updatedAt = 10L, lastReadAt = 100L),
            BookshelfEntry("2", "Beta", "", "", "", addedAt = 20L, updatedAt = 20L, lastReadAt = 50L),
            BookshelfEntry("3", "gamma", "", "", "", addedAt = 30L, updatedAt = 30L, lastReadAt = null),
        )

        BookshelfSortOrder.entries.forEach { order ->
            val ascending = sortBookshelf(entries, order, BookshelfSortDirection.ASCENDING).map { it.albumId }
            val descending = sortBookshelf(entries, order, BookshelfSortDirection.DESCENDING).map { it.albumId }
            assertEquals("${order.name} 的两个方向应互为逆序", ascending.reversed(), descending)
        }
    }

    @Test
    fun ascendingRecentlyReadPutsMostRecentlyReadFirst() {
        val entries = listOf(
            BookshelfEntry("read", "alpha", "", "", "", addedAt = 10L, updatedAt = 10L, lastReadAt = 100L),
            BookshelfEntry("fresh", "Beta", "", "", "", addedAt = 20L, updatedAt = 20L, lastReadAt = null),
        )

        // 时间类的"正序"= 最新在前：最近读过的排前面；没读过的按 Long.MIN_VALUE 落到最后，
        // 而不是被当成"刚读过"顶到最前。
        assertEquals(
            listOf("read", "fresh"),
            sortBookshelf(entries, BookshelfSortOrder.RECENTLY_READ, BookshelfSortDirection.ASCENDING)
                .map { it.albumId },
        )
        // 倒序才把没读过的翻到最前。
        assertEquals(
            listOf("fresh", "read"),
            sortBookshelf(entries, BookshelfSortOrder.RECENTLY_READ, BookshelfSortDirection.DESCENDING)
                .map { it.albumId },
        )
    }

    @Test
    fun recentlyAddedIgnoresMetadataAndReadingChanges() {
        val entries = listOf(
            BookshelfEntry("old", "Old", "", "", "", addedAt = 10L, updatedAt = 999L, lastReadAt = 999L),
            BookshelfEntry("new", "New", "", "", "", addedAt = 20L, updatedAt = 20L),
            BookshelfEntry("same", "Same", "", "", "", addedAt = 20L, updatedAt = 1000L),
        )
        assertEquals(
            listOf("new", "same", "old"),
            sortBookshelf(entries, BookshelfSortOrder.ADDED).map { it.albumId },
        )
        assertEquals(
            listOf("old", "same", "new"),
            sortBookshelf(entries, BookshelfSortOrder.ADDED, BookshelfSortDirection.DESCENDING)
                .map { it.albumId },
        )
    }

    @Test
    fun addingAgainPreservesOriginalAddedTime() {
        val initial = addToBookshelf(emptyList(), firstAlbum, addedAt = 10L).first
        val updated = addToBookshelf(initial, firstAlbum.copy(name = "新名称"), addedAt = 100L).first
        assertEquals(10L, updated.single().addedAt)
        assertEquals(100L, updated.single().updatedAt)
    }

    @Test
    fun tagRulesRequireEveryRuleAndAcceptCommonDelimiters() {
        val rules = parseBookshelfTagRules("韩漫，全彩  连载中")

        assertEquals(listOf("韩漫", "全彩", "连载中"), rules)
        assertTrue(matchesBookshelfTagRules(listOf("韓漫", "全彩", "連載中"), rules))
        assertFalse(matchesBookshelfTagRules(listOf("韩漫", "全彩"), rules))
    }

    @Test
    fun authorRulesRequireEveryAuthorAndNormalizeTraditionalChinese() {
        val rules = parseBookshelfAuthorRules("作者甲，作者乙")

        assertEquals(listOf("作者甲", "作者乙"), rules)
        assertTrue(matchesBookshelfAuthorRules("作者甲 / 作者乙", rules))
        assertTrue(matchesBookshelfAuthorRules("測試作者 甲作家", listOf("测试作者", "甲作家")))
        assertFalse(matchesBookshelfAuthorRules("作者甲", rules))
    }

    @Test
    fun assigningEntriesToGroupsOnlyChangesSelectedAlbums() {
        val entries = listOf(
            BookshelfEntry("1", "第一本", "", "", "", 10L, groupIds = setOf("group-a")),
            BookshelfEntry("2", "第二本", "", "", "", 20L),
            BookshelfEntry("3", "第三本", "", "", "", 30L),
        )

        val (updated, changed) = assignBookshelfGroups(
            entries = entries,
            albumIds = setOf("1", "2"),
            groupIds = setOf("group-b"),
            updatedAt = 100L,
        )

        assertEquals(2, changed)
        assertEquals(setOf("group-a", "group-b"), updated.first { it.albumId == "1" }.groupIds)
        assertEquals(setOf("group-b"), updated.first { it.albumId == "2" }.groupIds)
        assertTrue(updated.first { it.albumId == "3" }.groupIds.isEmpty())
        assertEquals(100L, updated.first { it.albumId == "1" }.updatedAt)
    }

    @Test
    fun pickerSearchMatchesSimplifiedTraditionalAndVehicleNumber() {
        val album = HomeAlbum(
            id = "438516",
            name = "異世界全彩漫畫",
            author = "測試作者",
            coverUrl = "",
            imageHost = "",
        )

        assertTrue(album.matchesBookshelfPickerQuery("异世界"))
        assertTrue(album.matchesBookshelfPickerQuery("测试作者"))
        assertTrue(album.matchesBookshelfPickerQuery("JM438516"))
        assertFalse(album.matchesBookshelfPickerQuery("韩漫"))
    }

    @Test
    fun exportingOneGroupKeepsOnlyThatGroupAndDropsDanglingMemberships() {
        val groups = listOf(
            group("group-a", "甲组", createdAt = 1L),
            group("group-b", "乙组", createdAt = 2L),
        )
        val entries = listOf(
            BookshelfEntry("1", "第一本", "", "", "", 10L, groupIds = setOf("group-a", "group-b")),
            BookshelfEntry("2", "第二本", "", "", "", 20L, groupIds = setOf("group-b")),
            BookshelfEntry("3", "第三本", "", "", "", 30L),
        )

        val snapshot = bookshelfSnapshotOf(entries, groups, setOf("group-a"), includeGroups = true)

        assertEquals(listOf("group-a"), snapshot.groups.map { it.id })
        assertEquals(listOf("1"), snapshot.entries.map { it.albumId })
        assertEquals(setOf("group-a"), snapshot.entries.single().groupIds)
    }

    @Test
    fun exportingEverythingWithoutGroupsKeepsAllComicsButNoMemberships() {
        val groups = listOf(group("group-a", "甲组", createdAt = 1L))
        val entries = listOf(
            BookshelfEntry("1", "第一本", "", "", "", 10L, groupIds = setOf("group-a")),
            BookshelfEntry("2", "第二本", "", "", "", 20L),
        )

        val snapshot = bookshelfSnapshotOf(entries, groups, groupIds = null, includeGroups = false)

        assertTrue(snapshot.groups.isEmpty())
        assertEquals(listOf("1", "2"), snapshot.entries.map { it.albumId })
        assertTrue(snapshot.entries.all { it.groupIds.isEmpty() })
    }

    @Test
    fun replacingDropsLocalContentAndCutsMembershipsToImportedGroups() {
        val local = BookshelfSnapshot(
            entries = listOf(BookshelfEntry("9", "本地", "", "", "", 5L)),
            groups = listOf(group("group-local", "本地组", createdAt = 1L)),
        )
        val incoming = BookshelfSnapshot(
            entries = listOf(
                BookshelfEntry("1", "第一本", "", "", "", 10L, groupIds = setOf("group-a", "group-missing")),
            ),
            groups = listOf(group("group-a", "甲组", createdAt = 2L)),
        )

        val (next, outcome) = replaceBookshelfWith(incoming)

        assertEquals(listOf("1"), next.entries.map { it.albumId })
        assertEquals(setOf("group-a"), next.entries.single().groupIds)
        assertEquals(listOf("group-a"), next.groups.map { it.id })
        assertEquals(1, outcome.addedEntries)
        assertEquals(0, outcome.droppedEntries)
        assertFalse(next.entries.map { it.albumId }.containsAll(local.entries.map { it.albumId }))
    }

    @Test
    fun mergingReusesGroupsByNameAndNeverOverwritesLocalProgress() {
        val localGroups = listOf(group("group-mine", "甲组", createdAt = 1L))
        val localEntries = listOf(
            BookshelfEntry("1", "第一本", "", "", "", 10L, lastChapterId = "11", lastPageIndex = 8),
        )
        val incoming = BookshelfSnapshot(
            entries = listOf(
                BookshelfEntry("1", "别人的标题", "", "", "", 99L, groupIds = setOf("group-theirs"), lastPageIndex = 3),
                BookshelfEntry("2", "第二本", "", "", "", 98L, groupIds = setOf("group-new")),
            ),
            groups = listOf(
                group("group-theirs", "甲组", createdAt = 2L),
                group("group-new", "乙组", createdAt = 3L),
            ),
        )

        val (next, outcome) = mergeBookshelfWith(localEntries, localGroups, incoming, mergedAt = 500L)

        assertEquals(listOf("group-mine", "group-new"), next.groups.map { it.id })
        assertEquals(1, outcome.reusedGroups)
        assertEquals(1, outcome.addedGroups)
        assertEquals(1, outcome.addedEntries)
        assertEquals(1, outcome.mergedEntries)

        val kept = next.entries.first { it.albumId == "1" }
        assertEquals("第一本", kept.name)
        assertEquals("11", kept.lastChapterId)
        assertEquals(8, kept.lastPageIndex)
        assertEquals(setOf("group-mine"), kept.groupIds)
        assertEquals(500L, kept.updatedAt)
        assertEquals(setOf("group-new"), next.entries.first { it.albumId == "2" }.groupIds)
    }

    @Test
    fun mergingOwnExportBackRemapsCollidingGroupIdsInsteadOfDuplicatingNames() {
        val localGroups = listOf(group("group-a", "甲组", createdAt = 1L))
        val localEntries = listOf(BookshelfEntry("1", "第一本", "", "", "", 10L, groupIds = setOf("group-a")))
        val renamed = BookshelfSnapshot(
            entries = listOf(BookshelfEntry("2", "第二本", "", "", "", 20L, groupIds = setOf("group-a"))),
            groups = listOf(group("group-a", "乙组", createdAt = 2L)),
        )

        val (next, outcome) = mergeBookshelfWith(
            entries = localEntries,
            groups = localGroups,
            snapshot = renamed,
            mergedAt = 500L,
            freshGroupId = { "group-fresh" },
        )

        assertEquals(listOf("甲组", "乙组"), next.groups.map { it.name })
        assertEquals(listOf("group-a", "group-fresh"), next.groups.map { it.id })
        assertEquals(setOf("group-fresh"), next.entries.first { it.albumId == "2" }.groupIds)
        assertEquals(setOf("group-a"), next.entries.first { it.albumId == "1" }.groupIds)
        assertEquals(0, outcome.reusedGroups)
    }

    @Test
    fun mergingIdenticalFileTwiceChangesNothingTheSecondTime() {
        val groups = listOf(group("group-a", "甲组", createdAt = 1L))
        val incoming = BookshelfSnapshot(
            entries = listOf(BookshelfEntry("1", "第一本", "", "", "", 10L, groupIds = setOf("group-a"))),
            groups = groups,
        )

        val (once, _) = mergeBookshelfWith(emptyList(), emptyList(), incoming, mergedAt = 500L)
        val (twice, outcome) = mergeBookshelfWith(once.entries, once.groups, incoming, mergedAt = 600L)

        assertEquals(once, twice)
        assertEquals(0, outcome.addedEntries)
        assertEquals(0, outcome.mergedEntries)
        assertEquals(0, outcome.addedGroups)
        assertEquals(1, outcome.reusedGroups)
    }
}
