package app.prismia.plus.core.result

data class NetworkExchange(
    val route: String,
    val requestUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val tokenTimestampSeconds: Long?,
    val bodySample: String
)

sealed interface JmxError {
    val message: String
    val cause: Throwable?
    val retryable: Boolean

    data class Network(
        override val message: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = true
    ) : JmxError

    /**
     * @param retryAfterMillis 服务端 `Retry-After` 响应头换算出的建议等待时长（毫秒），
     *   无该响应头时为 null。限流（429）与临时不可用（503）时由服务端指定重试节奏，
     *   比客户端自己猜的退避更准。
     */
    data class Http(
        val code: Int,
        override val message: String,
        val exchange: NetworkExchange? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = code >= 500 || code == 408 || code == 429,
        val retryAfterMillis: Long? = null
    ) : JmxError

    data class Api(
        val code: Int,
        override val message: String,
        val exchange: NetworkExchange? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError

    data class Decode(
        override val message: String,
        val exchange: NetworkExchange? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError

    data class Schema(
        override val message: String,
        val field: String? = null,
        val exchange: NetworkExchange? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError

    /**
     * code=200 但 data 为 null 的空载荷响应。
     * 全局默认仍是失败；是否解释为"当期无活动"等业务语义由具体接口层判断。
     */
    data class EmptyData(
        override val message: String,
        val exchange: NetworkExchange? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError

    data class Domain(
        override val message: String,
        val endpoint: String? = null,
        override val cause: Throwable? = null,
        override val retryable: Boolean = true
    ) : JmxError

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError
}
