package app.prismia.plus.core.runtime

import app.prismia.plus.core.api.AlbumApi
import app.prismia.plus.core.api.ChapterApi
import app.prismia.plus.core.api.InteractionApi
import app.prismia.plus.core.api.LibraryApi
import app.prismia.plus.core.api.SettingApi
import app.prismia.plus.core.api.UserApi
import app.prismia.plus.core.cache.InMemoryKeyValueStore
import app.prismia.plus.core.cache.JsonResponseCache
import app.prismia.plus.core.cache.KeyValueStore
import app.prismia.plus.core.cache.ProtocolStateStore
import app.prismia.plus.core.download.BinaryDownloader
import app.prismia.plus.core.download.ChapterDownloadTaskManager
import app.prismia.plus.core.download.ChapterDownloadTaskStore
import app.prismia.plus.core.download.DownloadBatchRunner
import app.prismia.plus.core.download.TaskExecutionPolicy
import app.prismia.plus.core.image.ImageHostRegistry
import app.prismia.plus.core.image.imageOkHttpClient
import app.prismia.plus.core.network.ApiEndpointManager
import app.prismia.plus.core.network.ApiEndpointProber
import app.prismia.plus.core.network.ApiEndpointSelection
import app.prismia.plus.core.network.DefaultResponseCachePolicy
import app.prismia.plus.core.network.DefaultRetryPolicy
import app.prismia.plus.core.network.DomainRefresher
import app.prismia.plus.core.network.JmxApiClient
import app.prismia.plus.core.network.JmxHttpClient
import app.prismia.plus.core.network.RequestMetricsRecorder
import app.prismia.plus.core.network.ResponseCachePolicy
import app.prismia.plus.core.network.RetryPolicy
import app.prismia.plus.core.network.apiOkHttpClient
import app.prismia.plus.core.network.defaultOkHttpClient
import app.prismia.plus.core.protocol.ApiClock
import app.prismia.plus.core.protocol.ApiTokenProvider
import app.prismia.plus.core.protocol.JmxProtocolConstants
import app.prismia.plus.core.protocol.StoredApiVersionProvider
import app.prismia.plus.core.protocol.SystemApiClock
import app.prismia.plus.core.session.CookieStore
import app.prismia.plus.core.session.InMemoryCookieStore
import app.prismia.plus.core.session.SessionManager
import app.prismia.plus.core.session.StoreBackedCookieJar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.nio.file.Path

data class JmxCoreConfig(
    val keyValueStore: KeyValueStore = InMemoryKeyValueStore(),
    val cookieStore: CookieStore = InMemoryCookieStore(),
    val okHttpClient: OkHttpClient? = null,
    val apiClock: ApiClock = SystemApiClock,
    val retryPolicy: RetryPolicy = DefaultRetryPolicy(),
    val downloadConcurrency: Int = 4,
    val domainServerUrls: List<String> = JmxProtocolConstants.DomainServerUrls,

    /** 内容语言（"CN"/"TW"等）；非空时为所有 GET 请求附加 lang 查询参数，null/空 表示不附加 */
    val contentLanguageProvider: () -> String? = { null },

    /**
     * 接口 JSON 响应的磁盘缓存目录。为 null 表示不启用缓存（默认，测试与诊断工具保持纯网络行为）。
     * 客户端应传入应用缓存目录下的子目录，例如 `context.cacheDir/api_json_cache`。
     */
    val responseCacheDirectory: Path? = null,

    /** 哪些路由缓存、缓存多久。仅在 [responseCacheDirectory] 非 null 时生效。 */
    val responseCachePolicy: ResponseCachePolicy = DefaultResponseCachePolicy(),

    val chapterDownloadTaskStore: ChapterDownloadTaskStore? = null,
    val taskExecutionPolicy: TaskExecutionPolicy = TaskExecutionPolicy()
)

class JmxCore private constructor(
    val protocolStateStore: ProtocolStateStore,
    val apiVersionProvider: StoredApiVersionProvider,
    val endpointManager: ApiEndpointManager,
    /** 图片 CDN 的线路表；封面与阅读器据此选机器，[imageHttpClient] 据此换机。 */
    val imageHostRegistry: ImageHostRegistry,
    /** 共享的基础客户端（连接池 / DNS 缓存都在这一层）。图片客户端由它派生。 */
    val httpTransport: OkHttpClient,
    val sessionManager: SessionManager,
    val httpClient: JmxHttpClient,
    val apiClient: JmxApiClient,
    val albumApi: AlbumApi,
    val chapterApi: ChapterApi,
    val settingApi: SettingApi,
    val userApi: UserApi,
    val interactionApi: InteractionApi,
    val libraryApi: LibraryApi,
    val domainRefresher: DomainRefresher,
    val requestMetricsRecorder: RequestMetricsRecorder = RequestMetricsRecorder(),
    val endpointProber: ApiEndpointProber,
    val initializer: JmxCoreInitializer,
    /** 接口 JSON 响应缓存；[JmxCoreConfig.responseCacheDirectory] 为 null 时为 null。 */
    val responseCache: JsonResponseCache? = null,
    val downloader: BinaryDownloader,
    val downloadBatchRunner: DownloadBatchRunner,
    private val domainServerUrls: List<String>,
    chapterDownloadTaskStore: ChapterDownloadTaskStore? = null,
    taskExecutionPolicy: TaskExecutionPolicy = TaskExecutionPolicy()
) {
    /**
     * 图片专用客户端（更宽的分发器 + 选路拦截器）。
     *
     * 懒加载：只有真的要显示图片的进程才需要它，诊断工具与单测不必为此多建一个线程池。
     */
    val imageHttpClient: OkHttpClient by lazy { imageOkHttpClient(httpTransport, imageHostRegistry) }

    val smokeRunner: JmxCoreSmokeRunner = JmxCoreSmokeRunner(this)
    val probeRunner: JmxCoreProbeRunner = JmxCoreProbeRunner(this)
    val connectivityRunner: JmxLiveConnectivityRunner = JmxLiveConnectivityRunner(this)
    val readingRunner: JmxLiveReadingRunner = JmxLiveReadingRunner(this)
    val loginRunner: JmxLiveLoginRunner = JmxLiveLoginRunner(this)
    val endpointController: JmxEndpointController = JmxEndpointController(this)
    val chapterDownloadTasks: ChapterDownloadTaskManager by lazy {
        ChapterDownloadTaskManager(
            templateFetcher = { chapterId, shunt ->
                chapterApi.template(chapterId = chapterId, shunt = shunt)
            },
            downloader = downloader,
            downloadConcurrency = downloadBatchRunner.maxConcurrency,
            taskStore = chapterDownloadTaskStore,
            executionPolicy = taskExecutionPolicy
        )
    }
    fun healthSnapshot(): JmxCoreHealth {
        val nowMillis = System.currentTimeMillis()
        return JmxCoreHealth(
            apiVersion = apiVersionProvider.current(),
            endpoints = endpointManager.all().map {
                EndpointHealth(
                    url = it.url.toString(),
                    successCount = it.successCount,
                    failureCount = it.failureCount,
                    consecutiveFailureCount = it.consecutiveFailureCount,
                    lastSuccessAtMillis = it.lastSuccessAtMillis,
                    lastFailureAtMillis = it.lastFailureAtMillis,
                    lastLatencyMillis = it.lastLatencyMillis,
                    averageLatencyMillis = it.averageLatencyMillis,
                    unavailableUntilMillis = it.unavailableUntilMillis,
                    healthScore = it.healthScore(nowMillis),
                    isAvailable = it.isAvailableAt(nowMillis),
                    lastFailureMessage = it.lastFailureMessage
                )
            },
            endpointSelection = endpointManager.selection().toHealth(),
            cookieCount = sessionManager.cookies().size,
            domainServerUrls = domainServerUrls,
            downloadConcurrency = downloadBatchRunner.maxConcurrency
        )
    }

    private fun ApiEndpointSelection.toHealth(): EndpointSelectionHealth {
        val sessionUrl = endpointManager.sessionEndpoint()?.toString()
        return when (this) {
            ApiEndpointSelection.Auto -> EndpointSelectionHealth(
                mode = "auto",
                manualUrl = null,
                sessionUrl = sessionUrl
            )
            is ApiEndpointSelection.Manual -> EndpointSelectionHealth(
                mode = "manual",
                manualUrl = url.toString(),
                sessionUrl = sessionUrl
            )
        }
    }

    companion object {
        fun create(config: JmxCoreConfig = JmxCoreConfig()): JmxCore {
            val protocolStateStore = ProtocolStateStore(config.keyValueStore)
            val apiVersionProvider = StoredApiVersionProvider(protocolStateStore)
            val endpointManager = ApiEndpointManager(protocolStateStore = protocolStateStore)
            val imageHostRegistry = ImageHostRegistry(protocolStateStore = protocolStateStore)
            val sessionManager = SessionManager(config.cookieStore)
            val cookieJar = StoreBackedCookieJar(config.cookieStore)
            val okHttpClient = config.okHttpClient ?: defaultOkHttpClient(cookieJar)
            // 接口请求额外套一层整次调用时限；图片下载继续用基础客户端（见 apiOkHttpClient 注释）。
            val apiHttpTransport = apiOkHttpClient(okHttpClient)
            val tokenProvider = ApiTokenProvider(
                clock = config.apiClock,
                apiVersionProvider = apiVersionProvider
            )
            val requestMetricsRecorder = RequestMetricsRecorder()
            val httpClient = JmxHttpClient(
                endpointManager = endpointManager,
                tokenProvider = tokenProvider,
                okHttpClient = apiHttpTransport,
                retryPolicy = config.retryPolicy,
                requestMetricsRecorder = requestMetricsRecorder,
                queryLanguageProvider = config.contentLanguageProvider
            )
            // 后台校验与后台域名刷新共用：SupervisorJob 保证一个任务失败不牵连其他，
            // 生命周期与 JmxCore 相同（单例，随进程结束）。
            val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val responseCache = config.responseCacheDirectory?.let { JsonResponseCache(directory = it) }
            val apiClient = JmxApiClient(
                httpClient = httpClient,
                responseCache = responseCache,
                cachePolicy = responseCache?.let { config.responseCachePolicy },
                revalidationScope = backgroundScope,
                // 语言已固化到请求身份；保留命名空间，隔离语言偏好对应的缓存。
                // API 版本一变说明协议换代，旧缓存内容一并作废。
                cacheNamespace = { config.contentLanguageProvider().orEmpty() },
                cacheGeneration = { apiVersionProvider.current() }
            )
            val downloader = BinaryDownloader(
                okHttpClient = okHttpClient,
                // 与取图路径共用同一张线路表：下载踩过的坑，阅读器与封面预热不用再踩一遍。
                imageHostRegistry = imageHostRegistry
            )
            val albumApi = AlbumApi(apiClient)
            val chapterApi = ChapterApi(apiClient)
            val settingApi = SettingApi(
                apiClient = apiClient,
                apiVersionProvider = apiVersionProvider,
                onApiVersionChanged = { version ->
                    // 目录遍历 + 删除，放后台执行，不占初始化这条关键路径。
                    responseCache?.let { cache -> backgroundScope.launch { cache.invalidateOtherGenerations(version) } }
                }
            )
            val userApi = UserApi(
                apiClient = apiClient,
                sessionManager = sessionManager,
                sessionSyncHosts = {
                    endpointManager.all().map { it.url.toString() }
                },
                endpointManager = endpointManager
            )
            val interactionApi = InteractionApi(apiClient)
            val libraryApi = LibraryApi(apiClient)
            val domainRefresher = DomainRefresher(
                endpointManager = endpointManager,
                okHttpClient = apiHttpTransport,
                serverUrls = config.domainServerUrls,
                sessionManager = sessionManager
            )
            val endpointProber = ApiEndpointProber(
                endpointManager = endpointManager,
                tokenProvider = tokenProvider,
                okHttpClient = apiHttpTransport,
                queryLanguageProvider = config.contentLanguageProvider
            )
            return JmxCore(
                protocolStateStore = protocolStateStore,
                apiVersionProvider = apiVersionProvider,
                endpointManager = endpointManager,
                imageHostRegistry = imageHostRegistry,
                httpTransport = okHttpClient,
                sessionManager = sessionManager,
                httpClient = httpClient,
                apiClient = apiClient,
                albumApi = albumApi,
                chapterApi = chapterApi,
                settingApi = settingApi,
                userApi = userApi,
                interactionApi = interactionApi,
                libraryApi = libraryApi,
                domainRefresher = domainRefresher,
                requestMetricsRecorder = requestMetricsRecorder,
                endpointProber = endpointProber,
                initializer = JmxCoreInitializer(
                    domainRefresher = domainRefresher,
                    settingApi = settingApi,
                    // 上次运行留下过用得通的主机 => 走热启动，不再让域名刷新挡住首页。
                    hasPersistedApiHost = { protocolStateStore.preferredAutoApiHost() != null },
                    backgroundScope = backgroundScope
                ),
                responseCache = responseCache,
                downloader = downloader,
                downloadBatchRunner = DownloadBatchRunner(downloader, config.downloadConcurrency),
                domainServerUrls = config.domainServerUrls,
                chapterDownloadTaskStore = config.chapterDownloadTaskStore,
                taskExecutionPolicy = config.taskExecutionPolicy
            )
        }
    }
}
