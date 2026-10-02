package app.prismia.plus.core.network

import app.prismia.plus.core.result.JmxError
import kotlin.random.Random

/**
 * @param delayMillis 本次重试前应等待的时长。0 表示立即重试。
 */
data class RetryDecision(
    val shouldRetry: Boolean,
    val shouldFailover: Boolean,
    val delayMillis: Long = 0L
)

interface RetryPolicy {
    val maxAttempts: Int

    fun decide(error: JmxError, attemptIndex: Int): RetryDecision
}

/**
 * 重试节奏：
 * - **换端点重试立即执行**（[RetryDecision.delayMillis] = 0）。失败的是这台主机，换一台就该马上试，
 *   等待只是在给用户增加白屏时间。
 * - **同端点重试才退避**。原地立刻重发通常会撞上同一个瞬时故障；而且在服务端限流时，
 *   无退避的重试会放大压力（多个客户端同时进入同步重试，形成尖峰）。
 *   退避带满抖动（`random(0, base * 2^n)`），避免全体客户端在同一时刻齐步重试。
 * - **服务端给了 `Retry-After` 就听它的**（见 [JmxError.Http.retryAfterMillis]），
 *   但夹在 [maxRetryAfterMillis] 以内：接口偶尔会返回以小时计的值，照做等于挂死这次请求。
 */
class DefaultRetryPolicy(
    override val maxAttempts: Int = 3,
    private val failoverOnRetryable: Boolean = true,
    private val baseBackoffMillis: Long = 250L,
    private val maxBackoffMillis: Long = 2_000L,
    private val maxRetryAfterMillis: Long = 5_000L,
    private val random: Random = Random.Default
) : RetryPolicy {
    override fun decide(error: JmxError, attemptIndex: Int): RetryDecision {
        val hasNextAttempt = attemptIndex + 1 < maxAttempts.coerceAtLeast(1)
        val shouldRetry = hasNextAttempt && error.retryable
        val shouldFailover = shouldRetry && failoverOnRetryable && error.shouldTryNextEndpoint()
        return RetryDecision(
            shouldRetry = shouldRetry,
            shouldFailover = shouldFailover,
            delayMillis = if (!shouldRetry) 0L else retryDelayMillis(error, attemptIndex, shouldFailover)
        )
    }

    private fun retryDelayMillis(error: JmxError, attemptIndex: Int, failingOver: Boolean): Long {
        val serverHint = (error as? JmxError.Http)?.retryAfterMillis
        if (serverHint != null && serverHint > 0L) return serverHint.coerceAtMost(maxRetryAfterMillis)
        // 换端点等于换了一台机器，没有理由等待。
        if (failingOver) return 0L
        val ceiling = (baseBackoffMillis shl attemptIndex.coerceIn(0, 16)).coerceAtMost(maxBackoffMillis)
        // 满抖动：取 [0, ceiling) 而不是 ceiling 本身，避免客户端齐步重试。
        return random.nextLong(ceiling.coerceAtLeast(1L))
    }

    private fun JmxError.shouldTryNextEndpoint(): Boolean {
        return when (this) {
            is JmxError.Network,
            is JmxError.Domain -> true

            is JmxError.Http -> code >= 500 || code == 408 || code == 429 || code == 403

            is JmxError.Decode -> retryable
            is JmxError.Api,
            is JmxError.Schema,
            is JmxError.EmptyData,
            is JmxError.Unknown -> false
        }
    }
}
