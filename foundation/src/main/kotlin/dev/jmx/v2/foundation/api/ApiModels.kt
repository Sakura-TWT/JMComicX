package dev.jmx.v2.foundation.api

data class RemoteSetting(
    val apiVersion: String?,
    val imageHost: String?,
    val shunts: List<ApiShunt>
)

data class ApiShunt(
    val id: String,
    val name: String
)

data class LoginSession(
    val avs: String?,
    val raw: Map<String, Any?>
)

data class AlbumSummary(
    val id: String,
    val name: String?,
    val author: String?,
    val imageCount: Int?
)

data class SearchPage(
    val total: Int?,
    val redirectAlbumId: String?,
    val content: List<AlbumSummary>
)
