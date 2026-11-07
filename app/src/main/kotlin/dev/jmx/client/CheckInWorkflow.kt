package dev.jmx.client

import dev.jmx.client.core.api.ActionResult
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult
import java.util.Locale

internal enum class CheckInAcceptance { SUBMITTED, ALREADY_SIGNED, REJECTED }

/** API status values are not the Python client's normalized result codes. */
internal fun ActionResult.checkInAcceptance(): CheckInAcceptance {
    val text = message.orEmpty().trim().lowercase(Locale.ROOT)
    val state = status.orEmpty().trim().lowercase(Locale.ROOT)
    if (listOf("今天已经签到过了", "今天已經簽到過了", "已经签到", "已經簽到", "已签到", "已簽到", "already checked in", "already signed")
            .any { it in text }) return CheckInAcceptance.ALREADY_SIGNED
    if (state in setOf("fail", "failed", "error", "false", "0") ||
        listOf("失败", "失敗", "异常", "異常", "拒绝", "拒絕", "未成功", "不成功", "无权限", "無權限").any { it in text }) {
        return CheckInAcceptance.REJECTED
    }
    return if (state in setOf("ok", "success", "true", "1") ||
        text == "签到成功" || text == "簽到成功" ||
        Regex("(?i)^(?:Jcoin|EXP):\\s*\\d+(?:\\s+(?:Jcoin|EXP):\\s*\\d+)*$").matches(text)) {
        CheckInAcceptance.SUBMITTED
    } else CheckInAcceptance.REJECTED
}

/** One submission, then a fresh read. An ambiguous write is never blindly replayed. */
internal suspend fun confirmedCheckIn(
    loadInfo: suspend () -> JmxResult<DailyCheckInfo?>,
    submit: suspend (Int) -> JmxResult<ActionResult>,
): JmxResult<ActionResult> {
    val initial = when (val result = loadInfo()) {
        is JmxResult.Failure -> return result
        is JmxResult.Success -> result.value ?: return JmxResult.Failure(JmxError.EmptyData("暂时无法确认签到活动，请稍后重试"))
    }
    if (initial.isSignedToday()) return confirmedCheckInAction("今日已签到")
    val eventId = initial.dailyId?.takeIf { it > 0 } ?: return JmxResult.Failure(JmxError.Schema("签到活动编号缺失", field = "dailyId"))
    val response = submit(eventId)
    if (response is JmxResult.Success && response.value.checkInAcceptance() == CheckInAcceptance.REJECTED) {
        return JmxResult.Failure(JmxError.Api(400, response.value.message ?: "签到未得到明确确认"))
    }
    if (response is JmxResult.Failure && response.error !is JmxError.EmptyData &&
        response.error !is JmxError.Network && response.error !is JmxError.Schema) return response
    return when (val verified = loadInfo()) {
        is JmxResult.Failure -> verified
        is JmxResult.Success -> if (verified.value?.isSignedToday() == true) {
            confirmedCheckInAction(if (response is JmxResult.Success && response.value.checkInAcceptance() == CheckInAcceptance.ALREADY_SIGNED) "今日已签到" else "签到成功")
        } else JmxResult.Failure(JmxError.EmptyData("服务端尚未确认今日签到，未重复提交，请稍后刷新"))
    }
}

private fun confirmedCheckInAction(message: String): JmxResult<ActionResult> =
    JmxResult.Success(ActionResult("ok", message, null, emptyMap()))
