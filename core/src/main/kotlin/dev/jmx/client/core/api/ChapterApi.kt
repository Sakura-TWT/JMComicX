package dev.jmx.client.core.api

import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.chapter.ChapterTemplateParser
import dev.jmx.client.core.chapter.ScrambleIdCache
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.network.withExchange
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.protocol.JmId
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class ChapterApi(
    private val apiClient: JmxApiClient,
    private val parser: ChapterTemplateParser = ChapterTemplateParser(),
    private val scrambleIdCache: ScrambleIdCache = ScrambleIdCache()
) {

    suspend fun detail(chapterId: String): JmxResult<PhotoDetail> {
        val id = when (val parsed = JmId.parse(chapterId)) {
            is JmxResult.Success -> parsed.value
            is JmxResult.Failure -> return parsed
        }
        val data = when (
            val result = apiClient.requestJson(
                apiRequest(ApiRoute.Chapter) {
                    query("id", id)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("chapter data is not an object"))
        if (root.stringOrNull("name", "title") == null && root.pageArrOrEmpty().isEmpty()) {
            return JmxResult.Failure(
                JmxError.Api(
                    code = 404,
                    message = "章节不存在或无数据：$id"
                )
            )
        }
        val detail = root.toPhotoDetail()
        detail.scrambleId?.let { scrambleIdCache.put(detail.id, it, detail.albumId) }
        return JmxResult.Success(detail)
    }

    suspend fun scrambleId(
        chapterId: String,
        albumId: String? = null,
        shunt: String = "1"
    ): JmxResult<Int> {
        val id = when (val parsed = JmId.parse(chapterId)) {
            is JmxResult.Success -> parsed.value
            is JmxResult.Failure -> return parsed
        }
        val album = albumId?.let { JmId.parseOrNull(it) }
        scrambleIdCache.get(id, album)?.let { return JmxResult.Success(it) }

        val template = when (val result = template(chapterId = id, shunt = shunt)) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> {

                val fallback = JmxProtocolConstants.Scramble220980
                scrambleIdCache.put(id, fallback, album)
                return JmxResult.Success(fallback)
            }
        }
        scrambleIdCache.put(id, template.scrambleId, album ?: template.albumId.toString())
        return JmxResult.Success(template.scrambleId)
    }

    suspend fun template(
        chapterId: String,
        shunt: String,
        page: Int = 0,
        mode: String = "vertical",
        express: String = "off",
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): JmxResult<ChapterTemplate> {
        val id = when (val parsed = JmId.parse(chapterId)) {
            is JmxResult.Success -> parsed.value
            is JmxResult.Failure -> return parsed
        }
        val response = when (
            val result = apiClient.requestTextResponse(
                apiRequest(ApiRoute.ChapterViewTemplate) {
                    query("id", id)
                    query("app_img_shunt", shunt)
                    query("mode", mode)
                    queryAtLeast("page", page, minimum = 0)
                    query("express", express)
                    query("v", timestampSeconds)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        return when (val parsed = parser.parse(response.text)) {
            is JmxResult.Success -> {
                scrambleIdCache.put(
                    photoId = parsed.value.chapterId,
                    scrambleId = parsed.value.scrambleId,
                    albumId = parsed.value.albumId.toString()
                )
                parsed
            }
            is JmxResult.Failure -> JmxResult.Failure(parsed.error.withExchange(response.exchange))
        }
    }

    fun cachedScrambleId(chapterId: String, albumId: String? = null): Int? {
        val id = JmId.parseOrNull(chapterId) ?: return null
        val album = albumId?.let { JmId.parseOrNull(it) }
        return scrambleIdCache.get(id, album)
    }
}

private fun com.google.gson.JsonObject.pageArrOrEmpty(): List<String> {
    val direct = get("page_arr") ?: get("images") ?: return emptyList()
    return when {
        direct.isJsonArray -> direct.asJsonArray.mapNotNull {
            it.takeIf { el -> el.isJsonPrimitive }?.asString
        }
        else -> emptyList()
    }
}
