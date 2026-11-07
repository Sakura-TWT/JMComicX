package dev.jmx.client

import kotlinx.coroutines.Job

/** Same-query retries are distinct generations; obsolete pagination cannot append to a fresh page. */
internal class SearchRequestEpoch {
    private val lock = Any()
    private var current = 0L
    private var pagination: Job? = null
    fun advance(): Long {
        val (generation, cancelled) = synchronized(lock) { ++current to pagination.also { pagination = null } }
        cancelled?.cancel()
        return generation
    }
    fun accepts(generation: Long): Boolean = synchronized(lock) { generation == current }
    fun register(generation: Long, task: Job): Boolean {
        val previous: Job?
        val accepted: Boolean
        synchronized(lock) {
            accepted = generation == current && !task.isCancelled && pagination?.isCompleted != false
            previous = if (accepted) pagination.also { pagination = task } else null
        }
        previous?.cancel()
        if (!accepted) task.cancel()
        return accepted
    }
    fun finished(task: Job) { synchronized(lock) { if (pagination === task) pagination = null } }
}

internal fun matchingSearchHistory(history: List<String>, query: String): List<String> {
    val term = query.trim()
    return if (term.isEmpty()) history else history.filter { it.contains(term, ignoreCase = true) }
}
