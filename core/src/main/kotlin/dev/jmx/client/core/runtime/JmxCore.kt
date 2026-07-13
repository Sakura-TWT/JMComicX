package dev.jmx.client.core.runtime

import dev.jmx.client.core.api.AlbumApi
import dev.jmx.client.core.api.ChapterApi
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
    val downloadConcurrency: Int = 4
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
    val domainRefresher: DomainRefresher,
    val downloader: BinaryDownloader,
    val downloadBatchRunner: DownloadBatchRunner
) {
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
            return JmxCore(
                protocolStateStore = protocolStateStore,
                apiVersionProvider = apiVersionProvider,
                endpointManager = endpointManager,
                sessionManager = sessionManager,
                httpClient = httpClient,
                apiClient = apiClient,
                albumApi = AlbumApi(apiClient),
                chapterApi = ChapterApi(apiClient),
                settingApi = SettingApi(apiClient, apiVersionProvider),
                userApi = UserApi(apiClient, endpointManager, sessionManager),
                domainRefresher = DomainRefresher(endpointManager, okHttpClient),
                downloader = downloader,
                downloadBatchRunner = DownloadBatchRunner(downloader, config.downloadConcurrency)
            )
        }
    }
}
