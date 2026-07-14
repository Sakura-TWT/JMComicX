package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.RemoteSetting
import dev.jmx.client.core.chapter.ChapterTemplate
import dev.jmx.client.core.network.ApiEndpoint
import dev.jmx.client.core.network.ApiEndpointProbeResult
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxErrorKind
import dev.jmx.client.core.result.JmxRecoveryAction
import dev.jmx.client.core.result.NetworkExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmxDiagnosticReportTest {
    @Test
    fun factoryBuildsStructuredIssuesWithSanitizedExchange() {
        val exchange = NetworkExchange(
            route = "/login",
            requestUrl = "https://api.test/login?token=secret-token&safe=1",
            statusCode = 403,
            contentType = "application/json",
            tokenTimestampSeconds = 1700566805L,
            bodySample = """{"AVS":"secret-avs","message":"denied"}"""
        )
        val health = sampleHealth()
        val report = JmxDiagnosticReportFactory { 1234L }.create(
            health = health,
            issues = listOf(
                JmxDiagnosticIssue(
                    step = "login",
                    severity = JmxDiagnosticSeverity.Error,
                    message = "password=super-secret failed",
                    error = JmxError.Http(code = 403, message = "forbidden", exchange = exchange)
                )
            )
        )

        assertEquals(1234L, report.generatedAtMillis)
        assertEquals(health, report.health)
        assertEquals(true, report.hasErrors)
        assertEquals(false, report.hasWarnings)
        val issue = report.issues.single()
        assertEquals("password=<redacted> failed", issue.message)
        assertEquals(JmxErrorKind.Http, issue.descriptor!!.kind)
        assertTrue(issue.descriptor.actions.contains(JmxRecoveryAction.Reauthenticate))
        assertTrue(issue.exchange!!.requestUrl.contains("token=<redacted>"))
        assertTrue(!issue.exchange.requestUrl.contains("secret-token"))
        assertTrue(issue.exchange.bodySample.contains("AVS=<redacted>"))
        assertTrue(!issue.exchange.bodySample.contains("secret-avs"))
    }

    @Test
    fun markdownRendererIncludesHealthEndpointIssuesAndNoSecrets() {
        val exchange = NetworkExchange(
            route = "/album",
            requestUrl = "https://api.test/album?id=1&avs=secret-avs",
            statusCode = 200,
            contentType = "application/json",
            tokenTimestampSeconds = 1700566805L,
            bodySample = "token=secret-token data missing"
        )
        val report = JmxDiagnosticReportFactory { 5678L }.create(
            health = sampleHealth(),
            issues = listOf(
                JmxDiagnosticIssue(
                    step = "album_detail",
                    severity = JmxDiagnosticSeverity.Error,
                    message = "data missing",
                    error = JmxError.Schema("missing data", field = "data", exchange = exchange)
                )
            )
        )

        val markdown = JmxDiagnosticMarkdownRenderer().render(report)

        assertTrue(markdown.contains("# JMComicX Core Diagnostic Report"))
        assertTrue(markdown.contains("API version: `2.0.26`"))
        assertTrue(markdown.contains("| `https://api-a.test/` | `true` | 95 | 3 | 0 | 0 | 120 | 140 | `-` |"))
        assertTrue(markdown.contains("### 1. album_detail"))
        assertTrue(markdown.contains("Kind: `Schema`"))
        assertTrue(markdown.contains("Actions: `InspectProtocol,ExportDiagnostics`"))
        assertTrue(markdown.contains("Exchange route: `/album`"))
        assertTrue(markdown.contains("token=<redacted>"))
        assertTrue(markdown.contains("avs=<redacted>"))
        assertTrue(!markdown.contains("secret-token"))
        assertTrue(!markdown.contains("secret-avs"))
    }

    @Test
    fun sanitizerRedactsHeaderStyleAndQueryStyleSecrets() {
        val sanitized = sanitizeDiagnosticText(
            "Cookie: AVS=secret-cookie token=secret-token https://x.test?a=1&password=secret-pass"
        )

        assertTrue(sanitized.contains("Cookie=<redacted>"))
        assertTrue(sanitized.contains("token=<redacted>"))
        assertTrue(sanitized.contains("password=<redacted>"))
        assertTrue(!sanitized.contains("secret-cookie"))
        assertTrue(!sanitized.contains("secret-token"))
        assertTrue(!sanitized.contains("secret-pass"))
    }

    @Test
    fun probeReportConvertsToUnifiedDiagnosticReport() {
        val afterHealth = sampleHealth()
        val probe = JmxCoreProbeReport(
            beforeHealth = sampleHealth(),
            domainRefresh = JmxCoreProbeStep.skipped<List<ApiEndpoint>>("domain_refresh", "refreshDomains=false"),
            endpointProbe = JmxCoreProbeStep.skipped<List<ApiEndpointProbeResult>>("endpoint_probe", "probeEndpoints=false"),
            setting = JmxCoreProbeStep.failure<RemoteSetting>("setting", JmxError.Network("timeout")),
            session = JmxCoreProbeStep.success("session", SessionProbeResult(cookieCount = 1, hasAvs = true)),
            chapterTemplate = JmxCoreProbeStep.skipped<ChapterTemplate>("chapter_template", "chapterTemplate=null"),
            afterHealth = afterHealth
        )

        val report = probe.toDiagnosticReport(generatedAtMillis = 9000L)

        assertEquals(9000L, report.generatedAtMillis)
        assertEquals(afterHealth, report.health)
        assertEquals(true, report.hasWarnings)
        assertEquals(false, report.hasErrors)
        assertEquals(listOf("domain_refresh", "endpoint_probe", "setting", "chapter_template"), probe.issues.map { it.step })
        assertEquals(listOf("domain_refresh", "endpoint_probe", "setting", "chapter_template"), report.issues.map { it.step })
        assertEquals(JmxErrorKind.Network, report.issues.single { it.step == "setting" }.descriptor!!.kind)
    }

    private fun sampleHealth(): JmxCoreHealth {
        return JmxCoreHealth(
            apiVersion = "2.0.26",
            endpoints = listOf(
                EndpointHealth(
                    url = "https://api-a.test/",
                    successCount = 3,
                    failureCount = 0,
                    consecutiveFailureCount = 0,
                    lastSuccessAtMillis = 1000L,
                    lastFailureAtMillis = null,
                    lastLatencyMillis = 120L,
                    averageLatencyMillis = 140L,
                    unavailableUntilMillis = null,
                    healthScore = 95,
                    isAvailable = true,
                    lastFailureMessage = null
                ),
                EndpointHealth(
                    url = "https://api-b.test/",
                    successCount = 0,
                    failureCount = 2,
                    consecutiveFailureCount = 2,
                    lastSuccessAtMillis = null,
                    lastFailureAtMillis = 2000L,
                    lastLatencyMillis = null,
                    averageLatencyMillis = null,
                    unavailableUntilMillis = 3000L,
                    healthScore = 10,
                    isAvailable = false,
                    lastFailureMessage = "timeout"
                )
            ),
            endpointSelection = EndpointSelectionHealth(mode = "auto", manualUrl = null),
            cookieCount = 1,
            domainServerUrls = listOf("https://domain.test"),
            downloadConcurrency = 4
        )
    }
}
