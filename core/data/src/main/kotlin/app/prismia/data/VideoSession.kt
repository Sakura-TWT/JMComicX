package app.prismia.data

import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailureCarrier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * Credentials owned by the video domain. They intentionally do not share the
 * JM cookie store: Iwara access tokens have a different lifetime and refresh
 * policy from the comic protocol session.
 */
data class VideoSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val accessTokenExpiresAtEpochSeconds: Long? = null,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken == null || refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        require(accessTokenExpiresAtEpochSeconds == null || accessTokenExpiresAtEpochSeconds > 0L) {
            "accessTokenExpiresAtEpochSeconds must be positive"
        }
    }

    fun isUsable(nowEpochSeconds: Long): Boolean {
        require(nowEpochSeconds >= 0) { "current time must be non-negative" }
        val expiry = accessTokenExpiresAtEpochSeconds ?: return true
        return expiry > nowEpochSeconds && expiry - nowEpochSeconds > EXPIRY_SAFETY_WINDOW_SECONDS
    }

    override fun toString(): String = "VideoSession(credentials=redacted, expiresAt=$accessTokenExpiresAtEpochSeconds)"

    companion object {
        private const val EXPIRY_SAFETY_WINDOW_SECONDS = 30L
    }
}

interface VideoSessionStore {
    suspend fun load(): VideoSession?
    suspend fun save(session: VideoSession)
    suspend fun clear()
}

fun interface VideoSessionRefresher {
    suspend fun refresh(previous: VideoSession?): VideoSession
}

/**
 * Versioned, bounded binary representation used before encryption.
 *
 * The codec intentionally has no Android dependency so it can be tested on the
 * JVM and reused by a database-backed store later. Encryption belongs to the
 * platform store; this class only defines a stable payload contract.
 */
object VideoSessionCodec {
    private const val MAGIC = 0x50565331 // "PVS1"
    private const val FORMAT_VERSION = 1
    private const val NO_EXPIRY = Long.MIN_VALUE
    private const val MAX_TOKEN_BYTES = 16 * 1024
    private const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun encode(session: VideoSession): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeByte(FORMAT_VERSION)
            writeRequired(data, session.accessToken, "accessToken")
            writeNullable(data, session.refreshToken, "refreshToken")
            data.writeLong(session.accessTokenExpiresAtEpochSeconds ?: NO_EXPIRY)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "encoded session is too large" }
        }
    }

    fun decode(payload: ByteArray): VideoSession {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "encoded session is too large" }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { data ->
                if (data.readInt() != MAGIC) throw VideoSessionFormatException("invalid session payload magic")
                if (data.readUnsignedByte() != FORMAT_VERSION) {
                    throw VideoSessionFormatException("unsupported session payload version")
                }
                val access = readRequired(data, "accessToken")
                val refresh = readNullable(data, "refreshToken")
                val expiry = data.readLong().takeUnless { it == NO_EXPIRY }
                if (data.available() != 0) throw VideoSessionFormatException("trailing session payload bytes")
                return VideoSession(access, refresh, expiry)
            }
        } catch (failure: VideoSessionFormatException) {
            throw failure
        } catch (failure: EOFException) {
            throw VideoSessionFormatException("truncated session payload", failure)
        } catch (failure: IllegalArgumentException) {
            throw VideoSessionFormatException("invalid session payload", failure)
        }
    }

    private fun writeRequired(data: DataOutputStream, value: String, field: String) {
        require(value.isNotBlank()) { "$field must not be blank" }
        writeString(data, value, field)
    }

    private fun writeNullable(data: DataOutputStream, value: String?, field: String) {
        if (value == null) {
            data.writeInt(-1)
        } else {
            require(value.isNotBlank()) { "$field must not be blank" }
            writeString(data, value, field)
        }
    }

    private fun writeString(data: DataOutputStream, value: String, field: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty()) { "$field must not be empty" }
        require(bytes.size <= MAX_TOKEN_BYTES) { "$field is too large" }
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readRequired(data: DataInputStream, field: String): String =
        readString(data, field) ?: throw VideoSessionFormatException("missing $field")

    private fun readNullable(data: DataInputStream, field: String): String? = readString(data, field)

    private fun readString(data: DataInputStream, field: String): String? {
        val length = data.readInt()
        if (length == -1) return null
        if (length <= 0 || length > MAX_TOKEN_BYTES || length > data.available()) {
            throw VideoSessionFormatException("invalid $field length")
        }
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8).takeIf(String::isNotBlank)
            ?: throw VideoSessionFormatException("blank $field")
    }
}

class VideoSessionFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** In-memory coordinator used by transports; persistence stays behind [VideoSessionStore]. */
class VideoSessionManager(
    private val store: VideoSessionStore,
    private val refresher: VideoSessionRefresher? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private val mutex = Mutex()
    @Volatile
    private var cached: VideoSession? = null

    suspend fun load(): VideoSession? = mutex.withLock {
        store.load().also { cached = it }
    }

    fun currentAccessToken(nowEpochSeconds: Long = this.nowEpochSeconds()): String? =
        cached?.takeIf { it.isUsable(nowEpochSeconds) }?.accessToken

    /**
     * Single-flight access-token lookup. Expired sessions are refreshed while
     * holding the mutex so concurrent callers cannot issue duplicate refreshes.
     */
    suspend fun accessTokenOrRefresh(forceRefresh: Boolean = false): String? = token(forceRefresh, null)

    /** Concurrent 401s for the same token reuse an already-renewed session. */
    suspend fun accessTokenAfterRejection(rejectedToken: String): String? = token(true, rejectedToken)

    private suspend fun token(forceRefresh: Boolean, rejectedToken: String?): String? = mutex.withLock {
        val existing = cached ?: store.load().also { cached = it }
            ?: return@withLock null // Anonymous access must never attempt refresh.
        val alreadyRenewed = rejectedToken != null && existing.accessToken != rejectedToken
        if ((!forceRefresh || alreadyRenewed) && existing.isUsable(nowEpochSeconds())) {
            return@withLock existing.accessToken
        }
        val refresh = refresher ?: return@withLock null
        val renewed = try {
            refresh.refresh(existing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val diagnostic = (failure as? SourceFailureCarrier)?.sourceFailure
            if (diagnostic?.category == SourceErrorCategory.AUTHENTICATION) {
                // A rejected refresh token is unrecoverable. Clear it before
                // surfacing the structured failure so subsequent requests do
                // not repeatedly send a known-invalid credential.
                try {
                    store.clear()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (clearFailure: Exception) {
                    failure.addSuppressed(clearFailure)
                } finally {
                    cached = null
                }
            }
            throw failure
        }
        check(renewed.isUsable(nowEpochSeconds())) { "refreshed video session is already expired or expiring" }
        store.save(renewed)
        cached = renewed
        renewed.accessToken
    }

    suspend fun save(session: VideoSession) {
        mutex.withLock {
            store.save(session)
            cached = session
        }
    }

    suspend fun clear() {
        mutex.withLock {
            store.clear()
            cached = null
        }
    }
}

class InMemoryVideoSessionStore(initial: VideoSession? = null) : VideoSessionStore {
    private var value: VideoSession? = initial

    override suspend fun load(): VideoSession? = value

    override suspend fun save(session: VideoSession) {
        value = session
    }

    override suspend fun clear() {
        value = null
    }
}
