package dev.jmx.client.ui.viewModel

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import dev.jmx.client.data.models.AlbumImageImageState
import dev.jmx.client.data.models.ImageResultState
import dev.jmx.client.repository.AlbumRepository
import dev.jmx.client.data.remote.model.AlbumImageListResponse
import dev.jmx.client.data.remote.model.NetworkResult
import dev.jmx.client.store.LocalSettingManager
import dev.jmx.client.ui.models.CommonUIState
import dev.jmx.client.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class AlbumReadViewModel(
    private val albumRepository: AlbumRepository,
    private val imageLoader: ImageLoader,
    private val localSettingManager: LocalSettingManager,
) : ViewModel() {
    var isShowToolBar = mutableStateOf(false)
    var currentIndexState = mutableIntStateOf(0)
    private val _albumImageState = MutableStateFlow(
        CommonUIState<List<AlbumImageImageState>>(
            isLoading = true
        )
    )
    val albumImageState = _albumImageState.asStateFlow()

    val size: Int get() = _albumImageState.value.data?.size ?: 0

    private val prefetchSet = mutableSetOf<Int>()

    fun getAlbumImageList(albumId: Int, shunt: String, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            currentIndexState.intValue = 0
            isShowToolBar.value = false
            prefetchSet.clear()
            _albumImageState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    data = null,
                    errorMsg = ""
                )
            }
            when (val data = albumRepository.getAlbumImageList(albumId, shunt)) {
                is NetworkResult.Error -> {
                    _albumImageState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetworkResult.Success<AlbumImageListResponse> -> {
                    _albumImageState.update {
                        it.copy(
                            data = data.data.list.mapIndexed { index, item ->
                                AlbumImageImageState(
                                    index,
                                    albumId,
                                    item,
                                    data.data.__scrambleId,
                                    data.data.__speed,
                                    imageLoader,
                                )
                            }
                        )
                    }
                    onSuccess?.invoke()
                }
            }
            _albumImageState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }
    fun decodeIndex(index: Int, context: Context) {
        if (size <= 0) return
        val targetIndex = index.coerceIn(0, size - 1)
        log("decode index $targetIndex")
        val setting = localSettingManager.localSettingState.value
        val count = setting.prefetchCount.coerceAtLeast(0)
        decode(targetIndex, context) {
            if (count <= 0) return@decode
            when (setting.imageLoadStrategy) {
                "nearby" -> decodeNearby(targetIndex, count, context)
                else -> decodeAhead(targetIndex, count, context)
            }
        }
    }

    private fun decodeNearby(index: Int, count: Int, context: Context) {
        val start = max(0, index - count)
        val end = min(size - 1, index + count)
        for (i in index + 1..end) {
            log("pre decode nearby index $i")
            decode(i, context)
        }
        for (i in index - 1 downTo start) {
            log("pre decode nearby index $i")
            decode(i, context)
        }
    }

    private fun decodeAhead(index: Int, count: Int, context: Context) {
        val aheadCount = max(count * 2, count + 2)
        val end = min(size - 1, index + aheadCount)
        for (i in index + 1..end) {
            log("pre decode ahead index $i")
            decode(i, context)
        }
        val behindStart = max(0, index - 1)
        for (i in index - 1 downTo behindStart) {
            log("pre decode keep-behind index $i")
            decode(i, context)
        }
    }

    fun prev(context: Context) {
        hideToolBar()
        val index = max(0, currentIndexState.intValue - 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    fun next(context: Context) {
        hideToolBar()
        val index = min(size - 1, currentIndexState.intValue + 1)
        currentIndexState.intValue = index
        decodeIndex(index, context)
    }

    private fun decode(index: Int, context: Context, onComplete: (() -> Unit)? = null) {
        val albumImageImageState = albumImageState.value.data?.getOrNull(index) ?: return
        val failed = albumImageImageState.imageResultState is ImageResultState.Failure
        if (prefetchSet.contains(index) && !failed) {
            onComplete?.invoke()
            return
        }
        prefetchSet.add(index)
        viewModelScope.launch {
            albumImageImageState.decode(context)
            if (albumImageImageState.imageResultState is ImageResultState.Failure) {
                prefetchSet.remove(index)
            }
            onComplete?.invoke()
        }
    }

    fun triggerToolBar() {
        isShowToolBar.value = !isShowToolBar.value
    }

    fun hideToolBar() {
        isShowToolBar.value = false
    }

    fun showToolBar() {
        isShowToolBar.value = true
    }
}
