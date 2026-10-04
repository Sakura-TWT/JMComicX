package app.prismia.plus.core.network

import app.prismia.plus.core.protocol.ApiRoute
import org.junit.Assert.*
import org.junit.Test

class RequestIdentityTest {
    @Test fun parameterDelimitersCannotCollideWithAnotherParameter() {
        val a = ApiRequest(ApiRoute.Search, query = mapOf("a" to "x&b=y"))
        val b = ApiRequest(ApiRoute.Search, query = mapOf("a" to "x", "b" to "y"))
        assertNotEquals(a.dedupKey(), b.dedupKey())
        assertNotEquals(a.copy(query = emptyMap(), form = a.query).dedupKey(), b.copy(query = emptyMap(), form = b.query).dedupKey())
    }

    @Test fun orderAndHeaderCasingDoNotChangeTheSameRequestIdentity() {
        val a = ApiRequest(ApiRoute.Search, query = linkedMapOf("a" to "1", "b" to "2"), headers = mapOf("X-Test" to "a"))
        val b = a.copy(query = linkedMapOf("b" to "2", "a" to "1", "unused" to null), headers = mapOf("x-test" to "a"))
        assertEquals(a.dedupKey(), b.dedupKey())
    }

    @Test fun headersSuccessPolicyAndExcludedEndpointRemainDistinct() {
        val request = ApiRequest(ApiRoute.Search)
        assertNotEquals(request.dedupKey(), request.copy(headers = mapOf("Authorization" to "secret-value")).dedupKey())
        assertNotEquals(request.dedupKey(), request.copy(requireSuccessCode = false).dedupKey())
        assertNotEquals(request.dedupKey(), request.copy(excludedEndpointUrl = "https://other.test").dedupKey())
        assertFalse(request.copy(headers = mapOf("Authorization" to "secret-value")).dedupKey().contains("secret-value"))
    }
}
