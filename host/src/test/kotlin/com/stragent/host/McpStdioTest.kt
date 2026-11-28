package com.stragent.host

import com.stragent.core.ports.PrefsStore
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpBridge
import com.stragent.data.McpStdioServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * stdio 传输的端到端测试（dev-agent 同款接线：宿主一条命令拉起，进程随宿主生死）。
 *
 * 关键断言：**stdio 与 HTTP 暴露的是同一份能力面**（都来自 `McpBridge.dispatch`）。
 */
class McpStdioTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 用真实宿主装配（含插件能力），确保 stdio 暴露的是**完整**能力面 */
    private fun bootstrapBridge(): Triple<McpBridge, AbilityDatasets, AutoCloseable> {
        val home = createTempDirectory("str-agent-stdio-test").toFile()
        val bootstrap = StrAgentHostBootstrap.create(home)
        return Triple(bootstrap.mcpBridge, bootstrap.abilityDatasets, bootstrap)
    }

    private fun run(bridge: McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    @Test
    fun `initialize 与 tools_list 走 stdio 正常工作`() {
        val (bridge, _, closer) = bootstrapBridge()
        try {
            val out = run(
                bridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                ),
            )
            assertEquals(2, out.size, "两帧请求应有两帧响应，实际：$out")

            val init = Json.parseToJsonElement(out[0]).jsonObject
            assertEquals(
                "Str Agent",
                init.getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content,
            )

            val tools = Json.parseToJsonElement(out[1]).jsonObject
                .getValue("result").jsonObject.getValue("tools").jsonArray
            val names = tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue(names.contains("stragent_import_table"), "应含数据契约 import_table，实际：$names")
            assertTrue(names.contains("stragent_generate_report"), "应含插件能力 generate_report，实际：$names")
            assertTrue(names.contains("stragent_ask_followup"), "应含插件能力 ask_followup，实际：$names")
        } finally {
            closer.close()
        }
    }

    @Test
    fun `通知（无 id）不回响应`() {
        val (bridge, _, closer) = bootstrapBridge()
        try {
            val out = run(bridge, listOf("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
            assertEquals(0, out.size, "通知不应产生响应帧")
        } finally {
            closer.close()
        }
    }

    @Test
    fun `坏帧回 -32700 且服务继续`() {
        val (bridge, _, closer) = bootstrapBridge()
        try {
            val out = run(
                bridge,
                listOf(
                    "not-json",
                    """{"jsonrpc":"2.0","id":9,"method":"initialize","params":{}}""",
                ),
            )
            assertEquals(2, out.size, "坏帧 + 好帧应有两帧响应，实际：$out")
            val bad = Json.parseToJsonElement(out[0]).jsonObject
            assertEquals(
                -32700,
                bad.getValue("error").jsonObject.getValue("code").jsonPrimitive.content.toInt(),
            )
            // 坏帧之后的正常帧仍要有响应
            assertEquals(
                "Str Agent",
                Json.parseToJsonElement(out[1]).jsonObject
                    .getValue("result").jsonObject.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content,
            )
        } finally {
            closer.close()
        }
    }

    @Test
    fun `tools_call 走 stdio 可用`() {
        val (bridge, _, closer) = bootstrapBridge()
        try {
            val out = run(
                bridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":"a,b\n1,2\n3,4\n","name":"t"}}}""",
                ),
            )
            assertEquals(1, out.size, "应有一帧响应，实际：$out")
            val text = Json.parseToJsonElement(out[0]).jsonObject
                .getValue("result").jsonObject.getValue("content")
            assertTrue(text.toString().contains("datasetId"), "导入结果应含 datasetId，实际：$text")
        } finally {
            closer.close()
        }
    }
}
