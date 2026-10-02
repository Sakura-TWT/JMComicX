package app.prismia.plus

import app.prismia.plus.core.api.ActionResult
import app.prismia.plus.core.api.DailyCheckInfo
import app.prismia.plus.core.api.FavoriteFolder
import app.prismia.plus.core.api.FavoritePage
import app.prismia.plus.core.protocol.JmxMagicConstants
import app.prismia.plus.core.result.JmxError
import app.prismia.plus.core.result.JmxResult
import app.prismia.plus.core.runtime.JmxCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class AccountCollectionKind { FAVORITES, HISTORY }

/**
 * 收藏页排序方式，对应收藏接口的 `o` 参数。
 *
 * 声明顺序即下拉菜单里的展示顺序；默认值单独由 [FavoriteSortOrder.Default] 给出，
 * 保持与服务端默认（收藏时间）一致，不因为菜单排序而变。
 */
internal enum class FavoriteSortOrder(val apiOrder: String, val label: String) {
    UPDATE_TIME(JmxMagicConstants.FAVORITE_ORDER_BY_UPDATE_TIME, "更新时间"),
    FAVORITE_TIME(JmxMagicConstants.FAVORITE_ORDER_BY_FAVORITE_TIME, "收藏时间"),
    ;

    companion object {
        val Default = FAVORITE_TIME

        fun fromName(name: String?): FavoriteSortOrder =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * 收藏页排序方向。
 *
 * 「正序」= 服务端原生顺序（收藏接口固定返回最新在前）；「倒序」= 整体翻转，
 * 把服务端的最后一页当第一页取、页内顺序反过来。默认 [ASCENDING]，与改动前表现一致。
 */
internal enum class FavoriteSortDirection(val label: String) {
    ASCENDING("正序"),
    DESCENDING("倒序"),
    ;

    companion object {
        val Default = ASCENDING

        fun fromName(name: String?): FavoriteSortDirection =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/** 服务端总页数：`total` 未知时返回 null，调用方要先探一页才能算出来。 */
internal fun favoriteServerPageCount(total: Int?): Int? {
    if (total == null || total <= 0) return null
    val size = JmxMagicConstants.PAGE_SIZE_FAVORITE
    return (total + size - 1) / size
}

/**
 * 逻辑页号 → 服务端页号。
 *
 * 正序时两者相同（服务端原生顺序就是最新在前）。倒序时逻辑第 1 页对应服务端最后一页，
 * 依次往前；越过第 1 页说明已经翻到头，返回 null 让调用方收尾而不是重复请求第 1 页。
 */
internal fun favoriteServerPage(
    logicalPage: Int,
    direction: FavoriteSortDirection,
    serverPageCount: Int?,
): Int? = when (direction) {
    FavoriteSortDirection.ASCENDING -> logicalPage
    FavoriteSortDirection.DESCENDING -> {
        if (serverPageCount == null) {
            // 还不知道总页数，只能先探服务端第一页把 total 拿回来。
            1
        } else {
            (serverPageCount - logicalPage + 1).takeIf { it >= 1 }
        }
    }
}

internal data class AccountAlbumPage(
    val albums: List<HomeAlbum>,
    val total: Int?,
    val folders: List<FavoriteFolder> = emptyList(),
)

/** 整批响应没有逐项状态，因此逐部提交，成功项移除选择，失败项可安全重试。 */
internal data class FavoriteAddResult(
    val action: ActionResult,
    /** 非 null 表示收藏已确认成功，仅归类失败；UI 应刷新收藏态并保留选夹重试。 */
    val folderError: JmxError? = null,
)

internal data class FavoriteBatchResult(
    val succeededIds: Set<String>,
    val failures: Map<String, JmxError>,
)

internal fun favoriteFolderChoices(folders: List<FavoriteFolder>): List<FavoriteFolder> = folders
    .filter { (it.id.toIntOrNull() ?: 0) > 0 }
    .distinctBy { it.id.toInt() }

internal fun FavoriteFolder.displayName(): String = name?.takeIf { it.isNotBlank() } ?: "资料夹 $id"

internal class AccountDataRepository(
    private val core: JmxCore,
    private val homeRepository: HomeRepository,
    private val accountRepository: AccountRepository,
) {
    /**
     * 拉一页收藏/观看历史。
     *
     * 整段放在 IO 线程上：出网之后还要把响应映射成 [HomeAlbum]（其中 `toRawMap` 会把嵌套的
     * JSON 重新串成字符串），留在调用方线程上就会在"内容即将出现"的那一刻卡住界面。
     */
    private val favoriteMutationMutex = Mutex()

    /** 本地身份快照，不读凭据也不发网络请求；调用链每次 suspend 后检查，避免串账号。 */
    fun accountIdentity(): String? = accountRepository.restore()?.let { "${it.id}:${it.username}" }

    suspend fun loadCollection(
        kind: AccountCollectionKind,
        page: Int,
        favoriteOrder: FavoriteSortOrder = FavoriteSortOrder.Default,
        folderId: Int = 0,
    ): JmxResult<AccountAlbumPage> = withContext(Dispatchers.IO) {
        val identity = accountIdentity()
        val result = accountRepository.withSessionRecovery {
            if (accountIdentity() != identity) return@withSessionRecovery accountChanged()
            when (kind) {
                AccountCollectionKind.FAVORITES -> core.libraryApi.favoritePage(
                    page = page, order = favoriteOrder.apiOrder, folderId = folderId,
                )
                AccountCollectionKind.HISTORY -> when (val history = core.libraryApi.watchList(page)) {
                    is JmxResult.Failure -> history
                    is JmxResult.Success -> JmxResult.Success(
                        FavoritePage(history.value.total, history.value.content, emptyList(), history.value.raw),
                    )
                }
            }
        }
        if (accountIdentity() != identity) return@withContext accountChanged()
        when (result) {
            is JmxResult.Success -> JmxResult.Success(
                AccountAlbumPage(
                    albums = result.value.content
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .map { it.toHomeAlbum(homeRepository.currentImageHost) },
                    total = result.value.total,
                    folders = favoriteFolderChoices(result.value.folders),
                ),
            )
            is JmxResult.Failure -> result
        }
    }

    suspend fun loadFavoriteFolders(): JmxResult<List<FavoriteFolder>> =
        when (val result = loadCollection(AccountCollectionKind.FAVORITES, 1)) {
            is JmxResult.Failure -> result
            is JmxResult.Success -> JmxResult.Success(result.value.folders)
        }

    /**
     * 详情选夹入口。默认收藏 folderId=0 只新增；实际资料夹按官方流程新增后 move。
     * 重试前重新读取 isFavorite，避免上次新增成功、move 失败后再次 toggle 取消收藏。
     */
    suspend fun addFavoriteToFolder(albumId: String, folderId: Int = 0): JmxResult<FavoriteAddResult> =
        favoriteMutationMutex.withLock {
            val identity = accountIdentity() ?: return@withLock accountChanged()
            if (folderId < 0) return@withLock JmxResult.Failure(JmxError.Schema("资料夹编号无效"))
            val detail = accountRepository.withSessionRecovery {
                if (accountIdentity() != identity) return@withSessionRecovery accountChanged()
                core.albumApi.detailFull(albumId)
            }
            currentCoroutineContext().ensureActive()
            if (accountIdentity() != identity) return@withLock accountChanged()
            val favorite = when (detail) {
                is JmxResult.Failure -> return@withLock detail
                is JmxResult.Success -> detail.value.isFavorite
                    ?: return@withLock JmxResult.Failure(JmxError.Schema("无法确认收藏状态，请刷新详情后重试"))
            }
            if (!favorite) {
                // toggle 不使用 withSessionRecovery 重放。重新登录后再次点击会先查真实状态。
                when (val added = core.interactionApi.favoriteAlbum(albumId)) {
                    is JmxResult.Failure -> return@withLock added
                    is JmxResult.Success -> if (added.value.type != "add") {
                        return@withLock JmxResult.Failure(JmxError.Schema("收藏状态已变化，请刷新详情后重试"))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (accountIdentity() != identity) return@withLock accountChanged()
            val confirmed = ActionResult("ok", "已收藏", "add", emptyMap())
            if (folderId == 0) return@withLock JmxResult.Success(FavoriteAddResult(confirmed))
            val moved = moveFavorite(albumId, folderId, identity)
            if (accountIdentity() != identity) return@withLock accountChanged()
            JmxResult.Success(when (moved) {
                is JmxResult.Success -> FavoriteAddResult(moved.value)
                is JmxResult.Failure -> FavoriteAddResult(confirmed, JmxError.Schema(
                    "漫画已收藏，但放入资料夹失败：${moved.error.toUiMessage()}。重试不会取消收藏。",
                ))
            })
        }

    suspend fun moveFavoritesToFolder(albumIds: Set<String>, folderId: Int): FavoriteBatchResult =
        favoriteMutationMutex.withLock {
            val identity = accountIdentity()
            val succeeded = linkedSetOf<String>()
            val failures = linkedMapOf<String, JmxError>()
            for (albumId in albumIds) {
                currentCoroutineContext().ensureActive()
                val result = if (identity == null || accountIdentity() != identity) accountChanged()
                    else moveFavorite(albumId, folderId, identity)
                when (result) {
                    is JmxResult.Success -> succeeded += albumId
                    is JmxResult.Failure -> failures[albumId] = result.error
                }
            }
            FavoriteBatchResult(succeeded, failures)
        }

    private suspend fun moveFavorite(albumId: String, folderId: Int, identity: String): JmxResult<ActionResult> =
        accountRepository.withSessionRecovery {
            currentCoroutineContext().ensureActive()
            if (accountIdentity() != identity) return@withSessionRecovery accountChanged()
            core.interactionApi.moveFavoritesToFolder(listOf(albumId), folderId)
        }

    suspend fun dailyInfo(profile: AccountProfile): JmxResult<DailyCheckInfo?> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        // 会话过期时 /daily 不回 401，而是回空载荷（与"当期无活动"同形），
        // 所以这里要显式把空载荷也当成会话失效的可能原因交给恢复逻辑判定，
        // 否则自动签到只会静默地什么都不做。
        return accountRepository.withSessionRecovery(treatEmptyDataAsSessionLoss = true) {
            core.libraryApi.dailyInfo(id)
        }
    }

    suspend fun checkIn(profile: AccountProfile, dailyId: Int?): JmxResult<ActionResult> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        val eventId = dailyId?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("签到活动编号缺失", field = "dailyId"))
        val submit = accountRepository.withSessionRecovery(treatEmptyDataAsSessionLoss = true) {
            core.libraryApi.dailyCheck(id, eventId)
        }
        if (submit is JmxResult.Failure && submit.error is JmxError.EmptyData) {
            // 提交签到也可能返回空载荷：以重新查询到的当日记录为准，不能只信提交响应
            return when (val verified = resolveCheckInAfterEmptyPayload(dailyInfo(profile))) {
                is JmxResult.Success -> verified
                is JmxResult.Failure -> submit
            }
        }
        return submit
    }

    suspend fun autoCheckIn(profile: AccountProfile): AutoCheckInResult {
        // 稳定度保护：同一时刻只允许一次自动签到流程（跳过而非排队），
        // 且两次真实尝试之间保持最小间隔，防止触发变敏感后重复提交或频繁失败
        if (!autoCheckInInFlight.compareAndSet(false, true)) {
            return AutoCheckInResult.FAILED
        }
        try {
            val now = System.currentTimeMillis()
            if (now - lastAutoCheckInAttemptAtMillis < AUTO_CHECK_IN_MIN_INTERVAL_MILLIS) {
                return AutoCheckInResult.FAILED
            }
            lastAutoCheckInAttemptAtMillis = now
            val info = when (val infoResult = dailyInfo(profile)) {
                is JmxResult.Success -> infoResult.value ?: return AutoCheckInResult.NO_ACTIVE_EVENT
                is JmxResult.Failure -> return AutoCheckInResult.FAILED
            }
            if (info.isSignedToday()) return AutoCheckInResult.ALREADY_SIGNED
            return when (val result = checkIn(profile, info.dailyId)) {
                is JmxResult.Success -> if (result.value.isAcceptedCheckInResult()) {
                    AutoCheckInResult.COMPLETED
                } else {
                    AutoCheckInResult.FAILED
                }
                is JmxResult.Failure -> AutoCheckInResult.FAILED
            }
        } finally {
            autoCheckInInFlight.set(false)
        }
    }
}

private fun accountChanged() = JmxResult.Failure(JmxError.Api(401, "账号已变化，请重新打开此页面"))

internal data class FavoriteLogicalPage(
    val albums: List<HomeAlbum>,
    val total: Int?,
    val serverPageCount: Int?,
)

/** 可注入纯 synthetic 页数据；folderId 始终属于调用方闭包，不在探测倒序时丢失。 */
internal suspend fun loadFavoriteLogicalPage(
    logicalPage: Int,
    direction: FavoriteSortDirection,
    knownServerPageCount: Int?,
    loadPage: suspend (Int) -> JmxResult<AccountAlbumPage>,
): JmxResult<FavoriteLogicalPage> {
    if (direction == FavoriteSortDirection.ASCENDING) {
        return when (val result = loadPage(logicalPage)) {
            is JmxResult.Failure -> result
            is JmxResult.Success -> JmxResult.Success(FavoriteLogicalPage(
                result.value.albums, result.value.total, favoriteServerPageCount(result.value.total),
            ))
        }
    }
    var count = knownServerPageCount
    if (count == null) {
        val probe = when (val result = loadPage(1)) {
            is JmxResult.Failure -> return result
            is JmxResult.Success -> result.value
        }
        // count 是页大小，不是总数；未知 total 不可假装倒序只有一页。
        val total = probe.total ?: return JmxResult.Failure(JmxError.Schema("服务端未返回总数，无法倒序，请切换正序"))
        count = favoriteServerPageCount(total) ?: 1
        if (count <= 1) return JmxResult.Success(FavoriteLogicalPage(probe.albums.reversed(), total, count))
    }
    val page = favoriteServerPage(logicalPage, direction, count)
        ?: return JmxResult.Success(FavoriteLogicalPage(emptyList(), null, count))
    return when (val result = loadPage(page)) {
        is JmxResult.Failure -> result
        is JmxResult.Success -> {
            val latestCount = favoriteServerPageCount(result.value.total)
            if (latestCount != null && latestCount != count) {
                JmxResult.Failure(JmxError.Schema("收藏数量已变化，请刷新后继续倒序浏览"))
            } else JmxResult.Success(FavoriteLogicalPage(result.value.albums.reversed(), result.value.total, count))
        }
    }
}

internal enum class AutoCheckInResult { COMPLETED, ALREADY_SIGNED, NO_ACTIVE_EVENT, FAILED }

private val autoCheckInInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

@Volatile
private var lastAutoCheckInAttemptAtMillis = 0L

private const val AUTO_CHECK_IN_MIN_INTERVAL_MILLIS = 15_000L

internal fun resolveCheckInAfterEmptyPayload(
    refreshedInfo: JmxResult<DailyCheckInfo?>
): JmxResult<ActionResult> {
    val info = when (refreshedInfo) {
        is JmxResult.Failure -> return refreshedInfo
        is JmxResult.Success -> refreshedInfo.value
            ?: return JmxResult.Failure(JmxError.EmptyData("签到提交返回空数据，且服务端未返回活动信息"))
    }
    if (!info.isSignedToday()) {
        return JmxResult.Failure(JmxError.EmptyData("签到提交返回空数据，且当日记录未确认"))
    }
    return JmxResult.Success(
        ActionResult(
            status = "ok",
            message = "签到成功",
            type = null,
            raw = emptyMap()
        )
    )
}

internal fun DailyCheckInfo.isSignedToday(now: Date = Date()): Boolean {
    val todayValues = todayDateValues(now)
    val todayDay = SimpleDateFormat("d", Locale.US).format(now).toInt()
    return records.any { record ->
        if (record.signed != true) return@any false
        val date = record.date?.trim().orEmpty()
        date in todayValues || date.substringAfterLast('-').toIntOrNull() == todayDay
    }
}

private fun ActionResult.isAcceptedCheckInResult(): Boolean {
    val normalizedStatus = status?.trim()?.lowercase(Locale.ROOT)
    return normalizedStatus.isNullOrEmpty() ||
        normalizedStatus in AUTO_CHECK_IN_SUCCESS_STATUSES ||
        message.orEmpty().containsAlreadySignedMessage()
}

private fun String.containsAlreadySignedMessage(): Boolean {
    val normalized = lowercase(Locale.ROOT)
    return "已签到" in normalized || "已簽到" in normalized || "already" in normalized
}

private val AUTO_CHECK_IN_SUCCESS_STATUSES = setOf("ok", "success", "true", "1")

internal fun todayDate(now: Date = Date()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

internal fun todayDateValues(now: Date = Date()): Set<String> {
    val fullDate = todayDate(now)
    val day = SimpleDateFormat("dd", Locale.US).format(now)
    return setOf(fullDate, day, day.toIntOrNull()?.toString().orEmpty())
}
