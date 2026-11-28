package com.stragent.core.ports

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * outputSchema 的小工具 —— 只负责拼 JSON Schema，不做校验/推断。
 *
 * 为什么不用「从一次真实结果反推 schema」：那会把「当前实现刚好返回什么」
 * 固化成契约，实现漏字段时 schema 跟着一起错。契约要手写，测试再用真实
 * 结果对拍（见 McpToolContractTest）。
 */

/** 对象 schema：`{type:"object", properties:{...}, required:[...]}` */
fun schemaObject(
    vararg props: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { props.forEach { (name, schema) -> put(name, schema) } })
    if (required.isNotEmpty()) {
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

/** 字段 schema：`{type:"string", description:"..."}`；type 可为 string/number/integer/boolean/object/array */
fun schemaField(type: String, description: String? = null): JsonObject = buildJsonObject {
    put("type", type)
    if (description != null) put("description", description)
}

/** 数组字段 schema：`{type:"array", items:{...}}` */
fun schemaArrayField(items: JsonObject, description: String? = null): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", items)
    if (description != null) put("description", description)
}

/** 任意值字段：JSON Schema 的空 schema `{}`（值类型由工具描述约定） */
fun schemaAnyField(description: String? = null): JsonObject = buildJsonObject {
    if (description != null) put("description", description)
}
