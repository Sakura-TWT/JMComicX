package dev.jmx.client

import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All mutations are serialized by the account authentication mutex. */
internal class SessionRecoveryGuard(
    private val emptyCooldownMillis: Long = 60_000L,
    private val failureCooldownMillis: Long = 30_000L,
) {
    @Volatile
    private var emptyGeneration = -1L
    @Volatile
    private var emptyAt = 0L
    private var failedGeneration = -1L
    private var failedAt = 0L
    private var failure: JmxResult.Failure? = null

    fun emptyAlreadyVerified(generation: Long, now: Long): Boolean =
        emptyGeneration == generation && now >= emptyAt && now - emptyAt < emptyCooldownMillis
    fun verifyEmpty(generation: Long, now: Long) { emptyAt = now; emptyGeneration = generation }
    fun recentFailure(generation: Long, now: Long): JmxResult.Failure? =
        failure?.takeIf { failedGeneration == generation && now >= failedAt && now - failedAt < failureCooldownMillis }
    fun failed(generation: Long, now: Long, result: JmxResult.Failure) { failedGeneration = generation; failedAt = now; failure = result }
    fun succeeded() { failure = null }
}

internal sealed interface RecoveryLogin {
    data object Recovered : RecoveryLogin
    data object Skipped : RecoveryLogin
    data class Failed(val result: JmxResult.Failure) : RecoveryLogin
}

/** Shared by production and synthetic concurrency tests; no credentials enter this layer. */
internal suspend fun <T> boundedSessionRequest(
    generation: () -> Long,
    mutex: Mutex,
    guard: SessionRecoveryGuard,
    recover: suspend (expectedGeneration: Long) -> RecoveryLogin,
    treatEmptyDataAsSessionLoss: Boolean = false,
    allowRecovery: () -> Boolean = { true },
    clock: () -> Long = System::currentTimeMillis,
    block: suspend () -> JmxResult<T>,
): JmxResult<T> {
    val originalGeneration = generation()
    val first = block()
    if (first !is JmxResult.Failure) return first
    val empty = treatEmptyDataAsSessionLoss && first.error is JmxError.EmptyData
    if (!first.error.requiresSessionRecovery() && !empty) return first
    if (empty && guard.emptyAlreadyVerified(originalGeneration, clock())) return first
    val failed = mutex.withLock {
        if (originalGeneration != generation()) return@withLock null
        if (empty && guard.emptyAlreadyVerified(originalGeneration, clock())) return@withLock first
        guard.recentFailure(originalGeneration, clock())?.let { return@withLock it }
        if (!allowRecovery()) return@withLock first
        when (val login = recover(originalGeneration)) {
            RecoveryLogin.Recovered -> { guard.succeeded(); null }
            RecoveryLogin.Skipped -> first
            is RecoveryLogin.Failed -> { guard.failed(originalGeneration, clock(), login.result); login.result }
        }
    }
    if (failed != null) return failed
    // Release the authentication mutex before ordinary HTTP readers retry in parallel.
    val retriedGeneration = generation()
    val retried = block()
    if (empty && retried is JmxResult.Failure && retried.error is JmxError.EmptyData) {
        mutex.withLock { if (retriedGeneration == generation()) guard.verifyEmpty(retriedGeneration, clock()) }
    }
    return retried
}
