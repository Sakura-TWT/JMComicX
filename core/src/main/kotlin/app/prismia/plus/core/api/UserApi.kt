package app.prismia.plus.core.api

import app.prismia.plus.core.network.ApiEndpointManager
import app.prismia.plus.core.network.JmxApiClient
import app.prismia.plus.core.network.apiRequest
import app.prismia.plus.core.protocol.ApiRoute
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.session.InMemoryCookieStore
import app.prismia.plus.core.session.SessionManager
import app.prismia.plus.core.session.StoreBackedCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class UserApi(
    private val apiClient: JmxApiClient,
    private val sessionManager: SessionManager,

    private val sessionSyncHosts: () -> List<String> = { emptyList() },

    private val endpointManager: ApiEndpointManager? = null
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

        sessionManager.clear()
        when (
            val committed = sessionManager.commitCookies(
                response.exchange.requestUrl,
                temporaryCookieStore.snapshot()
            )
        ) {
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
        val hosts = buildList {
            add(response.exchange.requestUrl)
            addAll(sessionSyncHosts())
        }.filter { it.isNotBlank() }.distinct()
        if (hosts.isNotEmpty()) {
            when (val replicated = sessionManager.syncAvsCookieToHostsIfPresent(hosts)) {
                is JmxResult.Success -> Unit
                is JmxResult.Failure -> return replicated
            }
        }
        // 记住是哪台机器签发的这个会话。
        // 服务端把 AVS 绑定在签发它的域名上：同一个 AVS 换到别的域名请求 /favorite，
        // 回的是 401「請先登入會員」（已实测四台镜像全部如此）。上面的 AVS 同步
        // 只保证 cookie 送得出去，送到别处照样不认——所以选路必须跟着会话走。
        endpointManager?.let { manager ->
            val loginBase = response.exchange.requestUrl.toHttpUrlOrNull()
                ?.newBuilder()
                ?.encodedPath("/")
                ?.query(null)
                ?.fragment(null)
                ?.build()
                ?.toString()
            if (!loginBase.isNullOrBlank()) {
                manager.useSessionEndpoint(loginBase)
            }
        }
        return JmxResult.Success(
            LoginSession(
                avs = avs,
                profile = root.toUserProfile(),
                raw = root.toRawMap()
            )
        )
    }

    fun logout() {
        sessionManager.clear()
        // 只解开会话亲和，不动用户自己选的线路——那是设置项，不该被退出登录顺手清掉。
        endpointManager?.clearSessionEndpoint()
    }
}
