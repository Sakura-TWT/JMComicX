package app.prismia.foundation

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Categories shared by all remote source adapters. */
enum class SourceErrorCategory {
    NETWORK,
    TLS,
    HTTP,
    CLOUDFLARE,
    AUTHENTICATION,
    RATE_LIMITED,
    REMOTE,
    PROTOCOL,
    JSON,
    PLAYBACK,
    VALIDATION,
    UNKNOWN,
}

/** A structured, source-neutral failure suitable for logs and diagnostics. */
data class SourceFailure(
    val source: ContentSource,
    val operation: String,
    val category: SourceErrorCategory,
    val httpStatus: Int? = null,
    val remoteCode: String? = null,
    val retryable: Boolean = false,
    val message: String,
    val cause: Throwable? = null,
)

/** Implemented by source-specific exceptions that already know their protocol details. */
interface SourceFailureCarrier {
    val sourceFailure: SourceFailure
}

/** Exception form used when a source has no more specific public exception type. */
class SourceFailureException(
    override val sourceFailure: SourceFailure,
) : IllegalStateException(sourceFailure.message, sourceFailure.cause), SourceFailureCarrier

/** Maps any adapter failure without losing source, operation, or retry semantics. */
fun Throwable.toSourceFailure(source: ContentSource, operation: String): SourceFailure =
    (this as? SourceFailureCarrier)?.sourceFailure?.copy(operation = operation)
        ?: when (this) {
            is SSLException -> SourceFailure(
                source = source,
                operation = operation,
                category = SourceErrorCategory.TLS,
                retryable = false,
                message = message ?: "TLS handshake failed",
                cause = this,
            )
            is SocketTimeoutException -> SourceFailure(
                source = source,
                operation = operation,
                category = SourceErrorCategory.NETWORK,
                retryable = true,
                message = message ?: "network request timed out",
                cause = this,
            )
            is UnknownHostException -> SourceFailure(
                source = source,
                operation = operation,
                category = SourceErrorCategory.NETWORK,
                retryable = false,
                message = message ?: "source host could not be resolved",
                cause = this,
            )
            is IOException -> SourceFailure(
                source = source,
                operation = operation,
                category = SourceErrorCategory.NETWORK,
                retryable = true,
                message = message ?: "network request failed",
                cause = this,
            )
            else -> SourceFailure(
                source = source,
                operation = operation,
                category = SourceErrorCategory.UNKNOWN,
                retryable = false,
                message = message ?: this::class.simpleName.orEmpty(),
                cause = this,
            )
        }
