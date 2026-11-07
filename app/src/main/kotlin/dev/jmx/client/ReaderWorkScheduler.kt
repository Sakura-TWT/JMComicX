package dev.jmx.client

import kotlinx.coroutines.*

/** Pure lifecycle kernel shared by reader prefetch consumers; never starts/cancels jobs under its lock. */
internal class ReaderWorkScheduler<K>(private val scope: CoroutineScope, private val execute: suspend (K) -> Boolean) {
    private val lock = Any()
    private val inFlight = LinkedHashMap<K, Job>()
    private val succeeded = LinkedHashSet<K>()
    private var desired = emptySet<K>()
    private var closed = false
    internal val activeCount: Int get() = synchronized(lock) { inFlight.size }
    internal val successCount: Int get() = synchronized(lock) { succeeded.size }

    fun update(keys: List<K>) {
        val cancelled = mutableListOf<Job>()
        val prepared = mutableListOf<Pair<K, Job>>()
        synchronized(lock) {
            if (closed) return
            desired = keys.toSet()
            succeeded.retainAll(desired)
            inFlight.entries.removeAll { (key, job) ->
                if (key !in desired) { cancelled.add(job); true } else false
            }
            keys.distinct().filter { it !in inFlight && it !in succeeded }.forEach { key ->
                lateinit var task: Job
                task = scope.launch(start = CoroutineStart.LAZY) {
                    val success = try { execute(key) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
                    if (success) synchronized(lock) { if (inFlight[key] === task && key in desired && !closed) succeeded.add(key) }
                }
                inFlight[key] = task
                prepared.add(key to task)
            }
        }
        cancelled.forEach(Job::cancel)
        prepared.forEach { (key, task) ->
            task.invokeOnCompletion { synchronized(lock) { if (inFlight[key] === task) inFlight.remove(key) } }
            task.start()
        }
    }
    fun markSucceeded(key: K) {
        val cancelled = synchronized(lock) {
            if (closed || key !in desired) return
            succeeded.add(key)
            inFlight.remove(key)
        }
        cancelled?.cancel()
    }
    fun clear(close: Boolean = false) {
        val cancelled = synchronized(lock) {
            if (close) closed = true
            desired = emptySet(); succeeded.clear()
            inFlight.values.toList().also { inFlight.clear() }
        }
        cancelled.forEach(Job::cancel)
    }
}
