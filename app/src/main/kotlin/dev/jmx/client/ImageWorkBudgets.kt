package dev.jmx.client

import dev.jmx.client.core.runtime.WeightedResourceGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore

/** Process-wide, not per-screen: speculative images cannot fill the host's five asynchronous slots. */
internal object ImageWorkBudgets {
    private const val MIB = 1024L * 1024L
    const val MAX_ENCODED_BYTES = 16L * MIB
    val prefetch = Semaphore(2)
    val decoderSlots = Semaphore(2)
    val decodeDispatcher = Dispatchers.Default.limitedParallelism(2)
    val decodeMemory = WeightedResourceGate((Runtime.getRuntime().maxMemory() / 4).coerceIn(32L * MIB, 128L * MIB))
}

/** Conservative two-ARGB-bitmap + encoded + scratch estimate, before any pixel allocation. */
internal fun readerDecodeReservation(width: Int, height: Int, sample: Int, encodedBytes: Int): Long {
    require(width in 1..131_072 && height in 1..131_072 && sample > 0 && encodedBytes >= 0) { "图片尺寸无效" }
    val decodedWidth = (width.toLong() + sample - 1) / sample
    val decodedHeight = (height.toLong() + sample - 1) / sample
    return decodedWidth * decodedHeight * 8L + encodedBytes + 8L * 1024 * 1024
}
