package com.stragent.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP 工具参数的读取助手（内部共享）。
 *
 * 约定：只接受**真正的 JSON 字符串**（`isString`）——数字/布尔当字符串读会得到奇怪的静默转换，
 * 与其猜，不如当作没传（走默认值或报中文错误）。
 */
internal fun JsonObject.string(key: String): String? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotBlank() }
}

internal fun JsonObject.double(key: String): Double? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()
}

internal fun JsonObject.int(key: String): Int? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.content.toIntOrNull()
}
