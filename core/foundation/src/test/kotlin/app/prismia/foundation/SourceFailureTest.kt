package app.prismia.foundation

import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFailureTest {
    @Test
    fun mapsTlsAndNetworkFailuresWithoutMakingTlsRetryable() {
        val tls = SSLHandshakeException("certificate rejected")
            .toSourceFailure(ContentSource.IWARA, "detail")
        val network = UnknownHostException("api.iwara.tv")
            .toSourceFailure(ContentSource.IWARA, "browse")

        assertEquals(SourceErrorCategory.TLS, tls.category)
        assertFalse(tls.retryable)
        assertEquals(SourceErrorCategory.NETWORK, network.category)
        assertFalse(network.retryable)
        assertTrue(network.message.contains("api.iwara.tv"))
    }

    @Test
    fun carrierOperationIsOverriddenAtTheBoundary() {
        val failure = SourceFailure(
            source = ContentSource.ORENO3D,
            operation = "transport",
            category = SourceErrorCategory.HTTP,
            httpStatus = 503,
            retryable = true,
            message = "busy",
        )
        val mapped = SourceFailureException(failure)
            .toSourceFailure(ContentSource.ORENO3D, "search")

        assertEquals("search", mapped.operation)
        assertEquals(503, mapped.httpStatus)
        assertTrue(mapped.retryable)
    }
}
