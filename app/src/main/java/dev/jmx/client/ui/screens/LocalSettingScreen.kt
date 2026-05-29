package dev.jmx.client.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jmx.client.store.LocalSettingManager
import dev.jmx.client.ui.components.CommonScaffold
import dev.jmx.client.ui.components.SelectDialog
import dev.jmx.client.ui.components.SelectOption
import dev.jmx.client.ui.glass.LocalJmxGlassPalette
import dev.jmx.client.ui.razor.RazorIcon
import dev.jmx.client.ui.razor.RazorText
import org.koin.compose.getKoin

private sealed class SettingType {
    object Theme : SettingType()
    object Api : SettingType()
    object Shunt : SettingType()
    object PrefetchCount : SettingType()
    object ImageLoadStrategy : SettingType()
    object ReadMode : SettingType()
}

private val themeTextMap = mapOf(
    "auto" to "跟随系统",
    "light" to "浅色",
    "dark" to "深色",
)

private val themeDescriptionMap = mapOf(
    "auto" to "根据系统外观自动切换",
    "light" to "始终使用浅色界面",
    "dark" to "始终使用深色界面",
)

private val imageLoadStrategyTextMap = mapOf(
    "ahead" to "提前加载",
    "nearby" to "附近加载",
)

@Composable
private fun GroupHeader(text: String) {
    val palette = LocalJmxGlassPalette.current
    RazorText(
        text = text,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        style = TextStyle(
            color = palette.tertiaryText,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
}

@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    val palette = LocalJmxGlassPalette.current
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(
            color = palette.tertiaryText,
            start = Offset(size.width * 0.36f, size.height * 0.22f),
            end = Offset(size.width * 0.68f, size.height * 0.50f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = palette.tertiaryText,
            start = Offset(size.width * 0.68f, size.height * 0.50f),
            end = Offset(size.width * 0.36f, size.height * 0.78f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalJmxGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.contentSurface.copy(alpha = 0.74f)),
        content = content
    )
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    icon: ImageVector,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalJmxGlassPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.08f else 0f,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMedium),
        label = "settingsRowPress"
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.primaryText.copy(alpha = pressAlpha))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                RazorIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = Color.White
                )
            }
            RazorText(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = palette.primaryText,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.2).sp
                )
            )
            RazorText(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = palette.tertiaryText,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.1).sp
                )
            )
            Chevron()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth()
                    .padding(start = 58.dp)
                    .background(palette.primaryText.copy(alpha = 0.08f))
                    .height(0.6.dp)
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        GroupHeader(title)
        SettingsGroup(content = content)
    }
}

@Composable
fun LocalSettingScreen(
    localSettingManager: LocalSettingManager = getKoin().get()
) {
    val localSetting by localSettingManager.localSettingState.collectAsState()
    var settingType by remember { mutableStateOf<SettingType>(SettingType.Theme) }
    var isOpenSettingSelectDialog by remember { mutableStateOf(false) }

    CommonScaffold(title = "设置") {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalJmxGlassPalette.current.page),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            item {
                SettingsSection(title = "外观") {
                    SettingsRow(
                        title = "显示模式",
                        value = themeTextMap[localSetting.theme] ?: "跟随系统",
                        icon = Icons.Outlined.WbSunny,
                        showDivider = false,
                        onClick = {
                            settingType = SettingType.Theme
                            isOpenSettingSelectDialog = true
                        }
                    )
                }
            }
            item {
                SettingsSection(title = "网络") {
                    SettingsRow(
                        title = "API 接口",
                        value = localSetting.api.removePrefix("https://"),
                        icon = Icons.Outlined.CloudQueue,
                        showDivider = true,
                        onClick = {
                            settingType = SettingType.Api
                            isOpenSettingSelectDialog = true
                        }
                    )
                    SettingsRow(
                        title = "图片线路",
                        value = "线路 ${localSetting.shunt}",
                        icon = Icons.Outlined.Route,
                        showDivider = false,
                        onClick = {
                            settingType = SettingType.Shunt
                            isOpenSettingSelectDialog = true
                        }
                    )
                }
            }
            item {
                SettingsSection(title = "阅读") {
                    SettingsRow(
                        title = "图片预载",
                        value = if (localSetting.prefetchCount == 0) "关闭" else "${localSetting.prefetchCount} 张",
                        icon = Icons.Outlined.Image,
                        showDivider = true,
                        onClick = {
                            settingType = SettingType.PrefetchCount
                            isOpenSettingSelectDialog = true
                        }
                    )
                    SettingsRow(
                        title = "漫画加载",
                        value = imageLoadStrategyTextMap[localSetting.imageLoadStrategy] ?: "提前加载",
                        icon = Icons.Outlined.Speed,
                        showDivider = true,
                        onClick = {
                            settingType = SettingType.ImageLoadStrategy
                            isOpenSettingSelectDialog = true
                        }
                    )
                    SettingsRow(
                        title = "阅读模式",
                        value = if (localSetting.readMode == "scroll") "滚动" else "翻页",
                        icon = Icons.Outlined.Swipe,
                        showDivider = false,
                        onClick = {
                            settingType = SettingType.ReadMode
                            isOpenSettingSelectDialog = true
                        }
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (isOpenSettingSelectDialog) {
            val themeOptionList by remember(localSetting.themeList) {
                derivedStateOf {
                    localSetting.themeList.map {
                        SelectOption(
                            label = themeTextMap[it] ?: it,
                            value = it,
                            description = themeDescriptionMap[it]
                        )
                    }
                }
            }
            val apiSelectOptionList by remember(localSetting.apiList) {
                derivedStateOf {
                    localSetting.apiList.mapIndexed { index, api ->
                        SelectOption(
                            label = "接口 ${index + 1}",
                            value = api,
                            description = api.removePrefix("https://")
                        )
                    }
                }
            }
            val shuntOptionList by remember(localSetting.shuntList) {
                derivedStateOf {
                    localSetting.shuntList.map {
                        SelectOption("线路 $it", it)
                    }
                }
            }
            val prefetchCountOptionList by remember {
                derivedStateOf {
                    listOf(
                        SelectOption("关闭", "0"),
                        SelectOption("1 张", "1"),
                        SelectOption("2 张", "2"),
                        SelectOption("3 张", "3")
                    )
                }
            }
            val imageLoadStrategyOptionList by remember {
                derivedStateOf {
                    listOf(
                        SelectOption("提前加载", "ahead", "优先加载后续页面，适合连续快速阅读"),
                        SelectOption("附近加载", "nearby", "保留当前页前后均衡预载算法")
                    )
                }
            }
            val readModeOptionList by remember {
                derivedStateOf {
                    listOf(
                        SelectOption("滚动", "scroll", "适合长条漫画"),
                        SelectOption("翻页", "page", "左右切换阅读")
                    )
                }
            }
            val title = when (settingType) {
                SettingType.Theme -> "显示模式"
                SettingType.Api -> "API 接口"
                SettingType.Shunt -> "图片线路"
                SettingType.PrefetchCount -> "图片预载"
                SettingType.ImageLoadStrategy -> "漫画加载"
                SettingType.ReadMode -> "阅读模式"
            }
            val value = when (settingType) {
                SettingType.Theme -> localSetting.theme
                SettingType.Api -> localSetting.api
                SettingType.Shunt -> localSetting.shunt
                SettingType.PrefetchCount -> "${localSetting.prefetchCount}"
                SettingType.ImageLoadStrategy -> localSetting.imageLoadStrategy
                SettingType.ReadMode -> localSetting.readMode
            }
            val selectOptionList = when (settingType) {
                SettingType.Theme -> themeOptionList
                SettingType.Api -> apiSelectOptionList
                SettingType.Shunt -> shuntOptionList
                SettingType.PrefetchCount -> prefetchCountOptionList
                SettingType.ImageLoadStrategy -> imageLoadStrategyOptionList
                SettingType.ReadMode -> readModeOptionList
            }

            SelectDialog(
                title = title,
                value = value,
                selectOptionList = selectOptionList,
                onSelect = {
                    when (settingType) {
                        SettingType.Theme -> localSettingManager.updateTheme(it)
                        SettingType.Api -> localSettingManager.updateApi(it)
                        SettingType.Shunt -> localSettingManager.updateShunt(it)
                        SettingType.PrefetchCount -> localSettingManager.updatePrefetchCount(it)
                        SettingType.ImageLoadStrategy -> localSettingManager.updateImageLoadStrategy(it)
                        SettingType.ReadMode -> localSettingManager.updateReadMode(it)
                    }
                    isOpenSettingSelectDialog = false
                },
                onDismissRequest = {
                    isOpenSettingSelectDialog = false
                }
            )
        }
    }
}
