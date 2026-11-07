package dev.jmx.client.core.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.jmx.client.core.protocol.JmId
import dev.jmx.client.core.result.JmxError
import dev.jmx.client.core.result.JmxResult

/** An explicit empty array is evidence of emptiness; a missing/malformed list is not. */
internal fun JsonElement.discoveryPage(): JmxResult<AlbumPage> {
    val root = asObjectOrNull()
    val list = if (isJsonArray) asJsonArray else root?.explicitArray("content", "list", "data", "albums", "photos")
        ?: return schema("发现列表缺失或格式异常", "content")
    if (list.any { !it.isJsonObject }) return schema("发现列表包含不明条目", "content")
    val albums = ArrayList<AlbumSummary>(list.size())
    for (item in list) {
        val album = item.asJsonObject.toAlbumSummary()
        val id = when (val parsed = JmId.parse(album.id)) {
            is JmxResult.Success -> parsed.value
            is JmxResult.Failure -> return schema("发现列表缺少有效漫画编号", "id")
        }
        albums.add(album.copy(id = id))
    }
    val total = root?.get("total")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
    if (root?.get("total")?.takeUnless { it.isJsonNull } != null && total == null) return schema("发现列表总数格式异常", "total")
    if (total != null && (total < 0 || total < albums.size)) return schema("发现列表总数不一致", "total")
    return JmxResult.Success(AlbumPage(total, albums, root?.toRawMap() ?: emptyMap()))
}

internal fun JsonObject.discoveryWeekInfo(): JmxResult<WeekInfo> {
    val categories = explicitArray("categories", "category") ?: return schema("每周必看期刊列表缺失", "categories")
    val types = explicitArray("type", "types") ?: return schema("每周必看类型列表缺失", "type")
    if (categories.any { !it.isJsonObject || it.asJsonObject.explicitId() == null } || types.any { !it.isJsonObject || it.asJsonObject.explicitId() == null }) {
        return schema("每周必看筛选项格式异常", "id")
    }
    return JmxResult.Success(WeekInfo(
        categories = categories.map { item -> item.asJsonObject.let { WeekCategory(it.explicitId()!!, it.stringOrNull("time"), it.stringOrNull("title", "name")) } }.distinctBy { it.id },
        types = types.map { item -> item.asJsonObject.let { WeekType(it.explicitId()!!, it.stringOrNull("title", "name")) } }.distinctBy { it.id },
        raw = toRawMap(),
    ))
}

private fun JsonObject.explicitArray(vararg keys: String): JsonArray? {
    for (key in keys) {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: continue
        return value.takeIf { it.isJsonArray }?.asJsonArray
    }
    return null
}

private fun JsonObject.explicitId(): String? = get("id")?.takeIf {
    it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean
}?.asString?.trim()?.takeIf { it.isNotEmpty() }

private fun schema(message: String, field: String): JmxResult.Failure = JmxResult.Failure(JmxError.Schema(message, field = field))
