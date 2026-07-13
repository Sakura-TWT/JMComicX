package dev.jmx.v2.foundation.result

sealed interface JmxResult<out T> {
    data class Success<T>(val value: T) : JmxResult<T>
    data class Failure(val error: JmxError) : JmxResult<Nothing>
}

inline fun <T, R> JmxResult<T>.map(transform: (T) -> R): JmxResult<R> {
    return when (this) {
        is JmxResult.Success -> JmxResult.Success(transform(value))
        is JmxResult.Failure -> this
    }
}

inline fun <T, R> JmxResult<T>.flatMap(transform: (T) -> JmxResult<R>): JmxResult<R> {
    return when (this) {
        is JmxResult.Success -> transform(value)
        is JmxResult.Failure -> this
    }
}

fun <T> JmxResult<T>.getOrNull(): T? {
    return when (this) {
        is JmxResult.Success -> value
        is JmxResult.Failure -> null
    }
}

fun <T> JmxResult<T>.errorOrNull(): JmxError? {
    return when (this) {
        is JmxResult.Success -> null
        is JmxResult.Failure -> error
    }
}
