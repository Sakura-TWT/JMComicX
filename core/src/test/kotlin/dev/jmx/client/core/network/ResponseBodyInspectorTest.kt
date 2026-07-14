package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseBodyInspectorTest {
    @Test
    fun emptyBodyIsRetryableNetworkError() {
        val result = ResponseBodyInspector.inspect(ApiRoute.Album, 200, "   ")
        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Network)
        assertTrue(error.retryable)
    }

    @Test
    fun nonJsonEncryptedRouteIsRetryableDecodeError() {
        val result = ResponseBodyInspector.inspect(
            ApiRoute.Album,
            200,
            "<html>gateway error</html>"
        )
        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Decode)
        assertTrue(error.retryable)
    }

    @Test
    fun chapterTemplateAllowsNonJsonHtml() {
        val result = ResponseBodyInspector.inspect(
            ApiRoute.ChapterViewTemplate,
            200,
            "<html><script>const result={}</script></html>"
        )
        assertTrue(result is JmxResult.Success)
    }

    @Test
    fun knownRestrictedAccessTextIsHttpRetryable() {
        val result = ResponseBodyInspector.inspect(
            ApiRoute.Album,
            200,
            "Restricted Access!"
        )
        assertTrue(result is JmxResult.Failure)
        val error = (result as JmxResult.Failure).error
        assertTrue(error is JmxError.Http)
        assertTrue(error.retryable)
    }
}
