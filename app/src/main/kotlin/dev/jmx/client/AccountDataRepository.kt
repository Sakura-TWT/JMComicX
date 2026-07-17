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
    private val accountRepository: AccountRepository,
) {
    suspend fun loadCollection(kind: AccountCollectionKind, page: Int): JmxResult<AccountAlbumPage> {
        val result = accountRepository.withSessionRecovery {
            when (kind) {
                AccountCollectionKind.FAVORITES -> core.libraryApi.favoriteAlbums(
                    page = page,
                    order = JmxMagicConstants.ORDER_BY_LATEST,
                    folderId = 0,
                )
                AccountCollectionKind.HISTORY -> core.libraryApi.watchList(page)
            }
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
        return accountRepository.withSessionRecovery { core.libraryApi.dailyInfo(id) }
    }

    suspend fun checkIn(profile: AccountProfile, dailyId: Int?): JmxResult<ActionResult> {
        val id = profile.id?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("用户资料缺少 UID", field = "uid"))
        val eventId = dailyId?.toString()
            ?: return JmxResult.Failure(JmxError.Schema("签到活动编号缺失", field = "dailyId"))
        return accountRepository.withSessionRecovery { core.libraryApi.dailyCheck(id, eventId) }
    }

    suspend fun autoCheckIn(profile: AccountProfile): Boolean {
        val info = (dailyInfo(profile) as? JmxResult.Success)?.value ?: return false
        if (info.isSignedToday()) return true
        return when (val result = checkIn(profile, info.dailyId)) {
            is JmxResult.Success -> result.value.isAcceptedCheckInResult()
            is JmxResult.Failure -> false
        }
    }
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
