package dev.jmx.client.core.network

import dev.jmx.client.core.protocol.ApiRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApiRequestBuilderTest {
    @Test
    fun builderDropsNullValuesAndKeepsInsertionOrder() {
        val request = apiRequest(ApiRoute.Search) {
            query("search_query", "cat")
            query("skip", null as String?)
            queryAtLeast("page", -2, minimum = 1)
            query("o", "mr")
            form("username", "user")
            form("password", null as String?)
            header("x-client", "jmcomicx")
            header("x-empty", null)
        }

        assertEquals(ApiRoute.Search, request.route)
        assertEquals(
            listOf("search_query" to "cat", "page" to "1", "o" to "mr"),
            request.query.toList()
        )
        assertEquals(listOf("username" to "user"), request.form.toList())
        assertEquals(mapOf("x-client" to "jmcomicx"), request.headers)
    }

    @Test
    fun builderCanDisableSuccessCodeCheck() {
        val request = apiRequest(ApiRoute.Album) {
            requireSuccessCode(false)
        }

        assertFalse(request.requireSuccessCode)
    }
}
