package app.prismia.plus

import app.prismia.plus.core.api.FavoriteFolder
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteFolderLogicTest {
    private fun album(id: String) = HomeAlbum(id, id, "", "", "")

    @Test
    fun folderChoicesExcludeAllAndInvalidIdsAndDeduplicate() {
        val folders = listOf(
            FavoriteFolder("0", "全部", null), FavoriteFolder("7", "A", null),
            FavoriteFolder("07", "重复", null), FavoriteFolder("x", "无效", null),
        )
        assertEquals(listOf("7"), favoriteFolderChoices(folders).map { it.id })
    }

    @Test
    fun reversePaginationProbesThenReadsLastPageInSameLoader() = runBlocking {
        val requests = mutableListOf<Int>()
        val result = loadFavoriteLogicalPage(1, FavoriteSortDirection.DESCENDING, null) { page ->
            requests += page
            JmxResult.Success(AccountAlbumPage(if (page == 3) listOf(album("41"), album("42")) else listOf(album("1")), 42))
        }
        assertEquals(listOf(1, 3), requests)
        assertEquals(listOf("42", "41"), (result as JmxResult.Success).value.albums.map { it.id })
    }

    @Test
    fun reversePaginationRejectsUnknownTotal() = runBlocking {
        val result = loadFavoriteLogicalPage(1, FavoriteSortDirection.DESCENDING, null) {
            JmxResult.Success(AccountAlbumPage(listOf(album("1")), null))
        }
        assertTrue(result is JmxResult.Failure)
    }

    @Test
    fun reversePaginationStopsWithoutRequestAfterFirstServerPage() = runBlocking {
        var requests = 0
        val result = loadFavoriteLogicalPage(4, FavoriteSortDirection.DESCENDING, 3) {
            requests++
            JmxResult.Success(AccountAlbumPage(emptyList(), 42))
        }
        assertEquals(0, requests)
        assertTrue((result as JmxResult.Success).value.albums.isEmpty())
    }

    @Test
    fun reversePaginationRejectsChangedServerPageCount() = runBlocking {
        val result = loadFavoriteLogicalPage(2, FavoriteSortDirection.DESCENDING, 3) {
            JmxResult.Success(AccountAlbumPage(listOf(album("1")), 70))
        }
        assertTrue(result is JmxResult.Failure)
    }
}
