package dev.jmx.client.data.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import dev.jmx.client.cache.getCommonPicDecodeCacheDir
import dev.jmx.client.cache.trimPicDecodeCache
import dev.jmx.client.utils.md5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class ImageResultState {
    object Loading : ImageResultState()
    data class Success(
        val decodeImageBitmap: ImageBitmap,
        val decodeImageAspectRatio: Float
    ) :
        ImageResultState()

    data class Failure(val reason: String) : ImageResultState()
}

class AlbumImageImageState(
    val index: Int,
    val albumId: Int,
    val originSrc: String,
    val __scrambleId: Int,
    val __speed: String,
    private val imageLoader: ImageLoader,
) {

    companion object {
        private val seedMap = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
        private const val DEFAULT_DECODE_ATTEMPTS = 3
        private const val RETRY_DELAY_BASE_MS = 320L
    }

    var imageResultState by mutableStateOf<ImageResultState>(ImageResultState.Loading)

    suspend fun decode(context: Context, maxAttempts: Int = DEFAULT_DECODE_ATTEMPTS) {
        withContext(Dispatchers.Default) {
            imageResultState = ImageResultState.Loading
            val attempts = maxAttempts.coerceAtLeast(1)
            var lastReason = "网络错误"
            repeat(attempts) { attemptIndex ->
                val result = runCatching {
                    decodeImage(context)
                }.getOrElse {
                    Result.failure(it)
                }
                if (result.isSuccess) {
                    return@withContext
                }
                val throwable = result.exceptionOrNull()
                lastReason = throwable?.localizedMessage?.takeIf { it.isNotBlank() } ?: lastReason
                Log.d(
                    "album pic",
                    "decode index=$index attempt=${attemptIndex + 1}/$attempts failed: " +
                        throwable?.stackTraceToString()
                )
                if (attemptIndex < attempts - 1) {
                    delay(RETRY_DELAY_BASE_MS * (attemptIndex + 1))
                }
            }
            imageResultState = ImageResultState.Failure("加载失败，已自动重试 $attempts 次\n$lastReason")
        }
    }

    private suspend fun decodeImage(context: Context): Result<Unit> {
        val cacheDir = getCommonPicDecodeCacheDir(context, albumId)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val page = extractPageFromUrl()
        val cacheFile = File(cacheDir, "$page.webp")

        // 检查缓存文件是否存在
        if (cacheFile.exists()) {
            val cacheBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                ?: run {
                    cacheFile.delete()
                    return Result.failure(IllegalStateException("缓存图片损坏，已重新请求"))
                }
            val decodeImageBitmap = cacheBitmap.asImageBitmap()
            val decodeImageAspectRatio =
                decodeImageBitmap.width * 1.0f / decodeImageBitmap.height
            imageResultState = ImageResultState.Success(decodeImageBitmap, decodeImageAspectRatio)
            return Result.success(Unit)
        }

        // 加载原始图片
        val request = ImageRequest.Builder(context)
            .data(originSrc)
            // 这里必须使用原始 size ，不然解密会有问题，出现白线
            .size { Size.ORIGINAL }
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()

        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                val originalBitmap = result.drawable.toBitmap()
                val originalImageBitmap = originalBitmap.asImageBitmap()
                val decodeImageAspectRatio =
                    originalImageBitmap.width * 1.0f / originalImageBitmap.height
                var decodedImageBitmap = originalImageBitmap
                if (isGif() || albumId <= __scrambleId || __speed == "1") {
                    saveBitmapAsWebp(originalBitmap, cacheFile)
                } else {
                    val decodedBitmap = decodeBitmap(originalBitmap, page)
                    saveBitmapAsWebp(decodedBitmap, cacheFile)
                    decodedImageBitmap = decodedBitmap.asImageBitmap()
                }
                trimPicDecodeCache(context)
                imageResultState =
                    ImageResultState.Success(decodedImageBitmap, decodeImageAspectRatio)
                Result.success(Unit)
            }

            is ErrorResult -> {
                Log.d("album pic", result.throwable.stackTraceToString())
                Result.failure(result.throwable)
            }
        }
    }

    private fun decodeBitmap(originalBitmap: Bitmap, page: String): Bitmap {
        val naturalWidth = originalBitmap.width
        val naturalHeight = originalBitmap.height
        val seed = calculateSeed(albumId, page)
        val remainder = naturalHeight % seed

        val decodedBitmap =
            createBitmap(naturalWidth, naturalHeight)
        val canvas = Canvas(decodedBitmap.asImageBitmap())
        val paint = Paint().apply {
            this.isAntiAlias = false
        }
        val originImageBitmap = originalBitmap.asImageBitmap()

        for (i in 0 until seed) {
            var height = naturalHeight / seed
            var dy = height * i
            val sy = naturalHeight - height * (i + 1) - remainder
            if (i == 0) {
                height += remainder
            } else {
                dy += remainder
            }

            val srcOffset = IntOffset(0, sy)
            val srcSize = IntSize(naturalWidth, height)
            val destOffset = IntOffset(0, dy)
            val destSize = IntSize(naturalWidth, height)

            canvas.drawImageRect(
                originImageBitmap,
                srcOffset,
                srcSize,
                destOffset,
                destSize,
                paint
            )
        }

        return decodedBitmap
    }

    private fun calculateSeed(albumId: Int, pageStr: String): Int {
        val key = "$albumId$pageStr"
        val keyMd5 = md5(key)
        var charCodeOfLastChar = keyMd5.last().code
        val left = 268850
        val right = 421925

        when {
            albumId in left..right -> charCodeOfLastChar %= 10
            albumId >= right + 1 -> charCodeOfLastChar %= 8
        }

        return seedMap.getOrNull(charCodeOfLastChar) ?: 10
    }

    private fun extractPageFromUrl(): String {
        return originSrc.substringAfterLast('/').substringBeforeLast('.')
    }

    private suspend fun saveBitmapAsWebp(bitmap: Bitmap, file: File) {
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 50, out)
            }
        }
    }

    private fun isGif(): Boolean {
        return originSrc.endsWith(".gif")
    }
}
