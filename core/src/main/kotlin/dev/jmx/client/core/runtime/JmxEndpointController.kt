package dev.jmx.client.core.runtime

import dev.jmx.client.core.network.ApiEndpointSelection
import dev.jmx.client.core.result.JmxResult

data class JmxEndpointSelectionReport(
    val selection: ApiEndpointSelection,
    val syncedAvsCookieCount: Int,
    val health: JmxCoreHealth
)

class JmxEndpointController(
    private val core: JmxCore
) {
    fun useManualEndpoint(host: String): JmxResult<JmxEndpointSelectionReport> {
        val url = when (val selected = core.endpointManager.useManualEndpoint(host)) {
            is JmxResult.Success -> selected.value
            is JmxResult.Failure -> return selected
        }
        val synced = when (val result = core.sessionManager.syncAvsCookieToHostsIfPresent(listOf(url.toString()))) {
            is JmxResult.Success -> result.value.size
            is JmxResult.Failure -> return result
        }
        return JmxResult.Success(report(syncedAvsCookieCount = synced))
    }

    fun useAutoSelection(): JmxEndpointSelectionReport {
        core.endpointManager.useAutoSelection()
        return report(syncedAvsCookieCount = 0)
    }

    fun current(): JmxEndpointSelectionReport {
        return report(syncedAvsCookieCount = 0)
    }

    private fun report(syncedAvsCookieCount: Int): JmxEndpointSelectionReport {
        return JmxEndpointSelectionReport(
            selection = core.endpointManager.selection(),
            syncedAvsCookieCount = syncedAvsCookieCount,
            health = core.healthSnapshot()
        )
    }
}
