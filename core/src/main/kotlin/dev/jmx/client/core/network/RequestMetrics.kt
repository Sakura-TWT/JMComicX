package dev.jmx.client.core.network

import java.util.ArrayDeque
import kotlin.math.ceil

enum class LoadStage { ADMISSION, TRANSPORT_HEADERS, BODY_READ, DECODE, CACHE_READ, CACHE_WRITE, IMAGE_IO, IMAGE_DECODE, IMAGE_ADMISSION }
data class LoadStageRecord(val route: String, val stage: LoadStage, val durationMillis: Long)
data class LoadStageSummary(val route: String, val stage: LoadStage, val count: Int, val averageMillis: Long, val p95Millis: Long)

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
    private val stages = ArrayDeque<LoadStageRecord>(capacity.coerceAtLeast(1))

    fun stage(route: String, stage: LoadStage, nanos: Long) {
        synchronized(lock) {
            if (stages.size >= capacity.coerceAtLeast(1)) stages.removeFirst()
            stages.addLast(LoadStageRecord(route, stage, (nanos / 1_000_000L).coerceAtLeast(0)))
        }
    }

    fun stageSummaries(): List<LoadStageSummary> {
        val snapshot = synchronized(lock) { stages.toList() }
        return snapshot.groupBy { it.route to it.stage }.map { (key, values) ->
            val sorted = values.map { it.durationMillis }.sorted()
            LoadStageSummary(key.first, key.second, sorted.size, sorted.sum() / sorted.size,
                sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)])
        }.sortedWith(compareByDescending<LoadStageSummary> { it.p95Millis }.thenBy { it.route })
    }

    fun record(record: RequestMetricRecord) {
        synchronized(lock) {
            if (records.size >= capacity.coerceAtLeast(1)) records.removeFirst()
            records.addLast(record)
        }
    }

    fun snapshot(): List<RequestMetricRecord> = synchronized(lock) { records.toList() }

    fun clear() = synchronized(lock) { records.clear(); stages.clear() }

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
