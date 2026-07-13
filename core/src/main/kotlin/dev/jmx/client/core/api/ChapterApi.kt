package dev.jmx.client.core.api

import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.chapter.ChapterTemplateParser
import dev.jmx.client.core.network.ApiRequest
import dev.jmx.client.core.network.JmxApiClient
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
                ApiRequest(
                    route = ApiRoute.ChapterViewTemplate,
                    query = mapOf(
                        "id" to chapterId,
                        "app_img_shunt" to shunt,
                        "mode" to mode,
                        "page" to page.coerceAtLeast(0).toString(),
                        "express" to express,
                        "v" to timestampSeconds.toString()
                    )
                )
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        return parser.parse(html)
    }
}
