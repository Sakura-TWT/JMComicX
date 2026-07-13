package dev.jmx.client.core.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal fun JsonObject.stringOrNull(vararg names: String): String? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive) {
            return runCatching { value.asString }.getOrNull()
        }
    }
    return null
}

internal fun JsonObject.intOrNull(vararg names: String): Int? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive) {
            return runCatching { value.asInt }.getOrNull()
        }
    }
    return null
}

internal fun JsonObject.booleanOrNull(vararg names: String): Boolean? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive) {
            return runCatching { value.asBoolean }.getOrNull()
        }
    }
    return null
}

internal fun JsonObject.stringListOrEmpty(vararg names: String): List<String> {
    for (name in names) {
        val value = get(name) ?: continue
        if (value.isJsonArray) {
            return value.asJsonArray
                .mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
                }
                .filter { it.isNotBlank() }
        }
        if (value.isJsonPrimitive) {
            val text = runCatching { value.asString }.getOrNull()
            if (!text.isNullOrBlank()) return listOf(text)
        }
    }
    return emptyList()
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? {
    return this?.takeIf { it.isJsonObject }?.asJsonObject
}

internal fun JsonElement?.asObjectListOrEmpty(): List<JsonObject> {
    return this
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { it.asObjectOrNull() }
        ?: emptyList()
}

internal fun JsonObject.toRawMap(): Map<String, Any?> {
    return entrySet().associate { (key, value) ->
        key to when {
            value.isJsonNull -> null
            value.isJsonPrimitive -> {
                val primitive = value.asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                }
            }
            else -> value.toString()
        }
    }
}
