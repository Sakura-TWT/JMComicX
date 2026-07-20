package dev.jmx.client

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
    fun tagRulesRequireEveryRuleAndAcceptCommonDelimiters() {
        val rules = parseBookshelfTagRules("韩漫，全彩  连载中")

        assertEquals(listOf("韩漫", "全彩", "连载中"), rules)
        assertTrue(matchesBookshelfTagRules(listOf("韓漫", "全彩", "連載中"), rules))
        assertFalse(matchesBookshelfTagRules(listOf("韩漫", "全彩"), rules))
    }
}
