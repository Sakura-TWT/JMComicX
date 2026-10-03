package app.prismia.plus

import android.content.Context
import app.prismia.comic.ComicCatalog
import app.prismia.data.DatabaseVideoContentStore
import app.prismia.data.FileVideoContentStore
import app.prismia.data.FederatedVideoRepository
import app.prismia.data.LegacyVideoContentStoreMigrator
import app.prismia.data.VideoContentStore
import app.prismia.data.VideoLibraryStore
import app.prismia.data.VideoSessionManager
import app.prismia.data.VideoSession
import app.prismia.data.VideoSessionStore
import app.prismia.data.VideoSessionRefresher
import app.prismia.database.android.PrismiaSqliteDatabase
import app.prismia.source.iwara.IwaraAuthClient
import app.prismia.source.iwara.IwaraAuthException
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

    private val iwaraTransport: OkHttpIwaraTransport by lazy {
        OkHttpIwaraTransport(
            client = videoClient,
            accessTokenProvider = { videoSessionManager.accessTokenOrRefresh() },
        )
    }

    val iwaraCatalog: PagedVideoCatalog by lazy {
        IwaraClient(iwaraTransport)
    }

    val iwaraAuthClient: IwaraAuthClient by lazy { IwaraAuthClient(iwaraTransport) }

    val videoRepository: FederatedVideoRepository by lazy {
        FederatedVideoRepository(orenoCatalog, iwaraCatalog)
    }

    val videoSessionStore: VideoSessionStore by lazy {
        AndroidKeystoreVideoSessionStore(applicationContext)
    }

    val videoSessionManager: VideoSessionManager by lazy {
        VideoSessionManager(
            store = videoSessionStore,
            refresher = VideoSessionRefresher { previous ->
                val refreshToken = previous?.refreshToken
                    ?: throw IwaraAuthException(
                        operation = "auth.refresh",
                        remoteCode = "missing_refresh_token",
                        message = "Iwara session has no refresh token",
                    )
                val renewed = iwaraAuthClient.refreshAccessToken(refreshToken)
                VideoSession(
                    accessToken = renewed.accessToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresAtEpochSeconds = renewed.expiresAtEpochSeconds,
                )
            },
        )
    }

    val videoDatabase: PrismiaSqliteDatabase by lazy {
        PrismiaSqliteDatabase(applicationContext)
    }

    val videoContentStore: VideoContentStore by lazy {
        DatabaseVideoContentStore(videoDatabase)
    }

    val videoLibraryStore: VideoLibraryStore by lazy {
        VideoLibraryStore(videoDatabase)
    }

    /** Performs the explicit login exchange and persists only the encrypted session. */
    suspend fun loginIwara(email: String, password: String): VideoSession {
        val login = iwaraAuthClient.login(email, password)
        val access = iwaraAuthClient.refreshAccessToken(login.refreshToken)
        val session = VideoSession(
            accessToken = access.accessToken,
            refreshToken = login.refreshToken,
            accessTokenExpiresAtEpochSeconds = access.expiresAtEpochSeconds,
        )
        videoSessionManager.save(session)
        return session
    }

    suspend fun logoutIwara() {
        videoSessionManager.clear()
    }

    /**
     * Migrates the pre-database snapshot exactly once. The old file is kept if
     * anything fails, so a later startup can retry without data loss.
     */
    suspend fun migrateLegacyVideoContentStore() {
        if (videoDatabase.readMetadata(VIDEO_MIGRATION_MARKER) != null) return
        val legacyFile = applicationContext.filesDir.resolve(VIDEO_CONTENT_STORE_FILE)
        if (!legacyFile.isFile) {
            videoDatabase.writeMetadata(VIDEO_MIGRATION_MARKER, "absent")
            return
        }
        if (!videoDatabase.isContentEmpty()) {
            videoDatabase.writeMetadata(VIDEO_MIGRATION_MARKER, "skipped-existing")
            return
        }
        LegacyVideoContentStoreMigrator(FileVideoContentStore(legacyFile))
            .migrateInto(videoDatabase)
        videoDatabase.writeMetadata(VIDEO_MIGRATION_MARKER, "migrated")
        val migrated = legacyFile.resolveSibling("${legacyFile.name}.migrated")
        if (!legacyFile.renameTo(migrated)) {
            // Retaining the source is safe; the marker prevents duplicate
            // imports, and the file remains available for manual recovery.
        }
    }
}

private const val VIDEO_CONTENT_STORE_FILE = "video-content-store.bin"
private const val VIDEO_MIGRATION_MARKER = "legacy_video_content_store_v1"
