package dev.jmx.client.repository

import dev.jmx.client.data.models.AlbumSearchOrderFilter
import dev.jmx.client.data.models.AlbumSearchMainTag
import dev.jmx.client.data.remote.model.CollectAlbumResponse
import dev.jmx.client.data.remote.model.AlbumDetailResponse
import dev.jmx.client.data.remote.model.AlbumListResponse
import dev.jmx.client.data.remote.model.AlbumImageListResponse
import dev.jmx.client.data.remote.model.CommentAlbumResponse
import dev.jmx.client.data.remote.model.CommentListResponse
import dev.jmx.client.data.remote.model.HomeSwiperAlbumListItemResponse
import dev.jmx.client.data.remote.model.LikeAlbumResponse
import dev.jmx.client.data.remote.model.NetworkResult
import dev.jmx.client.data.remote.model.WeekRecommendAlbumResponse
import dev.jmx.client.data.remote.model.WeekResponse

interface AlbumRepository {
    suspend fun getAlbumDetail(id: Int): NetworkResult<AlbumDetailResponse>
    suspend fun likeAlbum(id: Int): NetworkResult<LikeAlbumResponse>
    suspend fun collectAlbum(id: Int): NetworkResult<CollectAlbumResponse>
    suspend fun unCollectAlbum(id: Int): NetworkResult<CollectAlbumResponse>
    suspend fun getHomeSwiperAlbumList(): NetworkResult<List<HomeSwiperAlbumListItemResponse>>
    suspend fun getAlbumImageList(id: Int, shunt: String): NetworkResult<AlbumImageListResponse>
    suspend fun getAlbumList(
        page: Int,
        order: AlbumSearchOrderFilter,
        searchContent: String,
        mainTag: AlbumSearchMainTag = AlbumSearchMainTag.SITE_SEARCH,
    ): NetworkResult<AlbumListResponse>

    suspend fun getWeekData(): NetworkResult<WeekResponse>
    suspend fun getWeekRecommendAlbumList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetworkResult<WeekRecommendAlbumResponse>

    suspend fun getCommentList(
        page: Int,
        albumId: Int,
    ): NetworkResult<CommentListResponse>

    suspend fun comment(
        content: String,
        albumId: Int,
        commentId: Int?
    ): NetworkResult<CommentAlbumResponse>
}
