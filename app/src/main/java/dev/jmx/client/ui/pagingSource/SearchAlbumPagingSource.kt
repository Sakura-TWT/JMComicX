package dev.jmx.client.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jmx.client.data.models.Album
import dev.jmx.client.data.models.AlbumSearchMainTag
import dev.jmx.client.data.models.AlbumSearchOrderFilter
import dev.jmx.client.data.models.SearchTagFilter
import dev.jmx.client.data.models.SearchTagFilterMode
import dev.jmx.client.repository.AlbumRepository
import dev.jmx.client.data.remote.model.AlbumListResponse
import dev.jmx.client.data.remote.model.NetworkResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SearchAlbumFilter(
    val order: AlbumSearchOrderFilter = AlbumSearchOrderFilter.NEWEST,
    val searchContent: String = "",
    val tagFilter: SearchTagFilter = SearchTagFilter(),
)

class SearchAlbumPagingSource(
    private val albumRepository: AlbumRepository,
    private val filter: SearchAlbumFilter,
    private val onFindSingleAlbumId: (id: Int?) -> Unit = {}
) : PagingSource<Int, Album>() {
    private data class ServerSearch(
        val searchContent: String,
        val mainTag: AlbumSearchMainTag,
        val serverMatchedTag: String? = null,
    )

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Album> {
        val currentPage = params.key ?: 1
        val serverSearch = resolveServerSearch()
        return when (val data =
            albumRepository.getAlbumList(
                page = currentPage,
                order = filter.order,
                searchContent = serverSearch.searchContent,
                mainTag = serverSearch.mainTag,
            )) {
            is NetworkResult.Error -> {
                LoadResult.Error(Exception(data.message))
            }

            is NetworkResult.Success<AlbumListResponse> -> {
                if (data.data.redirect_aid != null) {
                    onFindSingleAlbumId(data.data.redirect_aid.toInt())
                    LoadResult.Page(
                        data = listOf(),
                        prevKey = null,
                        nextKey = null
                    )
                } else {
                    onFindSingleAlbumId(null)
                    val list = filterAlbums(data.data.toAlbumList(), serverSearch)
                    val total = data.data.total.toInt()
                    val isLastPage = currentPage >= (total + params.loadSize - 1) / params.loadSize
                    LoadResult.Page(
                        data = list,
                        prevKey = if (currentPage == 1) null else currentPage - 1,
                        nextKey = if (isLastPage) null else currentPage + 1,
                        itemsAfter = if (filter.tagFilter.enabled && !isLastPage) 1 else LoadResult.Page.COUNT_UNDEFINED
                    )
                }
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Album>): Int? = null

    private fun resolveServerSearch(): ServerSearch {
        val targetTags = filter.tagFilter.tags.normalizedTags()
        val firstIncludeTag = targetTags.firstOrNull()
        return if (filter.tagFilter.mode == SearchTagFilterMode.INCLUDE && firstIncludeTag != null) {
            ServerSearch(
                searchContent = firstIncludeTag,
                mainTag = AlbumSearchMainTag.TAG,
                serverMatchedTag = firstIncludeTag,
            )
        } else {
            ServerSearch(
                searchContent = filter.searchContent,
                mainTag = AlbumSearchMainTag.SITE_SEARCH,
            )
        }
    }

    private suspend fun filterAlbums(list: List<Album>, serverSearch: ServerSearch): List<Album> {
        val keywordFiltered = if (serverSearch.mainTag == AlbumSearchMainTag.TAG) {
            list.filter { it.matchesKeyword(filter.searchContent) }
        } else {
            list
        }
        val tagFilter = filter.tagFilter
        if (!tagFilter.enabled) return keywordFiltered

        val remainingFilter = tagFilter.withoutServerMatchedTag(serverSearch.serverMatchedTag)
        if (!remainingFilter.enabled) return keywordFiltered

        return filterAlbumsByDetails(keywordFiltered, remainingFilter)
    }

    private suspend fun filterAlbumsByDetails(list: List<Album>, tagFilter: SearchTagFilter): List<Album> {
        val semaphore = Semaphore(6)
        return coroutineScope {
            list.map { album ->
                async {
                    semaphore.withPermit {
                        when (val detail = albumRepository.getAlbumDetail(album.id)) {
                            is NetworkResult.Error -> null
                            is NetworkResult.Success -> {
                                val detailedAlbum = detail.data.toAlbum()
                                if (matchesTagFilter(detailedAlbum.tagList, tagFilter)) {
                                    detailedAlbum
                                } else {
                                    null
                                }
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun matchesTagFilter(albumTags: List<String>, filter: SearchTagFilter): Boolean {
        val normalizedAlbumTags = albumTags.normalizedTags()
        val targetTags = filter.tags.normalizedTags()
        if (targetTags.isEmpty()) return true
        return when (filter.mode) {
            SearchTagFilterMode.INCLUDE -> targetTags.all { target ->
                normalizedAlbumTags.any { albumTag ->
                    albumTag == target || albumTag.contains(target) || target.contains(albumTag)
                }
            }

            SearchTagFilterMode.EXCLUDE -> targetTags.none { target ->
                normalizedAlbumTags.any { albumTag ->
                    albumTag == target || albumTag.contains(target) || target.contains(albumTag)
                }
            }
        }
    }

    private fun SearchTagFilter.withoutServerMatchedTag(serverMatchedTag: String?): SearchTagFilter {
        if (serverMatchedTag == null || mode != SearchTagFilterMode.INCLUDE) return this
        val remainingTags = tags.filterNot { it.normalizeTag() == serverMatchedTag }
        return copy(tags = remainingTags)
    }

    private fun Album.matchesKeyword(keyword: String): Boolean {
        val normalizedKeyword = keyword.normalizeTag()
        if (normalizedKeyword.isBlank()) return true
        return name.normalizeTag().contains(normalizedKeyword) ||
            description.normalizeTag().contains(normalizedKeyword) ||
            authorList.any { it.normalizeTag().contains(normalizedKeyword) }
    }

    private fun List<String>.normalizedTags(): List<String> {
        return map { it.normalizeTag() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun String.normalizeTag(): String {
        return trim().lowercase()
    }
}
