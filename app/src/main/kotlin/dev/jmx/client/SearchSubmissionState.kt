package dev.jmx.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class SearchSubmissionRequest(
    val query: String,
    val order: ComicSearchOrder,
    val tagFilter: SearchTagFilter,
    val generation: Long,
)

internal class SearchSubmissionState(private val epoch: SearchRequestEpoch) {
    var request by mutableStateOf<SearchSubmissionRequest?>(null)
        private set
    var state by mutableStateOf<ComicSearchUiState>(ComicSearchUiState.Idle)

    fun begin(query: String, order: ComicSearchOrder, tagFilter: SearchTagFilter) {
        val next = SearchSubmissionRequest(query.trim(), order, tagFilter, epoch.advance())
        state = ComicSearchUiState.Loading
        request = next
    }

    fun reset() {
        epoch.advance()
        request = null
        state = ComicSearchUiState.Idle
    }

    suspend fun execute(
        request: SearchSubmissionRequest,
        fetch: suspend (String, Int, ComicSearchOrder, SearchTagFilter) -> ComicSearchResult,
    ): HomeAlbum? {
        if (!epoch.accepts(request.generation)) return null
        val result = fetch(request.query, 1, request.order, request.tagFilter)
        if (!epoch.accepts(request.generation)) return null
        state = when (result) {
            is ComicSearchResult.Direct -> ComicSearchUiState.Content(
                query = request.query,
                albums = listOf(result.album),
                generation = request.generation,
                total = 1,
                nextPage = 2,
                order = request.order,
                tagFilter = request.tagFilter,
                endReached = true,
            )
            is ComicSearchResult.Page -> {
                val albums = result.albums.distinctBy { it.id }
                if (albums.isEmpty() && result.endReached) {
                    ComicSearchUiState.Empty(request.query.ifBlank { tagFilterSummary(request.tagFilter) })
                } else {
                    ComicSearchUiState.Content(
                        query = request.query,
                        albums = albums.take(MAX_SEARCH_RESULTS),
                        generation = request.generation,
                        browseLimited = albums.size >= MAX_SEARCH_RESULTS,
                        total = result.total,
                        nextPage = 2,
                        order = request.order,
                        tagFilter = request.tagFilter,
                        endReached = result.endReached || albums.size >= MAX_SEARCH_RESULTS,
                    )
                }
            }
            is ComicSearchResult.Error -> ComicSearchUiState.Error(result.message)
        }
        return (result as? ComicSearchResult.Direct)?.album
    }
}

internal sealed interface ComicSearchUiState {
    data object Idle : ComicSearchUiState
    data object Loading : ComicSearchUiState
    data class Empty(val query: String) : ComicSearchUiState
    data class Error(val message: String) : ComicSearchUiState
    data class Content(
        val query: String,
        val albums: List<HomeAlbum>,
        val total: Int?,
        val nextPage: Int,
        val generation: Long = 0,
        val browseLimited: Boolean = false,
        val order: ComicSearchOrder,
        val tagFilter: SearchTagFilter = SearchTagFilter(),
        val isLoadingMore: Boolean = false,
        val endReached: Boolean = false,
        val loadMoreError: String? = null,
    ) : ComicSearchUiState
}
