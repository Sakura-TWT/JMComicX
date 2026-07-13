package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.AlbumApi
import dev.jmx.client.core.api.ChapterApi
import dev.jmx.client.core.api.InteractionApi
import dev.jmx.client.core.api.LibraryApi
import dev.jmx.client.core.api.SettingApi
import dev.jmx.client.core.api.UserApi
import dev.jmx.client.core.cache.InMemoryKeyValueStore
import dev.jmx.client.core.cache.KeyValueStore
import dev.jmx.client.core.cache.ProtocolStateStore
import dev.jmx.client.core.download.BinaryDownloader
import dev.jmx.client.core.download.DownloadBatchRunner
import dev.jmx.client.core.network.ApiEndpointManager
import dev.jmx.client.core.network.DefaultRetryPolicy
import dev.jmx.client.core.network.DomainRefresher
import dev.jmx.client.core.network.JmxApiClient
import dev.jmx.client.core.network.JmxHttpClient
import dev.jmx.client.core.network.RetryPolicy
import dev.jmx.client.core.network.defaultOkHttpClient
import dev.jmx.client.core.protocol.ApiClock
import dev.jmx.client.core.protocol.ApiTokenProvider
import dev.jmx.client.core.protocol.JmxProtocolConstants
import dev.jmx.client.core.protocol.StoredApiVersionProvider
import dev.jmx.client.core.protocol.SystemApiClock
import dev.jmx.client.core.session.CookieStore
import dev.jmx.client.core.session.InMemoryCookieStore
import dev.jmx.client.core.session.SessionManager
import dev.jmx.client.core.session.StoreBackedCookieJar
import okhttp3.OkHttpClient

data class JmxCoreConfig(
    val keyValueStore: KeyValueStore = InMemoryKeyValueStore(),
    val cookieStore: CookieStore = InMemoryCookieStore(),
    val okHttpClient: OkHttpClient? = null,
    val apiClock: ApiClock = SystemApiClock,
    val retryPolicy: RetryPolicy = DefaultRetryPolicy(),
    val downloadConcurrency: Int = 4,
    val domainServerUrls: List<String> = JmxProtocolConstants.DomainServerUrls
)

class JmxCore private constructor(
    val protocolStateStore: ProtocolStateStore,
    val apiVersionProvider: StoredApiVersionProvider,
    val endpointManager: ApiEndpointManager,
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
    val initializer: JmxCoreInitializer,
    val downloader: BinaryDownloader,
    val downloadBatchRunner: DownloadBatchRunner,
    private val domainServerUrls: List<String>
) {
    fun healthSnapshot(): JmxCoreHealth {
        return JmxCoreHealth(
            apiVersion = apiVersionProvider.current(),
            endpoints = endpointManager.all().map {
                EndpointHealth(
                    url = it.url.toString(),
                    failureCount = it.failureCount,
                    lastFailureMessage = it.lastFailureMessage
                )
            },
            cookieCount = sessionManager.cookies().size,
            domainServerUrls = domainServerUrls,
            downloadConcurrency = downloadBatchRunner.maxConcurrency
        )
    }

    companion object {
        fun create(config: JmxCoreConfig = JmxCoreConfig()): JmxCore {
            val protocolStateStore = ProtocolStateStore(config.keyValueStore)
            val apiVersionProvider = StoredApiVersionProvider(protocolStateStore)
            val endpointManager = ApiEndpointManager(protocolStateStore = protocolStateStore)
            val sessionManager = SessionManager(config.cookieStore)
            val cookieJar = StoreBackedCookieJar(config.cookieStore)
            val okHttpClient = config.okHttpClient ?: defaultOkHttpClient(cookieJar)
            val tokenProvider = ApiTokenProvider(
                clock = config.apiClock,
                apiVersionProvider = apiVersionProvider
            )
            val httpClient = JmxHttpClient(
                endpointManager = endpointManager,
                tokenProvider = tokenProvider,
                okHttpClient = okHttpClient,
                retryPolicy = config.retryPolicy
            )
            val apiClient = JmxApiClient(httpClient)
            val downloader = BinaryDownloader(okHttpClient = okHttpClient)
            val albumApi = AlbumApi(apiClient)
            val chapterApi = ChapterApi(apiClient)
            val settingApi = SettingApi(apiClient, apiVersionProvider)
            val userApi = UserApi(apiClient, endpointManager, sessionManager)
            val interactionApi = InteractionApi(apiClient)
            val libraryApi = LibraryApi(apiClient)
            val domainRefresher = DomainRefresher(
                endpointManager = endpointManager,
                okHttpClient = okHttpClient,
                serverUrls = config.domainServerUrls
            )
            return JmxCore(
                protocolStateStore = protocolStateStore,
                apiVersionProvider = apiVersionProvider,
                endpointManager = endpointManager,
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
                initializer = JmxCoreInitializer(domainRefresher, settingApi),
                downloader = downloader,
                downloadBatchRunner = DownloadBatchRunner(downloader, config.downloadConcurrency),
                domainServerUrls = config.domainServerUrls
            )
        }
    }
}
