package dev.jmx.client.core.api

import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.apiRequest
import dev.jmx.client.core.protocol.ApiRoute
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.session.InMemoryCookieStore
import dev.jmx.client.core.session.SessionManager
import dev.jmx.client.core.session.StoreBackedCookieJar

class UserApi(
    private val apiClient: JmxApiClient,
    private val sessionManager: SessionManager
) {
    suspend fun login(username: String, password: String): JmxResult<LoginSession> {
        if (username.isBlank() || password.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("用户名或密码为空"))
        }
        val temporaryCookieStore = InMemoryCookieStore()
        val loginClient = apiClient.withCookieJar(StoreBackedCookieJar(temporaryCookieStore))
        val response = when (
            val result = loginClient.requestJsonResponse(
                apiRequest(ApiRoute.Login) {
                    form("username", username)
                    form("password", password)
                }
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = response.data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("login data 不是对象"))
        when (val committed = sessionManager.commitCookies(response.exchange.requestUrl, temporaryCookieStore.snapshot())) {
            is JmxResult.Success -> Unit
            is JmxResult.Failure -> return committed
        }
        val avs = root.stringOrNull("s", "AVS", "avs")
        if (!avs.isNullOrBlank()) {
            when (val installed = sessionManager.installAvsCookie(response.exchange.requestUrl, avs)) {
                is JmxResult.Success -> Unit
                is JmxResult.Failure -> return installed
            }
        }
        return JmxResult.Success(LoginSession(avs = avs, raw = root.toRawMap()))
    }
}
