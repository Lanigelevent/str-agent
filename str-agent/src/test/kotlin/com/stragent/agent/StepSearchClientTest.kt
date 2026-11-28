package com.stragent.agent

import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-093 · StepSearchClient 的纯函数面测试（覆盖率补强靶点：此前 0%）。
 *
 * 覆盖：parseRpcBody 的 JSON/SSE 双形态与坏输入；readToolText 的
 * isError/空/缺 content 三态（原文注释承诺「分别报对」）；extractItems 的
 * URL 提取/去重/上限/标点清洗；无 Key 时的中文错误（HTTP 路径依赖外部
 * 端点且 MCP_URL 是私有常量，不在本套件范围——诚实边界）。
 */
class StepSearchClientTest {

    private class MapPrefs(private val map: MutableMap<String, String> = mutableMapOf()) : PrefsStore {
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    // ---------------- parseRpcBody ----------------

    @Test
    fun `parseRpcBody——JSON 直解`() {
        val raw = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""
        val obj = StepSearchClient.parseRpcBody(raw)
        assertEquals("2.0", obj["jsonrpc"]!!.toString().trim('"'))
    }

    @Test
    fun `parseRpcBody——SSE 取最后一条完整 data 行并忽略 DONE`() {
        val raw = "data: {\"n\":1}\n\n" +
            "data: {\"n\":2}\n\n" +
            "data: [DONE]\n\n"
        val obj = StepSearchClient.parseRpcBody(raw)
        assertEquals("2", obj["n"]!!.toString().trim('"'), "应取最后一条完整事件")
    }

    @Test
    fun `parseRpcBody——空输入与非 JSON 都给中文错误`() {
        for (bad in listOf("", "   ")) {
            try {
                StepSearchClient.parseRpcBody(bad)
                fail("空输入应抛：$bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("没有返回内容"), "空输入错误：${e.message}")
            }
        }
        try {
            StepSearchClient.parseRpcBody("not-json at all")
            fail("非 JSON 应抛")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("无法解析"), "格式错误：${e.message}")
        }
    }

    // ---------------- readToolText ----------------

    @Test
    fun `readToolText——isError 与空 content 与正常文本三态（T-073 承诺分别报对）`() {
        // isError
        try {
            StepSearchClient.readToolText(
                Json.parseToJsonElement("""{"result":{"isError":true}}""").jsonObject,
            )
            fail("isError 应抛")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("错误"), "isError 错误：${e.message}")
        }
        // 空 content
        try {
            StepSearchClient.readToolText(
                Json.parseToJsonElement("""{"result":{"content":[]}}""").jsonObject,
            )
            fail("空 content 应抛")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("没有搜索到"), "空结果错误：${e.message}")
        }
        // 正常文本
        val text = StepSearchClient.readToolText(
            Json.parseToJsonElement(
                """{"result":{"content":[{"type":"text","text":"第一行"}]}}""",
            ).jsonObject,
        )
        assertEquals("第一行", text)
    }

    // ---------------- extractItems ----------------

    @Test
    fun `extractItems——提取去重上限与标题清理`() {
        val raw = """
            某事件报道 https://a.example.com/x，后续说明。
            重复链接 https://a.example.com/x。再次出现。
            https://b.example.com/y 摘要内容
            https://c.example.com/z
            https://d.example.com/w
            https://e.example.com/v
            https://f.example.com/u
        """.trimIndent()
        val items = StepSearchClient.extractItems(raw)
        assertTrue(items.size <= 5, "上限 5 条：${items.size}")
        assertEquals("https://a.example.com/x", items[0].url, "去重后首条")
        assertTrue(items.none { it.url.endsWith("。") || it.url.endsWith("，") }, "URL 尾部标点应清洗")
        assertTrue(items[0].title.isNotEmpty() || items[0].url.isNotEmpty())
    }

    // ---------------- 无 Key 错误 ----------------

    @Test
    fun `search 与 searchRaw——未配置 Key 时给中文指引`() {
        val client = StepSearchClient(MapPrefs())
        for (action in listOf({ client.search("q") }, { client.searchRaw("q") })) {
            try {
                action()
                fail("无 Key 应抛")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Step API Key"), "错误信息：${e.message}")
            }
        }
    }
}
