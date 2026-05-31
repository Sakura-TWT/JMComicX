package dev.jmx.client.repository.impl

import dev.jmx.client.data.models.AlbumSearchOrderFilter
import dev.jmx.client.data.models.AlbumSearchMainTag
import dev.jmx.client.repository.BaseRepository
import dev.jmx.client.repository.AlbumRepository
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
import dev.jmx.client.data.remote.parseHtml
import dev.jmx.client.data.remote.parseRange
import dev.jmx.client.data.remote.parseSpeed
import dev.jmx.client.data.remote.service.AlbumService
import dev.jmx.client.store.InitManager

class AlbumRepositoryImpl(
    private val service: AlbumService,
    initManager: InitManager
) : BaseRepository(initManager), AlbumRepository {
    override suspend fun getAlbumDetail(id: Int): NetworkResult<AlbumDetailResponse> {
        return safeApiCall {
            service.getAlbumDetail(id)
        }
    }

    override suspend fun likeAlbum(id: Int): NetworkResult<LikeAlbumResponse> {
        return safeApiCall {
            service.likeAlbum(id)
        }
    }

    override suspend fun collectAlbum(id: Int): NetworkResult<CollectAlbumResponse> {
        return safeApiCall {
            service.collectAlbum(id)
        }
    }

    override suspend fun unCollectAlbum(id: Int): NetworkResult<CollectAlbumResponse> {
        return safeApiCall {
            service.collectAlbum(id)
        }
    }

    override suspend fun getHomeSwiperAlbumList(): NetworkResult<List<HomeSwiperAlbumListItemResponse>> {
        return safeApiCall {
            service.getHomeSwiperAlbumList()
        }
    }

    override suspend fun getAlbumImageList(id: Int, shunt: String): NetworkResult<AlbumImageListResponse> {
        return when (val res = safeStringCall {
            service.getAlbumImageList(id, shunt)
        }) {
            is NetworkResult.Success<String> -> {
                val htmlStr = res.data
                val pair = parseRange(htmlStr)
                NetworkResult.Success(AlbumImageListResponse(
                    list = parseHtml(htmlStr),
                    __aId = pair.first,
                    __scrambleId = pair.second,
                    __speed = parseSpeed(htmlStr)
                ))
            }

            else -> {
                NetworkResult.Error("从 HTML 解析图片列表失败")
            }
        }
    }

    override suspend fun getAlbumList(
        page: Int,
        order: AlbumSearchOrderFilter,
        searchContent: String,
        mainTag: AlbumSearchMainTag,
    ): NetworkResult<AlbumListResponse> {
        return safeApiCall {
            service.getAlbumList(page, order.value, searchContent, mainTag.value)
        }
    }

    override suspend fun getWeekData(): NetworkResult<WeekResponse> {
        return safeApiCall {
            service.getWeekData()
        }
    }

    override suspend fun getWeekRecommendAlbumList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetworkResult<WeekRecommendAlbumResponse> {
        return safeApiCall {
            service.getWeekRecommendAlbumList(
                page,
                categoryId,
                typeId
            )
        }
    }

    override suspend fun getCommentList(
        page: Int,
        albumId: Int
    ): NetworkResult<CommentListResponse> {
        return safeApiCall {
            service.getCommentList(
                page,
                albumId,
                "manhua"
            )
        }
    }

    override suspend fun comment(
        content: String,
        albumId: Int,
        commentId: Int?
    ): NetworkResult<CommentAlbumResponse> {
        return safeApiCall {
            service.comment(
                content,
                albumId,
                "1",
                commentId,
            )
        }
    }
}
