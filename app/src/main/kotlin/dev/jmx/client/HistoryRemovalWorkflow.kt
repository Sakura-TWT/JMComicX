package dev.jmx.client

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal fun shouldUseNavigationRail(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 840f || (widthDp >= 600f && heightDp > 0f && widthDp > heightDp)

/** The first page shifts after deletion; a fresh empty read is the completion proof. */
internal suspend fun clearHistoryVerified(
    loadFirst: suspend () -> JmxResult<List<String>>,
    remove: suspend (String) -> JmxResult<Unit>,
    maxBatches: Int = 40,
    onProgress: (Int) -> Unit = {},
    timeoutMillis: Long = 90_000L,
): JmxResult<Unit> = withTimeoutOrNull(timeoutMillis) {
    val attempted = linkedSetOf<String>()
    repeat(maxBatches + 1) { round ->
        currentCoroutineContext().ensureActive()
        val ids = when (val page = loadFirst()) {
            is JmxResult.Failure -> return@withTimeoutOrNull page
            is JmxResult.Success -> page.value
        }
        val batch = nextHistoryClearBatch(attempted, ids)
            ?: return@withTimeoutOrNull JmxResult.Failure(JmxError.Schema("服务端仍保留已请求删除的记录，清理未完成"))
        if (batch.isEmpty()) return@withTimeoutOrNull JmxResult.Success(Unit)
        if (round == maxBatches) return@withTimeoutOrNull JmxResult.Failure(JmxError.Schema("已达到本轮清理预算，请继续清理剩余记录"))
        for (id in batch) {
            currentCoroutineContext().ensureActive()
            when (val deleted = remove(id)) {
                is JmxResult.Success -> Unit
                is JmxResult.Failure -> if (deleted.error !is JmxError.EmptyData) return@withTimeoutOrNull deleted
            }
            attempted += id
            onProgress(attempted.size)
        }
    }
    JmxResult.Failure(JmxError.Schema("清理未完成"))
} ?: JmxResult.Failure(JmxError.EmptyData("本轮清理已超时，部分记录可能已删除，请刷新后继续"))

/** Acknowledged writes are still read back; absence requires a complete membership snapshot. */
internal suspend fun deleteHistoryVerified(
    ids: Set<String>,
    remove: suspend (String) -> JmxResult<Unit>,
    loadPage: suspend (Int) -> JmxResult<AlbumHistoryIds>,
    isCurrent: () -> Boolean = { true },
    onProgress: (Int) -> Unit = {},
    maxPages: Int = 40,
    timeoutMillis: Long = 90_000L,
): FavoriteBatchResult {
    if (ids.isEmpty()) return FavoriteBatchResult(emptySet(), emptyMap())
    val attempted = linkedSetOf<String>()
    val failures = linkedMapOf<String, JmxError>()
    val result = withTimeoutOrNull(timeoutMillis) {
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) { failures[id] = JmxError.Api(401, "账号已变化"); continue }
            when (val removed = remove(id)) {
                is JmxResult.Success -> attempted += id
                is JmxResult.Failure -> if (removed.error is JmxError.EmptyData) attempted += id else failures[id] = removed.error
            }
            onProgress(attempted.size + failures.size)
        }
        var complete = false
        var verificationError: JmxError? = null
        val remaining = linkedSetOf<String>()
        for (number in 1..maxPages) {
            currentCoroutineContext().ensureActive()
            if (attempted.isEmpty()) break
            if (!isCurrent()) { verificationError = JmxError.Api(401, "账号已变化"); break }
            val page = when (val loaded = loadPage(number)) {
                is JmxResult.Failure -> { verificationError = loaded.error; break }
                is JmxResult.Success -> loaded.value
            }
            if (!isCurrent()) { verificationError = JmxError.Api(401, "账号已变化"); break }
            val before = remaining.size
            remaining += page.ids
            if (page.ids.isEmpty() || page.total?.let { remaining.size >= it } == true) { complete = true; break }
            if (before == remaining.size || remaining.containsAll(attempted)) break
        }
        val confirmed = linkedSetOf<String>()
        for (id in attempted) {
            if (complete && id !in remaining && isCurrent()) confirmed += id
            else failures[id] = verificationError ?: JmxError.EmptyData("未确认此记录已经删除，请刷新后重试")
        }
        FavoriteBatchResult(confirmed, failures)
    }
    return result ?: FavoriteBatchResult(emptySet(), ids.associateWith { failures[it] ?: JmxError.EmptyData("删除核对超时，部分记录可能已删除，请刷新后重试") })
}

internal data class AlbumHistoryIds(val ids: List<String>, val total: Int? = null)
