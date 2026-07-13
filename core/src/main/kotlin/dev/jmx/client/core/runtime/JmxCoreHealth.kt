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
    val failureCount: Int,
    val lastFailureMessage: String?
)
