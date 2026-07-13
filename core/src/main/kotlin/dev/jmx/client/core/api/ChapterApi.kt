package dev.jmx.client.core.api

import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.chapter.ChapterTemplateParser
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

class ChapterApi(
    private val apiClient: JmxApiClient,
    private val parser: ChapterTemplateParser = ChapterTemplateParser()
) {
    suspend fun template(
        chapterId: String,
        shunt: String,
        page: Int = 0,
        mode: String = "vertical",
        express: String = "off",
        timestampSeconds: Long = System.currentTimeMillis() / 1000L
    ): JmxResult<ChapterTemplate> {
        if (chapterId.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("chapterId 为空", field = "chapterId"))
        }
        val html = when (
            val result = apiClient.requestText(
                apiRequest(ApiRoute.ChapterViewTemplate) {
                    query("id", chapterId)
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
        return parser.parse(html)
    }
}
