package app.prismia.source.iwara

import app.prismia.foundation.ContentSource
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** Result of the email/password exchange. Iwara returns a refresh token here. */
data class IwaraLoginResult(
    val refreshToken: String,
)

/** Short lived access token returned by the refresh endpoint. */
data class IwaraAccessTokenResult(
    val accessToken: String,
    val expiresAtEpochSeconds: Long?,
)

/**
 * Explicit authentication boundary for Iwara. Credentials are serialized only
 * for the request body and are never included in exceptions or diagnostics.
 */
class IwaraAuthClient(
    private val transport: IwaraTransport,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    suspend fun login(email: String, password: String): IwaraLoginResult {
        require(email.isNotBlank()) { "email must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }
        val response = transport.post(
            path = "user/login",
            body = "{\"email\":${quoteJson(email)},\"password\":${quoteJson(password)}}",
        )
        val root = parseResponse(response, operation = "auth.login")
        val refreshToken = root.valueString("token")
            ?: root.valueString("refreshToken")
            ?: throw IwaraAuthException(
                operation = "auth.login",
                remoteCode = "missing_refresh_token",
                message = "Iwara login response did not contain a refresh token",
            )
        return IwaraLoginResult(refreshToken)
    }

    suspend fun refreshAccessToken(refreshToken: String): IwaraAccessTokenResult {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        val response = transport.post(
            path = "user/token",
            body = "{}",
            bearerToken = refreshToken,
        )
        val root = parseResponse(response, operation = "auth.refresh")
        val accessToken = root.valueString("accessToken")
            ?: root.valueString("access_token")
            ?: throw IwaraAuthException(
                operation = "auth.refresh",
                remoteCode = "missing_access_token",
                message = "Iwara refresh response did not contain an access token",
            )
        val responseExpiry = root.valueLong("expiresAtEpochSeconds")
            ?: root.valueLong("expiresAt")?.toEpochSeconds()
            ?: root.valueLong("expires")?.toEpochSeconds()
        val parsedExpiry = responseExpiry ?: parseJwtExpiry(accessToken)
        val now = nowEpochSeconds()
        return IwaraAccessTokenResult(
            accessToken = accessToken,
            // JWT parsing is informational only. The server remains the
            // authority; malformed/opaque tokens get a bounded local lease so
            // they cannot be treated as valid forever by the session manager.
            expiresAtEpochSeconds = (parsedExpiry ?: now + DEFAULT_ACCESS_TOKEN_TTL_SECONDS)
                .takeIf { it > now }
                ?: now + DEFAULT_ACCESS_TOKEN_TTL_SECONDS,
        )
    }

    private fun parseResponse(raw: String, operation: String): JsonObject {
        return try {
            val root = JsonParser.parseString(raw).takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: throw IwaraAuthException(
                    operation = operation,
                    remoteCode = "invalid_json_shape",
                    message = "Iwara authentication response was not a JSON object",
                )
            root.obj("data") ?: root
        } catch (failure: IwaraAuthException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw IwaraAuthException(
                operation = operation,
                remoteCode = "invalid_json",
                message = "Iwara authentication response was invalid JSON",
                cause = failure,
            )
        }
    }

    private fun parseJwtExpiry(token: String): Long? {
        val payload = token.split('.')
            .takeIf { it.size == 3 }
            ?.getOrNull(1)
            ?: return null
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(padBase64(payload))
            val json = JsonParser.parseString(String(decoded, StandardCharsets.UTF_8))
                .takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: return@runCatching null
            json.valueLong("exp")?.toEpochSeconds()
        }.getOrNull()
    }

    private fun quoteJson(value: String): String =
        // The authentication module has no mutable Gson instance and this
        // small encoder covers the JSON string grammar without exposing the
        // credential values anywhere else.
        buildString(value.length + 2) {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    in '\u0000'..'\u001F' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }
}

private const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 15 * 60L

/** Authentication failures contain only safe protocol metadata. */
class IwaraAuthException(
    val operation: String,
    val remoteCode: String,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() = SourceFailure(
            source = ContentSource.IWARA,
            operation = operation,
            category = SourceErrorCategory.AUTHENTICATION,
            remoteCode = remoteCode,
            retryable = false,
            message = message,
            cause = cause,
        )
}

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.valueString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.takeIf(String::isNotBlank)

private fun JsonObject.valueLong(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
        ?.let { runCatching { it.asLong }.getOrNull() }

private fun Long.toEpochSeconds(): Long = if (this >= 1_000_000_000_000L) this / 1000 else this

private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
