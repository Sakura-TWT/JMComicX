package app.prismia.plus.core.runtime

import app.prismia.plus.core.api.RemoteSetting
import app.prismia.plus.core.api.SettingApi
import app.prismia.plus.core.network.ApiEndpoint
import app.prismia.plus.core.network.DomainRefresher
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class JmxCoreInitResult(
    val domainRefresh: InitStepResult<List<ApiEndpoint>>,
    val settingFetch: InitStepResult<RemoteSetting>
) {
    /**
     * [InitStepResult.Deferred] 也算成功：热启动时域名刷新被有意挪到后台，
     * 它还没出结果不等于这次初始化不可用。真失败了，后台任务回填 [InitStepResult.Failure]，
     * 下一次 initialize 自然按失败的短 TTL 重试。
     */
    val isFullySuccessful: Boolean =
        domainRefresh !is InitStepResult.Failure && settingFetch is InitStepResult.Success
}

sealed interface InitStepResult<out T> {
    data class Success<T>(
        val value: T
    ) : InitStepResult<T>

    data class Failure(
        val error: JmxError
    ) : InitStepResult<Nothing>

    /** 该步骤已挪到后台执行，本次 initialize 不等它。 */
    data object Deferred : InitStepResult<Nothing>
}

/**
 * 冷/热启动分流的初始化。
 *
 * 原实现无条件地"先刷新域名、再拉 setting"串行执行，两个网络往返都挡在首页第一次渲染前面。
 * 这里按是否有过可用主机分成两条路：
 * - **热启动**（[hasPersistedApiHost] 为真，即上次运行留下了可用主机）：立刻只等 setting，
 *   域名刷新丢到 [backgroundScope]，结果回填进缓存。已知可用的主机不需要每次开屏都重新发现。
 * - **冷启动**：两步并发。setting 用内置 DefaultApiHosts 先打，域名刷新同时进行；
 *   若 setting 失败而域名刷新拿到了新主机，再补打一次 setting——
 *   这是并发换来速度之后必须补上的那一步，否则内置主机失效时会白白报错。
 *
 * @param hasPersistedApiHost 是否已持久化过一个用得通的自动选择主机。
 * @param backgroundScope 承载后台域名刷新的作用域；为 null 时热启动路径不启用（退回并发冷启动）。
 */
class JmxCoreInitializer(
    private val domainRefresher: DomainRefresher,
    private val settingApi: SettingApi,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val hasPersistedApiHost: () -> Boolean = { false },
    private val backgroundScope: CoroutineScope? = null,
) {
    private val mutex = Mutex()
    @Volatile
    private var cachedResult: JmxCoreInitResult? = null
    @Volatile
    private var cachedAtMillis: Long = 0L

    suspend fun initialize(forceRefresh: Boolean = false): JmxCoreInitResult = mutex.withLock {
        val now = nowMillis()
        val cached = cachedResult
        val cacheTtl = if (cached?.isFullySuccessful == true) SUCCESS_CACHE_TTL_MILLIS else FAILURE_CACHE_TTL_MILLIS
        if (!forceRefresh && cached != null && now - cachedAtMillis in 0 until cacheTtl) {
            return@withLock cached
        }

        val result = if (!forceRefresh && backgroundScope != null && hasPersistedApiHost()) {
            warmStart(backgroundScope)
        } else {
            coldStart()
        }
        result.also {
            cachedResult = it
            cachedAtMillis = nowMillis()
        }
    }

    private suspend fun warmStart(scope: CoroutineScope): JmxCoreInitResult {
        scope.launch { refreshDomainsInBackground() }
        return JmxCoreInitResult(
            domainRefresh = InitStepResult.Deferred,
            settingFetch = settingApi.fetchSetting().toStepResult()
        )
    }

    private suspend fun refreshDomainsInBackground() {
        val step = domainRefresher.refresh().toStepResult()
        mutex.withLock {
            val current = cachedResult ?: return@withLock
            // 只覆盖 Deferred：期间若已有一次 forceRefresh 拿到了真实结果，不要拿旧的盖掉。
            if (current.domainRefresh !is InitStepResult.Deferred) return@withLock
            cachedResult = current.copy(domainRefresh = step)
            if (step is InitStepResult.Failure) {
                // 让下一次 initialize 按失败的短 TTL 立刻重试，而不是继续吃 15 分钟缓存。
                cachedAtMillis = nowMillis()
            }
        }
    }

    private suspend fun coldStart(): JmxCoreInitResult = coroutineScope {
        val domainAsync = async { domainRefresher.refresh() }
        val settingAsync = async { settingApi.fetchSetting() }
        val domain = domainAsync.await().toStepResult()
        val setting = settingAsync.await().toStepResult()
        val recoveredSetting = if (setting is InitStepResult.Failure && domain is InitStepResult.Success) {
            // 并发时 setting 打的是刷新前的主机。域名刷新换上了新主机，值得为此重打一次。
            settingApi.fetchSetting().toStepResult()
        } else {
            setting
        }
        JmxCoreInitResult(domainRefresh = domain, settingFetch = recoveredSetting)
    }

    private fun <T> JmxResult<T>.toStepResult(): InitStepResult<T> = when (this) {
        is JmxResult.Success -> InitStepResult.Success(value)
        is JmxResult.Failure -> InitStepResult.Failure(error)
    }

    private companion object {
        const val SUCCESS_CACHE_TTL_MILLIS = 15 * 60 * 1000L
        const val FAILURE_CACHE_TTL_MILLIS = 30 * 1000L
    }
}
