package dev.jmx.client.core.runtime

data class JmxCoreHealth(
    val apiVersion: String,
    val endpoints: List<EndpointHealth>,
    val cookieCount: Int,
    val domainServerUrls: List<String>,
    val downloadConcurrency: Int
)

data class EndpointHealth(
    val url: String,
    val successCount: Int,
    val failureCount: Int,
    val consecutiveFailureCount: Int,
    val lastSuccessAtMillis: Long?,
    val lastFailureAtMillis: Long?,
    val lastLatencyMillis: Long?,
    val averageLatencyMillis: Long?,
    val unavailableUntilMillis: Long?,
    val healthScore: Int,
    val isAvailable: Boolean,
    val lastFailureMessage: String?
)
