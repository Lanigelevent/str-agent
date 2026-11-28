package com.stragent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-153 · 协议 transcript 快照（record/replay 回归门禁）。
 *
 * D24 审查 P1-7：协议序列化回归（JSON-RPC 字段、回执形状）此前无自动门禁——
 * 一次字段改名/新增字段都不会被任何测试拦住。本测试把**固定请求序列**的
 * 归一化响应冻结为金标夹具（`protocol/transcript.json`），回归逐次对拍：
 * 改帧形 → 快照红。漂移时用 `-Dstragent.transcript.write=true` 重生成夹具
 * （刻意变更，需 review）。
 *
 * ## 归一化口径（只抹平易变的值，形状漂移照抓）
 *
 * - 键 durationMs / elapsedMs / uptimeMs / costUsd 的数值 -> 0；
 * - 键 时间 / timestamp / generatedAt 的字符串 -> "TS"；
 * - ds_ 开头的十六进制串**不抹**（datasetId = 内容指纹，同输入确定同值）。
 *
 * ## 边界（诚实）
 *
 * 本快照覆盖 **dispatch 层**的帧形（stdio 与 HTTP 共用的那份实现）；HTTP 传输层的
 * 状态码/头/SSE 帧格式由 `McpHttpSseTest` 断言覆盖，不在本快照内。
 */
class ProtocolTranscriptTest {

    private val prefs = FilePrefs(Files.createTempDirectory("t153-prefs").toFile())

    /** 固定请求序列：覆盖 ping / initialize / 工具回执 / 未知方法四类帧形 */
    private val sequence: List<Pair<String, JsonObject>> = listOf(
        "ping" to JsonObject(emptyMap()),
        "initialize" to buildJsonObject {
            put("protocolVersion", "2025-06-18")
        },
        "tools/call" to buildJsonObject {
            put("name", "stragent_import_table")
            put("arguments", buildJsonObject {
                put("csv", "地区,销售额\n华东,1000\n华北,300")
                put("name", "门店")
            })
        },
        "tools/call" to buildJsonObject {
            put("name", "stragent_get_column_profile")
            put("arguments", buildJsonObject { put("column", "地区") })
        },
    )

    private fun bridge(): McpBridge = McpBridge(AbilityDatasets(), prefs, registry = SessionRegistry(AbilityDatasets()))

    /** 递归归一化：只抹平易变值，键名与结构漂移照抓 */
    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.associate { (k, v) ->
            when {
                k in VOLATILE_NUM_KEYS -> k to JsonPrimitive(0)
                k in VOLATILE_STR_KEYS -> k to JsonPrimitive("TS")
                else -> k to normalize(v)
            }
        })
        is JsonArray -> JsonArray(e.map { normalize(it) })
        is JsonPrimitive -> e
    }

    private fun canonical(e: JsonElement): String = normalize(e).toString()

    private fun fixturePath() = java.io.File(
        System.getProperty("user.dir"), "src/test/resources/protocol/transcript.json",
    )

    @Test
    fun `dispatch 帧形快照——与金标夹具逐字对拍`() {
        val b = bridge()
        val actual = sequence.map { (method, params) -> canonical(b.dispatch(method, params)) }
        val path = fixturePath()
        if (!path.isFile) {
            path.parentFile.mkdirs()
            path.writeText((listOf("# T-153 协议帧形金标（重生成：删掉本文件重跑自播种；刻意变更需 review）") + actual).joinToString("\n"))
            throw IllegalStateException("金标夹具已生成：" + path.absolutePath + " ——请 review 内容后入库；再次运行即逐字对拍")
        }
        val expected = path.readLines()
        assertTrue(expected.size == actual.size + 1, "金标夹具行数不符（请求序列变了？）：先 review 夹具再重生成")
        assertEquals(expected.drop(1), actual, "协议帧形漂移——如系刻意变更，重生成夹具并 review；否则修回")
    }


    private companion object {
        val VOLATILE_NUM_KEYS = setOf("durationMs", "elapsedMs", "uptimeMs", "costUsd")
        val VOLATILE_STR_KEYS = setOf("时间", "timestamp", "generatedAt")
    }
}
