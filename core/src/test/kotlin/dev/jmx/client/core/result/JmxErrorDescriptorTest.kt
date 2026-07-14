package dev.jmx.client.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmxErrorDescriptorTest {
    @Test
    fun httpForbiddenRecommendsSessionEndpointAndProtocolChecks() {
        val exchange = NetworkExchange(
            route = "/album",
            requestUrl = "https://api.test/album?id=1",
            statusCode = 403,
            contentType = "text/html",
            tokenTimestampSeconds = 1700566805L,
            bodySample = "forbidden"
        )

        val descriptor = JmxError.Http(
            code = 403,
            message = "forbidden",
            exchange = exchange
        ).describe()

        assertEquals(JmxErrorKind.Http, descriptor.kind)
        assertEquals(JmxErrorImpact.Error, descriptor.impact)
        assertEquals(403, descriptor.httpCode)
        assertEquals(exchange, descriptor.exchange)
        assertTrue(descriptor.actions.contains(JmxRecoveryAction.Reauthenticate))
        assertTrue(descriptor.actions.contains(JmxRecoveryAction.SwitchEndpoint))
        assertTrue(descriptor.actions.contains(JmxRecoveryAction.InspectProtocol))
    }

    @Test
    fun retryableHttpFailureRecommendsRetryLaterAndEndpointSwitch() {
        val descriptor = JmxError.Http(code = 502, message = "bad gateway").describe()

        assertEquals(JmxErrorKind.Http, descriptor.kind)
        assertEquals(JmxErrorImpact.Warning, descriptor.impact)
        assertEquals(true, descriptor.retryable)
        assertEquals(
            listOf(
                JmxRecoveryAction.RetryLater,
                JmxRecoveryAction.SwitchEndpoint,
                JmxRecoveryAction.ExportDiagnostics
            ),
            descriptor.actions
        )
    }

    @Test
    fun remoteSchemaFailureIsProtocolInspectionWhileLocalSchemaFailureIsCallerInput() {
        val exchange = NetworkExchange(
            route = "/search",
            requestUrl = "https://api.test/search",
            statusCode = 200,
            contentType = "application/json",
            tokenTimestampSeconds = 1700566805L,
            bodySample = "{}"
        )

        val remote = JmxError.Schema("missing data", field = "data", exchange = exchange).describe()
        val local = JmxError.Schema("albumId is blank", field = "albumId").describe()

        assertEquals(listOf(JmxRecoveryAction.InspectProtocol, JmxRecoveryAction.ExportDiagnostics), remote.actions)
        assertEquals(listOf(JmxRecoveryAction.FixCallerInput, JmxRecoveryAction.ExportDiagnostics), local.actions)
        assertEquals(exchange, remote.exchange)
        assertEquals(null, local.exchange)
    }

    @Test
    fun exchangeOrNullReturnsOnlyErrorsWithNetworkExchange() {
        val exchange = NetworkExchange(
            route = "/setting",
            requestUrl = "https://api.test/setting",
            statusCode = 200,
            contentType = "application/json",
            tokenTimestampSeconds = 1700566805L,
            bodySample = "{}"
        )

        assertEquals(exchange, JmxError.Api(403, "denied", exchange).exchangeOrNull())
        assertEquals(exchange, JmxError.Decode("bad data", exchange).exchangeOrNull())
        assertEquals(exchange, JmxError.Schema("bad schema", exchange = exchange).exchangeOrNull())
        assertEquals(null, JmxError.Network("timeout").exchangeOrNull())
        assertEquals(null, JmxError.Domain("bad host").exchangeOrNull())
    }
}
