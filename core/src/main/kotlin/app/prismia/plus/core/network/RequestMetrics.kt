package app.prismia.plus.core.network

import java.util.ArrayDeque

/**
 * 脱敏的请求耗时/失败观测记录。
 * 只保留路由、域名、耗时、尝试次数与错误类别，不落任何请求体或凭据。
 */
data class RequestMetricRecord(
    val route: String,
    val endpointHost: String,
    val durationMillis: Long,
    val attempts: Int,
    val success: Boolean,
    val errorKind: String? = null
) {
    val retries: Int get() = (attempts - 1).coerceAtLeast(0)
}

data class RouteMetricSummary(
    val route: String,
    val requestCount: Int,
    val failureCount: Int,
    val averageDurationMillis: Long,
    val maxDurationMillis: Long,
    val retryCount: Int
)

class RequestMetricsRecorder(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    private val lock = Any()
    private val records = ArrayDeque<RequestMetricRecord>(capacity.coerceAtLeast(1))

    fun record(record: RequestMetricRecord) {
        synchronized(lock) {
            if (records.size >= capacity.coerceAtLeast(1)) records.removeFirst()
            records.addLast(record)
        }
    }

    fun snapshot(): List<RequestMetricRecord> = synchronized(lock) { records.toList() }

    fun clear() = synchronized(lock) { records.clear() }

    fun summarize(): List<RouteMetricSummary> {
        val grouped = snapshot().groupBy { it.route }
        return grouped.map { (route, list) ->
            RouteMetricSummary(
                route = route,
                requestCount = list.size,
                failureCount = list.count { !it.success },
                averageDurationMillis = if (list.isEmpty()) 0L else list.sumOf { it.durationMillis } / list.size,
                maxDurationMillis = list.maxOfOrNull { it.durationMillis } ?: 0L,
                retryCount = list.sumOf { it.retries }
            )
        }.sortedWith(
            compareByDescending<RouteMetricSummary> { it.requestCount }
                .thenByDescending { it.maxDurationMillis }
        )
    }

    private companion object {
        const val DEFAULT_CAPACITY = 240
    }
}
