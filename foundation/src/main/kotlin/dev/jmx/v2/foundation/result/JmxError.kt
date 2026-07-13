package dev.jmx.v2.foundation.result

sealed interface JmxError {
    val message: String
    val cause: Throwable?
    val retryable: Boolean

    data class Network(
        override val message: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = true
    ) : JmxError

    data class Http(
        val code: Int,
        override val message: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = code >= 500 || code == 408 || code == 429
    ) : JmxError

    data class Api(
        val code: Int,
        override val message: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError

    data class Decode(
        override val message: String,
        override val cause: Throwable? = null,
        override val retryable: Boolean = false
    ) : JmxError

    data class Schema(
        override val message: String,
        val field: String? = null,
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
