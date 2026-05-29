package dev.jmx.client.store

import dev.jmx.client.data.models.User
import dev.jmx.client.repository.UserRepository
import dev.jmx.client.data.remote.ApiClient
import dev.jmx.client.data.remote.model.LoginResponse
import dev.jmx.client.data.remote.model.NetworkResult
import dev.jmx.client.storage.CookieStorage
import dev.jmx.client.storage.UserStorage
import dev.jmx.client.task.AppInitTask
import dev.jmx.client.task.AppTaskInfo
import dev.jmx.client.ui.models.CommonUIState
import dev.jmx.client.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class UserManager(
    private val userStorage: UserStorage,
    private val cookieStorage: CookieStorage,
    private val userRepository: UserRepository,
    private val apiClient: ApiClient
) : AppInitTask {
    private val _userState = MutableStateFlow(CommonUIState<User>())
    val userState = _userState.asStateFlow()

    val isLoginState = _userState.map { (it.data?.id ?: 0) > 0 }

    private val appTaskInfo = AppTaskInfo(
        taskName = "加载上次退出前保存的用户信息",
        sort = 4,
    )

    fun updateUser(user: User) {
        _userState.update {
            it.copy(
                data = user
            )
        }
        userStorage.set(user)
        JmxDiagnostics.i(
            "Auth",
            "User state persisted",
            metadata = mapOf(
                "user_id" to user.id,
                "username_length" to user.username.orEmpty().length
            )
        )
    }

    fun clearUser() {
        _userState.update {
            it.copy(
                data = User.create()
            )
        }
        apiClient.clearCookie()
        userStorage.remove()
        cookieStorage.remove()
        JmxDiagnostics.i("Auth", "User logged out and local auth state cleared")
    }

    suspend fun autoLogin(username: String, password: String) {
        _userState.update {
            it.copy(
                isLoading = true,
                isError = false,
                errorMsg = ""
            )
        }
        when (val data = userRepository.login(username, password)) {
            is NetworkResult.Error -> {
                _userState.update {
                    it.copy(
                        isError = true,
                        errorMsg = data.message,
                        data = User.create()
                    )
                }
                JmxDiagnostics.e(
                    "Auth",
                    "Auto login failed",
                    metadata = mapOf(
                        "username_length" to username.length,
                        "error_message" to data.message
                    )
                )
            }

            is NetworkResult.Success<LoginResponse> -> {
                val user = data.data.toUser(
                    password = password
                )
                _userState.update {
                    it.copy(
                        data = user
                    )
                }
                JmxDiagnostics.i(
                    "Auth",
                    "Auto login succeeded",
                    metadata = mapOf(
                        "user_id" to user.id,
                        "username_length" to user.username.orEmpty().length
                    )
                )
            }
        }
        _userState.update {
            it.copy(
                isLoading = false
            )
        }
    }

    override suspend fun init() {
        log("用户信息开始初始化")
        log("加载本地用户、cookie、登录信息")
        _userState.update {
            it.copy(
                data = userStorage.get()
            )
        }
        JmxDiagnostics.d(
            "Storage",
            "User state loaded",
            metadata = mapOf(
                "operation" to "read",
                "target" to "user_storage",
                "has_user" to ((_userState.value.data?.id ?: 0) > 0)
            )
        )
        log("已加载本地用户、cookie、登录信息")
        val cachedUser = _userState.value.data ?: User.create()
        val username = cachedUser.username.orEmpty()
        val password = cachedUser.password.orEmpty()
        if (username.isNotEmpty() && password.isNotEmpty()) {
            log("检测到已保存了用户登录信息，开始执行一次用户登录")
            autoLogin(username, password)
        }
        log("用户信息初始化结束")
    }

    override fun getAppTaskInfo(): AppTaskInfo = appTaskInfo
}
