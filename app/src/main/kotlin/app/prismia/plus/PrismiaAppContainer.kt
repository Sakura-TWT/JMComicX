package app.prismia.plus

import android.content.Context
import app.prismia.comic.ComicCatalog
import app.prismia.data.FederatedVideoRepository
import app.prismia.data.VideoContentStore
import app.prismia.data.FileVideoContentStore
import app.prismia.data.VideoSessionManager
import app.prismia.data.VideoSessionStore
import app.prismia.source.iwara.IwaraClient
import app.prismia.source.iwara.OkHttpIwaraTransport
import app.prismia.source.jmcomic.JmComicCatalog
import app.prismia.source.oreno.OkHttpOrenoTransport
import app.prismia.source.oreno.OrenoClient
import app.prismia.video.PagedVideoCatalog
import okhttp3.OkHttpClient

/**
 * Process scoped dependency graph for the fusion branch.
 *
 * The container is deliberately small and explicit for now. It gives the
 * Android layer one composition point without introducing a DI framework
 * before the module boundaries have settled.
 */
class PrismiaAppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val videoClient: OkHttpClient by lazy {
        // Video requests must not inherit JM cookies or protocol interceptors.
        OkHttpClient.Builder().build()
    }

    val comicCatalog: ComicCatalog by lazy {
        JmComicCatalog(createAppJmxCore(applicationContext))
    }

    val orenoCatalog: PagedVideoCatalog by lazy {
        OrenoClient(
            transport = OkHttpOrenoTransport(
                client = videoClient,
                baseUrl = "https://oreno3d.com",
            ),
        )
    }

    val iwaraCatalog: PagedVideoCatalog by lazy {
        IwaraClient(
            OkHttpIwaraTransport(
                client = videoClient,
                accessTokenProvider = videoSessionManager::currentAccessToken,
            ),
        )
    }

    val videoRepository: FederatedVideoRepository by lazy {
        FederatedVideoRepository(orenoCatalog, iwaraCatalog)
    }

    val videoSessionStore: VideoSessionStore by lazy {
        AndroidKeystoreVideoSessionStore(applicationContext)
    }

    val videoSessionManager: VideoSessionManager by lazy {
        VideoSessionManager(videoSessionStore)
    }

    /** Durable interim store; the future database module can replace this boundary. */
    val videoContentStore: VideoContentStore by lazy {
        FileVideoContentStore(applicationContext.filesDir.resolve(VIDEO_CONTENT_STORE_FILE))
    }
}

private const val VIDEO_CONTENT_STORE_FILE = "video-content-store.bin"\n