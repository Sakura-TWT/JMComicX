package dev.jmx.client.core.runtime

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Public content can use persisted/default endpoints immediately; maintenance is not a prerequisite.
 * On a failed first attempt, one bounded bootstrap recovery is allowed only after a real protocol/
 * endpoint-list change. Authentication and business writes keep their own stricter workflow.
 */
suspend fun <T> JmxCore.publicRead(block: suspend () -> JmxResult<T>): JmxResult<T> {
    val version = apiVersionProvider.current()
    val hosts = endpointManager.all().map { it.url }
    val bootstrap = initializer.refreshInBackground()
    val first = withTimeoutOrNull(20_000) { block() }
        ?: JmxResult.Failure(JmxError.Network("内容请求超时，请重试或切换线路"))
    if (first is JmxResult.Success || bootstrap == null) return first
    val failure = (first as JmxResult.Failure).error
    if (failure !is JmxError.Network && failure !is JmxError.Domain && failure !is JmxError.Decode) return first
    withTimeoutOrNull(8_000) { bootstrap.await() } ?: return first
    if (version == apiVersionProvider.current() && hosts == endpointManager.all().map { it.url }) return first
    return withTimeoutOrNull(20_000) { block() } ?: first
}
