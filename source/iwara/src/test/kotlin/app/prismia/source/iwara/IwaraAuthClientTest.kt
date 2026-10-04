package app.prismia.source.iwara

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class IwaraAuthClientTest {
    @Test
    fun explicitlyExpiredTokenIsRejectedInsteadOfReceivingANewLease() = runBlocking {
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>) = error("unexpected GET")
            override suspend fun post(path: String, body: String, query: Map<String, String>, bearerToken: String?) =
                "{\"accessToken\":\"" + jwt(100) + "\"}"
        }
        val failure = runCatching { IwaraAuthClient(transport) { 200 }.refreshAccessToken("test-refresh") }.exceptionOrNull()
        assertTrue(failure is IwaraAuthException)
        assertEquals("expired_access_token", (failure as IwaraAuthException).remoteCode)
    }

    @Test
    fun loginExtractsRefreshTokenWithoutLeakingCredentialsToTransportContract() = runBlocking {
        var requestedPath = ""
        var requestedBody = ""
        var requestedBearer: String? = null
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String = error("unexpected GET")

            override suspend fun post(
                path: String,
                body: String,
                query: Map<String, String>,
                bearerToken: String?,
            ): String {
                requestedPath = path
                requestedBody = body
                requestedBearer = bearerToken
                return "{\"token\":\"refresh-value\"}"
            }
        }

        val result = IwaraAuthClient(transport).login("person@example.test", "p\\\"ssword")

        assertEquals("refresh-value", result.refreshToken)
        assertEquals("user/login", requestedPath)
        assertTrue(requestedBody.contains("person@example.test"))
        assertTrue(requestedBody.contains("p\\\\\\\"ssword"))
        assertEquals(null, requestedBearer)
    }

    @Test
    fun refreshExtractsJwtExpiryAndUsesRefreshTokenOnlyAsBearer() = runBlocking {
        val token = jwt(exp = 1_900_000_000L)
        var requestedPath = ""
        var requestedBody = ""
        var requestedBearer: String? = null
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String = error("unexpected GET")

            override suspend fun post(
                path: String,
                body: String,
                query: Map<String, String>,
                bearerToken: String?,
            ): String {
                requestedPath = path
                requestedBody = body
                requestedBearer = bearerToken
                return "{\"accessToken\":\"$token\"}"
            }
        }

        val result = IwaraAuthClient(transport).refreshAccessToken("refresh-value")

        assertEquals(token, result.accessToken)
        assertEquals(1_900_000_000L, result.expiresAtEpochSeconds)
        assertEquals("user/token", requestedPath)
        assertEquals("{}", requestedBody)
        assertEquals("refresh-value", requestedBearer)
    }

    @Test
    fun malformedJwtDoesNotBlockRefresh() = runBlocking {
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String = error("unexpected GET")
            override suspend fun post(
                path: String,
                body: String,
                query: Map<String, String>,
                bearerToken: String?,
            ): String = "{\"accessToken\":\"opaque-token\"}"
        }

        val result = IwaraAuthClient(transport) { 1_000L }.refreshAccessToken("refresh")
        assertEquals(1_000L + 15 * 60L, result.expiresAtEpochSeconds)
    }

    @Test
    fun invalidAuthResponseIsStructuredAndContainsNoResponseBody() = runBlocking {
        val transport = object : IwaraTransport {
            override suspend fun get(path: String, query: Map<String, String>): String = error("unexpected GET")
            override suspend fun post(
                path: String,
                body: String,
                query: Map<String, String>,
                bearerToken: String?,
            ): String = "{\"ok\":true,\"password\":\"secret\"}"
        }

        val failure = runCatching { IwaraAuthClient(transport).login("a@b.test", "secret") }.exceptionOrNull()
        requireNotNull(failure)
        assertTrue(failure is IwaraAuthException)
        assertEquals("missing_refresh_token", (failure as IwaraAuthException).sourceFailure.remoteCode)
        assertTrue("secret" !in failure.message.orEmpty())
    }

    private fun jwt(exp: Long): String {
        fun part(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return "${part("{\"alg\":\"none\"}")}.${part("{\"exp\":$exp}")}.signature"
    }
}
