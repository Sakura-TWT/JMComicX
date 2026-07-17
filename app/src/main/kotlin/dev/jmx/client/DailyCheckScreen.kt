package dev.jmx.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jmx.client.core.api.DailyCheckInfo
import dev.jmx.client.core.result.JmxResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.util.Calendar

internal data class DailyCalendarCell(
    val day: Int?,
    val signed: Boolean = false,
    val bonus: Boolean = false,
    val today: Boolean = false,
)

@Composable
internal fun DailyCheckScreen(
    innerPadding: PaddingValues,
    profile: AccountProfile,
    repository: AccountDataRepository,
) {
    var state by remember(profile.id) { mutableStateOf<DailyUiState>(DailyUiState.Loading) }
    var retryKey by remember(profile.id) { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun reload() {
        state = when (val result = repository.dailyInfo(profile)) {
            is JmxResult.Success -> DailyUiState.Content(result.value)
            is JmxResult.Failure -> DailyUiState.Error(result.error.toUiMessage())
        }
    }

    LaunchedEffect(profile.id, retryKey, repository) { reload() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
    ) {
        when (val current = state) {
            DailyUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is DailyUiState.Error -> CollectionMessageForDaily(
                current.message,
                onRetry = { retryKey++ },
            )
            is DailyUiState.Content -> {
                val month = remember(current.info) { currentMonthCalendar(current.info) }
                val streak = remember(current.info) { currentDailyStreak(current.info) }
                val cycleProgress = dailyRewardCycleProgress(streak)
                val todaySigned = month.cells.any { it.today && it.signed }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        top = innerPadding.calculateTopPadding() + 18.dp,
                        end = 18.dp,
                        bottom = innerPadding.calculateBottomPadding() + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item(key = "daily-title") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = buildString {
                                    append("${month.month}月")
                                    cleanDailyEventName(current.info.eventName, month.month)?.let {
                                        append(" · ")
                                        append(it)
                                    }
                                },
                                style = MiuixTheme.textStyles.title2,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "已连续签到 $streak 天",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    item(key = "daily-calendar") {
                        DailyCalendar(month)
                    }
                    item(key = "daily-progress") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "本轮连续签到奖励",
                                    style = MiuixTheme.textStyles.body2,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "$cycleProgress/7",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Spacer(modifier = Modifier.size(10.dp))
                            LinearProgressIndicator(
                                progress = cycleProgress / 7f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.size(16.dp))
                            DailyRewardMilestone(
                                day = 3,
                                coin = current.info.threeDaysCoin ?: DEFAULT_THREE_DAY_REWARD,
                                experience = current.info.threeDaysExp ?: DEFAULT_THREE_DAY_REWARD,
                                achieved = cycleProgress >= 3,
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            DailyRewardMilestone(
                                day = 7,
                                coin = current.info.sevenDaysCoin ?: DEFAULT_SEVEN_DAY_REWARD,
                                experience = current.info.sevenDaysExp ?: DEFAULT_SEVEN_DAY_REWARD,
                                achieved = cycleProgress >= 7,
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Text(
                                text = "完成第 7 天后，下一次签到从新一轮第 1 天继续累计。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    item(key = "daily-action") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = when {
                                    checking -> "签到中"
                                    todaySigned -> "今日已签到"
                                    else -> "签到"
                                },
                                enabled = !checking && !todaySigned,
                                onClick = {
                                    if (!checking && !todaySigned) {
                                        checking = true
                                        actionMessage = null
                                        coroutineScope.launch {
                                            when (val result = repository.checkIn(profile, current.info.dailyId)) {
                                                is JmxResult.Success -> {
                                                    val rejection = result.value.rejectionMessageOrNull()
                                                    if (rejection == null) {
                                                        actionMessage = result.value.message ?: "签到成功"
                                                        reload()
                                                    } else {
                                                        actionMessage = rejection
                                                    }
                                                }
                                                is JmxResult.Failure -> {
                                                    actionMessage = result.error.toUiMessage()
                                                }
                                            }
                                            checking = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                            actionMessage?.let {
                                Text(
                                    text = it,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyRewardMilestone(
    day: Int,
    coin: Int,
    experience: Int,
    achieved: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (achieved) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MiuixTheme.colorScheme.surfaceContainerHigh,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (achieved) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = "第 $day 天奖励已达成",
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = day.toString(),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "连续签到 $day 天",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "$coin 金币 + $experience 经验",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun DailyCalendar(month: DailyCalendarMonth) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEK_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        month.cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    day.today -> MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    day.signed -> MiuixTheme.colorScheme.surfaceContainerHigh
                                    else -> MiuixTheme.colorScheme.surface
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        day.day?.let {
                            Text(
                                text = it.toString(),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            if (day.signed) {
                                Icon(
                                    imageVector = MiuixIcons.Basic.Check,
                                    contentDescription = "已签到",
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(12.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                            if (day.bonus) {
                                Icon(
                                    imageVector = MiuixIcons.FavoritesFill,
                                    contentDescription = "额外奖励",
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(11.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class DailyCalendarMonth(
    val year: Int,
    val month: Int,
    val cells: List<DailyCalendarCell>,
)

internal fun currentDailyStreak(
    info: DailyCheckInfo,
    now: Calendar = Calendar.getInstance(),
): Int {
    val signedDates = info.records
        .asSequence()
        .filter { it.signed == true }
        .mapNotNull { record ->
            record.date?.trim()?.let { date ->
                runCatching { LocalDate.parse(date.take(10)) }.getOrNull()
                    ?: date.toIntOrNull()
                        ?.takeIf { it in 1..31 }
                        ?.let { day ->
                            runCatching {
                                LocalDate.of(
                                    now.get(Calendar.YEAR),
                                    now.get(Calendar.MONTH) + 1,
                                    day,
                                )
                            }.getOrNull()
                        }
            }
        }
        .toSet()
    if (signedDates.isEmpty()) return 0

    val today = LocalDate.of(
        now.get(Calendar.YEAR),
        now.get(Calendar.MONTH) + 1,
        now.get(Calendar.DAY_OF_MONTH),
    )
    var cursor = if (today in signedDates) today else today.minusDays(1)
    var streak = 0
    while (cursor in signedDates) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

internal fun dailyRewardCycleProgress(streak: Int): Int {
    if (streak <= 0) return 0
    return (streak - 1) % 7 + 1
}

internal fun currentMonthCalendar(info: DailyCheckInfo, now: Calendar = Calendar.getInstance()): DailyCalendarMonth {
    val year = now.get(Calendar.YEAR)
    val month = now.get(Calendar.MONTH) + 1
    val first = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }
    val leading = (first.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val records = info.records.mapNotNull { record ->
        val day = record.date?.substringAfterLast('-')?.toIntOrNull() ?: return@mapNotNull null
        day to record
    }.toMap()
    val today = if (now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) + 1 == month) {
        now.get(Calendar.DAY_OF_MONTH)
    } else {
        -1
    }
    val cells = buildList {
        repeat(leading) { add(DailyCalendarCell(null)) }
        for (day in 1..days) {
            val record = records[day]
            add(
                DailyCalendarCell(
                    day = day,
                    signed = record?.signed == true,
                    bonus = record?.bonus == true,
                    today = day == today,
                ),
            )
        }
        while (size % 7 != 0 || size < 42) add(DailyCalendarCell(null))
    }
    return DailyCalendarMonth(year, month, cells)
}

internal fun cleanDailyEventName(eventName: String?, month: Int): String? {
    val normalized = eventName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return normalized
        .replace(Regex("^${month}月[\\s·._-]*"), "")
        .trim()
        .takeIf { it.isNotEmpty() }
}

@Composable
private fun CollectionMessageForDaily(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        TextButton(text = "重试", onClick = onRetry)
    }
}

private sealed interface DailyUiState {
    data object Loading : DailyUiState
    data class Error(val message: String) : DailyUiState
    data class Content(val info: DailyCheckInfo) : DailyUiState
}

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
private const val DEFAULT_THREE_DAY_REWARD = 150
private const val DEFAULT_SEVEN_DAY_REWARD = 350
