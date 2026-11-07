package dev.jmx.client

import dev.jmx.client.effect.FloatingNavBarStyle
import dev.jmx.client.effect.TopBarBlurStyle
import org.mockito.Mockito
import org.mockito.Answers
import java.util.concurrent.atomic.AtomicInteger

internal object VisualFixtures {
    val history = listOf("春日旅行", "星空与海", "校园日常", "很长的搜索历史标题用于观察触摸目标与单行动画", "城市记忆", "雨后的书店")
    val albums = (1..8).map {
        HomeAlbum("fixture-$it", "本地测试作品 $it", "测试作者", "file:///android_asset/visual-not-requested.png", "https://images.invalid")
    }
    val page = ComicSearchResult.Page(albums, 8, true)
    val apiUrls = listOf(
        "https://api-a.example.invalid",
        "https://a-very-long-route-host-name-for-layout-reproduction.example.invalid",
        "https://api-c.example.invalid",
        "https://api-d.example.invalid",
        "https://api-e.example.invalid",
    )
    val imageUrls = listOf(
        "https://img-a.example.invalid",
        "https://a-very-long-image-source-host-name-for-layout-reproduction.example.invalid",
        "https://img-c.example.invalid",
        "https://img-d.example.invalid",
        "https://img-e.example.invalid",
    )
    fun probe(url: String): EndpointProbeUi {
        val index = (apiUrls + imageUrls).indexOf(url)
        return when (index % 3) {
            0 -> EndpointProbeUi(url, 123, true)
            1 -> EndpointProbeUi(url, 4200, true)
            else -> EndpointProbeUi(url, 2500, false)
        }
    }
    fun settingsRepository(): AppSettingsRepository {
        var snapshot = EndpointSettingsState(
            automatic = false,
            selectedApiUrl = apiUrls[1], activeApiUrl = apiUrls[1], selectedImageUrl = imageUrls[1],
            apiEndpoints = apiUrls.map(::EndpointProbeUi), imageEndpoints = imageUrls.map(::EndpointProbeUi),
        )
        return Mockito.mock(AppSettingsRepository::class.java) { call ->
            when (call.method.name) {
                "contentLanguage" -> CONTENT_LANGUAGE_SIMPLIFIED
                "topBarBlurStyle" -> TopBarBlurStyle.GAUSSIAN
                "floatingNavBarStyle" -> FloatingNavBarStyle.entries.first()
                "imageCacheSizeBytes" -> 0L
                "endpointSnapshot" -> snapshot
                "probeApiEndpoint", "probeImageEndpoint" -> probe(call.arguments[0] as String)
                "useAutomaticApi" -> { snapshot = snapshot.copy(automatic = true, selectedApiUrl = null); null }
                "useAutomaticImageHost" -> { snapshot = snapshot.copy(selectedImageUrl = null); null }
                "useImageHost" -> { snapshot = snapshot.copy(selectedImageUrl = call.arguments[0] as String); null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
    }
}
