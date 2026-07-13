package dev.jmx.v2.foundation.api

import dev.jmx.v2.foundation.network.ApiEndpointManager
import dev.jmx.v2.foundation.network.ApiRequest
import dev.jmx.v2.foundation.network.JmxApiClient
import dev.jmx.v2.foundation.protocol.ApiRoute
import dev.jmx.v2.foundation.result.JmxError
import dev.jmx.v2.foundation.result.JmxResult
import dev.jmx.v2.foundation.session.SessionManager

class UserApi(
    private val apiClient: JmxApiClient,
    private val endpointManager: ApiEndpointManager,
    private val sessionManager: SessionManager
) {
    suspend fun login(username: String, password: String): JmxResult<LoginSession> {
        if (username.isBlank() || password.isBlank()) {
            return JmxResult.Failure(JmxError.Schema("用户名或密码为空"))
        }
        val data = when (
            val result = apiClient.requestJson(
                ApiRequest(
                    route = ApiRoute.Login,
                    form = mapOf("username" to username, "password" to password)
                )
            )
        ) {
            is JmxResult.Success -> result.value
            is JmxResult.Failure -> return result
        }
        val root = data.asObjectOrNull()
            ?: return JmxResult.Failure(JmxError.Schema("login data 不是对象"))
        val avs = root.stringOrNull("s", "AVS", "avs")
        if (!avs.isNullOrBlank()) {
            val endpoint = when (val current = endpointManager.current()) {
                is JmxResult.Success -> current.value.toString()
                is JmxResult.Failure -> return current
            }
            when (val installed = sessionManager.installAvsCookie(endpoint, avs)) {
                is JmxResult.Success -> Unit
                is JmxResult.Failure -> return installed
            }
        }
        return JmxResult.Success(LoginSession(avs = avs, raw = root.toRawMap()))
    }
}
