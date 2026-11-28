package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpBridge
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-123 /ui/call 写侧闭环集成测试：
 * ① 无 token → 401；② 导入→追问全链经 /ui/call 走通（固定 ui 会话）；
 * ③ 非法体 → 400。鉴权与数据端点同套（Bearer / ?token=）。
 */
class UiCallIntegrationTest {

    private class TestPrefsStore : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class FixedLlm : LlmClient {
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int) =
            ChatResult("东店销售额 1200（表格统计摘要）。", 55)
    }

    private fun post(port: Int, path: String, body: String?): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = if (body == null) "GET" else "POST"
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to text
    }

    @Test
    fun `写侧闭环——无 token 401 导入与追问经 ui 会话走通`() {
        val prefs = TestPrefsStore()
        val llm = FixedLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-uicall-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        try {
            val port = b.mcpBridge.start(0)
            val token = McpBridge.currentToken(b.prefs) // 与 bootstrap 内部同一份 prefs（token 落在其中）
            // ① 无 token → 401
            val (code1, _) = post(port, "/ui/call", """{"name":"stragent_import_table","arguments":{"csv":"a,b\n1,2"}}""")
            assertEquals(401, code1, "未鉴权拒绝")
            // ② 导入 → datasetId
            val (code2, body2) = post(
                port, "/ui/call?token=$token",
                """{"name":"stragent_import_table","arguments":{"csv":"门店,销售额\n东店,1200","name":"操作页"}}""",
            )
            assertEquals(200, code2)
            val sc2 = Json.parseToJsonElement(body2).jsonObject.getValue("structuredContent").jsonObject
            val dsId = sc2.getValue("datasetId").jsonPrimitive.content
            assertTrue(dsId.startsWith("ds_"), "导入回执 datasetId")
            // ③ 追问 → 回答 + 门禁字段
            val (code3, body3) = post(
                port, "/ui/call?token=$token",
                """{"name":"stragent_ask_followup","arguments":{"datasetId":"$dsId","question":"东店销售额多少"}}""",
            )
            assertEquals(200, code3)
            val sc3 = Json.parseToJsonElement(body3).jsonObject.getValue("structuredContent").jsonObject
            assertTrue(sc3.getValue("answer").jsonPrimitive.content.contains("1200"), "追问回答贯通")
            assertEquals("false", sc3.getValue("gateFailed").jsonPrimitive.content, "门禁字段恒输出")
            // ④ 非法体 → 400
            val (code4, _) = post(port, "/ui/call?token=$token", "not json")
            assertEquals(400, code4, "非法体拒绝")
        } finally {
            b.mcpBridge.stop()
            b.close()
        }
    }
}
