package dev.jmx.client.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import dev.jmx.client.data.models.CollectAlbumOrderFilter
import dev.jmx.client.data.models.SignInData
import dev.jmx.client.repository.UserRepository
import dev.jmx.client.data.remote.model.LoginResponse
import dev.jmx.client.data.remote.model.NetworkResult
import dev.jmx.client.data.remote.model.SignInDataResponse
import dev.jmx.client.data.remote.model.SignInResponse
import dev.jmx.client.store.ToastManager
import dev.jmx.client.store.UserManager
import dev.jmx.client.store.JmxDiagnostics
import dev.jmx.client.ui.models.CommonUIState
import dev.jmx.client.ui.pagingSource.CollectAlbumPagingSource
import dev.jmx.client.ui.pagingSource.HistoryAlbumPagingSource
import dev.jmx.client.ui.pagingSource.HistoryCommentPagingSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UserViewModel(
    private val userManager: UserManager,
    private val userRepository: UserRepository,
    private val toastManager: ToastManager,
) : ViewModel() {
    private fun currentUserId(): Int = userManager.userState.value.data?.id ?: 0
    private var loginJob: Job? = null

    private val _loginState = MutableStateFlow(CommonUIState(data = null))
    val loginState = _loginState.asStateFlow()
    fun login(username: String, password: String) {
        if (loginJob?.isActive == true) {
            return
        }
        val normalizedUsername = username.trim()
        JmxDiagnostics.userAction(
            screen = "Login",
            action = "submit_login",
            target = "login_button",
            metadata = mapOf(
                "username_length" to normalizedUsername.length,
                "password_length" to password.length
            )
        )
        loginJob = viewModelScope.launch {
            _loginState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.login(normalizedUsername, password)) {
                is NetworkResult.Error -> {
                    _loginState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                    JmxDiagnostics.e(
                        "Auth",
                        "Manual login failed",
                        metadata = mapOf(
                            "username_length" to normalizedUsername.length,
                            "error_message" to data.message
                        )
                    )
                }

                is NetworkResult.Success<LoginResponse> -> {
                    val user = data.data.toUser(
                        password = password
                    )
                    userManager.updateUser(user)
                    JmxDiagnostics.i(
                        "Auth",
                        "Manual login succeeded",
                        metadata = mapOf(
                            "user_id" to user.id,
                            "username_length" to user.username.orEmpty().length
                        )
                    )
                }
            }
            _loginState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun logout() {
        JmxDiagnostics.userAction(
            screen = "Profile",
            action = "logout",
            target = "logout_button"
        )
        viewModelScope.launch {
            userManager.clearUser()
        }
    }

    private val _collectAlbumOrder = MutableStateFlow(CollectAlbumOrderFilter.COLLECT_TIME)
    val collectAlbumOrder = _collectAlbumOrder.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val collectAlbumPager = _collectAlbumOrder.flatMapLatest { order ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                CollectAlbumPagingSource(
                    userRepository,
                    order
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun changeCollectAlbumOrder(order: CollectAlbumOrderFilter) {
        _collectAlbumOrder.update {
            order
        }
    }

    val historyAlbumPager = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
        pagingSourceFactory = {
            HistoryAlbumPagingSource(
                userRepository,
            )
        }
    ).flow.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyCommentPager = userManager.userState
        .map { it.data?.id ?: 0 }
        .distinctUntilChanged()
        .flatMapLatest { userId ->
            Pager(
                config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
                pagingSourceFactory = {
                    HistoryCommentPagingSource(
                        userRepository,
                        userId
                    )
                }
            ).flow
        }.cachedIn(viewModelScope)

    private val _signInDataState = MutableStateFlow(
        CommonUIState<SignInData>(
            isLoading = true
        )
    )
    val signDataState = _signInDataState.asStateFlow()
    fun getSignInData() {
        viewModelScope.launch {
            val userId = currentUserId()
            if (userId <= 0) {
                _signInDataState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMsg = "请先登录"
                    )
                }
                return@launch
            }

            _signInDataState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.getSignData(userId)) {
                is NetworkResult.Error -> {
                    _signInDataState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetworkResult.Success<SignInDataResponse> -> {
                    _signInDataState.update {
                        it.copy(
                            data = data.data.toSignData()
                        )
                    }
                }
            }
            _signInDataState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    private val _signInState = MutableStateFlow(CommonUIState<String>())
    val signInState = _signInState.asStateFlow()
    fun signIn() {
        viewModelScope.launch {
            val userId = currentUserId()
            val dailyId = _signInDataState.value.data?.dailyId
            if (userId <= 0 || dailyId == null) {
                _signInState.update {
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMsg = "请先登录并加载签到数据"
                    )
                }
                return@launch
            }

            _signInState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.signIn(
                userId,
                dailyId
            )) {
                is NetworkResult.Error -> {
                    _signInState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetworkResult.Success<SignInResponse> -> {
                    toastManager.showAsync(data.data.msg)
                    getSignInData()
                    _signInState.update {
                        it.copy(
                            data = data.data.msg
                        )
                    }
                }
            }
            _signInState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }
}
