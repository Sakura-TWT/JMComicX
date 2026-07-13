package dev.jmx.client.core.chapter

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.Strictness
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

data class ChapterTemplate(
    val albumId: Int,
    val scrambleId: Int,
    val speed: String,
    val imageHost: String,
    val chapterId: String,
    val cacheSuffix: String,
    val imageFileNames: List<String>
) {
    val imageUrls: List<String> = imageFileNames.map { fileName ->
        "${imageHost.trimEnd('/')}/media/photos/$chapterId/$fileName$cacheSuffix"
    }
}

data class ChapterTemplateInspection(
    val hasResultObject: Boolean,
    val hasConfigObject: Boolean,
    val hasImages: Boolean,
    val imageCount: Int,
    val hasImageHost: Boolean,
    val hasChapterId: Boolean,
    val hasCacheSuffix: Boolean,
    val hasAlbumId: Boolean,
    val hasScrambleId: Boolean,
    val hasSpeed: Boolean
) {
    val missingFields: List<String> = buildList {
        if (!hasResultObject) add("result")
        if (!hasConfigObject) add("config")
        if (!hasImages) add("result.images")
        if (!hasImageHost) add("config.imghost")
        if (!hasChapterId) add("config.jmid")
        if (!hasCacheSuffix) add("config.cache")
        if (!hasAlbumId) add("aid")
        if (!hasScrambleId) add("scramble_id")
        if (!hasSpeed) add("speed")
    }
}

class ChapterTemplateParser(
    private val gson: Gson = Gson().newBuilder().setStrictness(Strictness.LENIENT).create()
) {
    fun parse(html: String): JmxResult<ChapterTemplate> {
        val resultObject = extractObject(html, "result").unwrapOrReturn { return it }
        val configObject = extractObject(html, "config").unwrapOrReturn { return it }
        val images = resultObject.imageNamesOrEmpty()
        if (images.isEmpty()) {
            return JmxResult.Failure(JmxError.Schema("章节模板缺少图片列表", field = "result.images"))
        }
        val imageHost = configObject.requiredString("imghost").unwrapOrReturn { return it }
        val chapterId = configObject.requiredString("jmid").unwrapOrReturn { return it }
        val cache = configObject.requiredString("cache").unwrapOrReturn { return it }
        val albumId = extractInt(html, "aid").unwrapOrReturn { return it }
        val scrambleId = extractInt(html, "scramble_id").unwrapOrReturn { return it }
        val speed = extractString(html, "speed").unwrapOrReturn { return it }
        return JmxResult.Success(
            ChapterTemplate(
                albumId = albumId,
                scrambleId = scrambleId,
                speed = speed,
                imageHost = imageHost,
                chapterId = chapterId,
                cacheSuffix = cache,
                imageFileNames = images
            )
        )
    }

    fun inspect(html: String): ChapterTemplateInspection {
        val resultObject = parseAssignedObjectOrNull(html, "result")
        val configObject = parseAssignedObjectOrNull(html, "config")
        val imageCount = resultObject?.imageNamesOrEmpty()?.size ?: 0
        return ChapterTemplateInspection(
            hasResultObject = resultObject != null,
            hasConfigObject = configObject != null,
            hasImages = imageCount > 0,
            imageCount = imageCount,
            hasImageHost = configObject.hasString("imghost"),
            hasChapterId = configObject.hasString("jmid"),
            hasCacheSuffix = configObject.hasString("cache"),
            hasAlbumId = findAssignedInt(html, "aid") != null,
            hasScrambleId = findAssignedInt(html, "scramble_id") != null,
            hasSpeed = findAssignedString(html, "speed") != null
        )
    }

    private fun extractObject(html: String, name: String): JmxResult<JsonObject> {
        val json = extractAssignedObject(html, name)
            ?: return JmxResult.Failure(JmxError.Schema("章节模板缺少 $name 对象", field = name))
        return runCatching { gson.fromJson(json, JsonObject::class.java) }.fold(
            onSuccess = { JmxResult.Success(it) },
            onFailure = { JmxResult.Failure(JmxError.Schema("章节模板 $name 对象解析失败", field = name, cause = it)) }
        )
    }

    private fun extractInt(html: String, name: String): JmxResult<Int> {
        val value = findAssignedInt(html, name)
            ?: return JmxResult.Failure(JmxError.Schema("章节模板缺少 $name", field = name))
        return JmxResult.Success(value)
    }

    private fun extractString(html: String, name: String): JmxResult<String> {
        val value = findAssignedString(html, name)
            ?: return JmxResult.Failure(JmxError.Schema("章节模板缺少 $name", field = name))
        return JmxResult.Success(value)
    }

    private fun JsonObject.requiredString(name: String): JmxResult<String> {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        return if (value == null) {
            JmxResult.Failure(JmxError.Schema("章节模板 config 缺少 $name", field = "config.$name"))
        } else {
            JmxResult.Success(value)
        }
    }

    private fun JsonObject.imageNamesOrEmpty(): List<String> {
        return get("images")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun JsonObject?.hasString(name: String): Boolean {
        return this?.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true
    }

    private inline fun <T> JmxResult<T>.unwrapOrReturn(returnFailure: (JmxResult.Failure) -> Nothing): T {
        return when (this) {
            is JmxResult.Success -> value
            is JmxResult.Failure -> returnFailure(this)
        }
    }

    private fun extractAssignedObject(html: String, name: String): String? {
        val assignment = Regex("""(?:const|let|var)\s+$name\s*=""").find(html) ?: return null
        val start = html.indexOf('{', assignment.range.last + 1).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString: Char? = null
        var escaping = false
        for (index in start until html.length) {
            val char = html[index]
            if (inString != null) {
                if (escaping) {
                    escaping = false
                } else if (char == '\\') {
                    escaping = true
                } else if (char == inString) {
                    inString = null
                }
                continue
            }
            when (char) {
                '\'', '"' -> inString = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun parseAssignedObjectOrNull(html: String, name: String): JsonObject? {
        val json = extractAssignedObject(html, name) ?: return null
        return runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
    }

    private fun findAssignedInt(html: String, name: String): Int? {
        val regex = Regex("""(?:const|let|var)\s+$name\s*=\s*(\d+)\s*;?""")
        return regex.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun findAssignedString(html: String, name: String): String? {
        val regex = Regex("""(?:const|let|var)\s+$name\s*=\s*['"]([^'"]*)['"]\s*;?""")
        return regex.find(html)?.groupValues?.getOrNull(1)
    }
}
