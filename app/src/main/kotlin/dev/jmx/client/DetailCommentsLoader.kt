package dev.jmx.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object DetailCommentsLoader {
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)

    suspend fun <T> load(block: suspend () -> T): T = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        val result = block()
        currentCoroutineContext().ensureActive()
        result
    }
}
