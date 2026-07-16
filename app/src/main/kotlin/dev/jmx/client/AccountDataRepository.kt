package dev.jmx.client

import dev.jmx.client.core.api.ActionResult
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.protocol.JmxMagicConstants
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import dev.jmx.client.core.runtime.JmxCore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class AccountCollectionKind { FAVORITES, HISTORY }

internal data class AccountAlbumPage(
    val albums: List<HomeAlbum>,
    val total: Int?,
)

internal class AccountDataRepository(
    private val core: JmxCore,
    private val homeRepository: HomeRepository,
) {
    suspend fun loadCollection(kind: AccountCollectionKind, page: Int): JmxResult<AccountAlbumPage> {
        val result = when (kind) {
            AccountCollectionKind.FAVORITES -> core.libraryApi.favoriteAlbums(
                page = page,
                order = JmxMagicConstants.ORDER_BY_LATEST,
                folderId = 0,
            )
            AccountCollectionKind.HISTORY -> core.libraryApi.watchList(page)
        }
        return when (result) {
            is JmxResult.Success -> JmxResult.Success(
                AccountAlbumPage(
                    albums = result.value.content
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .map { it.toHomeAlbum(homeRepository.currentImageHost) },
                    total = result.value.total,
                ),
            )
            is JmxResult.Failure -> result
        }
    }

    suspend fun dailyInfo(profile: AccountProfile): JmxResult<DailyCheckInfo> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        return core.libraryApi.dailyInfo(id)
    }

    suspend fun checkIn(profile: AccountProfile, dailyId: Int?): JmxResult<ActionResult> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        val eventId = dailyId?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("签到活动编号缺失", field = "dailyId"))
        return core.libraryApi.dailyCheck(id, eventId)
    }

    suspend fun autoCheckIn(profile: AccountProfile) {
        val info = (dailyInfo(profile) as? JmxResult.Success)?.value ?: return
        if (info.records.any { it.signed == true && it.date == todayDate() }) return
        checkIn(profile, info.dailyId)
    }
}

internal fun todayDate(now: Date = Date()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
