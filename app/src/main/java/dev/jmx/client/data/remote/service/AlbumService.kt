package dev.jmx.client.data.remote.service

import dev.jmx.client.data.remote.model.CollectAlbumResponse
import dev.jmx.client.data.remote.model.AlbumDetailResponse
import dev.jmx.client.data.remote.model.AlbumListResponse
import dev.jmx.client.data.remote.model.CommentAlbumResponse
import dev.jmx.client.data.remote.model.CommentListResponse
import dev.jmx.client.data.remote.model.HomeSwiperAlbumListItemResponse
import dev.jmx.client.data.remote.model.LikeAlbumResponse
import dev.jmx.client.data.remote.model.ResponseWrapper
import dev.jmx.client.data.remote.model.WeekRecommendAlbumResponse
import dev.jmx.client.data.remote.model.WeekResponse
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface AlbumService {

    @GET("album")
    suspend fun getAlbumDetail(
        @Query("id") id: Int,
    ): ResponseWrapper<AlbumDetailResponse>

    @POST("like")
    @Multipart
    suspend fun likeAlbum(
        @Part("id") id: Int,
    ): ResponseWrapper<LikeAlbumResponse>

    @POST("favorite")
    @Multipart
    suspend fun collectAlbum(
        @Part("aid") id: Int,
    ): ResponseWrapper<CollectAlbumResponse>

    @GET("promote")
    suspend fun getHomeSwiperAlbumList(
        @Query("_") timestamp: Long = System.currentTimeMillis()
    ): ResponseWrapper<List<HomeSwiperAlbumListItemResponse>>

    @GET("chapter_view_template")
    suspend fun getAlbumImageList(
        @Query("id") id: Int,
        // TODO 图片设置
        @Query("app_img_shunt") shunt: String,
        @Query("mode") mode: String = "vertical",
        @Query("page") page: Int = 0,
        @Query("express") express: String = "off",
        @Query("v") v: Long = System.currentTimeMillis() / 1000,
    ): String

    @GET("search")
    suspend fun getAlbumList(
        @Query("page") page: Int,
        @Query("o") order: String,
        @Query("search_query") searchContent: String,
        @Query("main_tag") mainTag: Int = 0,
        @Query("t") time: String = "a",
    ): ResponseWrapper<AlbumListResponse>

    @GET("week")
    suspend fun getWeekData(): ResponseWrapper<WeekResponse>

    @GET("week/filter")
    suspend fun getWeekRecommendAlbumList(
        @Query("page") page: Int,
        @Query("id") categoryId: String,
        @Query("type") typeId: String,
    ): ResponseWrapper<WeekRecommendAlbumResponse>

    @GET("forum")
    suspend fun getCommentList(
        @Query("page") page: Int,
        @Query("aid") albumId: Int,
        @Query("mode") mode: String = "manhua",
    ): ResponseWrapper<CommentListResponse>

    @POST("comment")
    @Multipart
    suspend fun comment(
        @Part("comment") content: String,
        @Part("aid") id: Int,
        @Part("status") status: String, // TODO 是否剧透
        @Part("comment_id") commentId: Int? = null,
    ): ResponseWrapper<CommentAlbumResponse>
}
