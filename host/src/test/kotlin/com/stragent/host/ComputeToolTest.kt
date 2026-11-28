package com.stragent.host

import com.stragent.data.McpStdioServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 受控计算工具（阶段 2 sandbox 档）的端到端验证。
 *
 * 三条断言：
 *  ① **默认关闭**：未授权时报中文错误并说明怎么开（人类授权闸）；
 *  ② 授权后可执行：返回数值 + 计算清单（code/inputHash/outputHash/env）；
 *  ③ 计算被记进**分析链溯源清单**，且可重放复核（代码与数据都对得上）。
 */
class ComputeToolTest {

    private val csv = "地区,销售额,订单数,成本\n华东,1000,10,600\n华北,300,5,200\n华东,200,8,100\n"

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    // T-079：工具执行错误按 MCP 规范装在 isError 结果里（响应形状是 success）；
    // 协议错误仍在 frame["error"]。两种形态归一成错误文本。
    private fun errorMessage(out: List<String>): String {
        val frame = Json.parseToJsonElement(out.last()).jsonObject
        frame["error"]?.let { return it.jsonObject["message"]!!.jsonPrimitive.content }
        val result = frame["result"]!!.jsonObject
        assertTrue(
            result["isError"]?.jsonPrimitive?.content == "true",
            "该帧既非协议错误也非 isError 结果：$frame",
        )
        return result["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content
    }

    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "第 $index 帧不是成功响应：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    private fun importFrame(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":${jsonString(csv)},"name":"样本"}}}"""

    @Test
    fun `① 未授权时拒绝并给中文说明`() {
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-compute-off").toFile())
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(1),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_compute","arguments":{"code":"sum(销售额)"}}}""",
                ),
            )
            val message = errorMessage(out)
            assertTrue(message.contains("默认关闭"), "应说明默认关闭：$message")
            assertTrue(message.contains("STRAGENT_SANDBOX"), "应说明如何开启：$message")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `② 授权后可执行并产出计算清单`() {
        val home = createTempDirectory("str-agent-compute-on").toFile()
        val bootstrap = StrAgentHostBootstrap.create(home)
        try {
            // 宿主显式授权（模拟 HITL：宿主做出授权决定）
            bootstrap.prefs.set("compute.sandbox.enabled", "true")

            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(1),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_compute","arguments":{"code":"sum(销售额)"}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_compute","arguments":{"code":"avg(销售额 / 订单数)"}}}""",
                ),
            )
            val sum = toolResult(out, 1)
            assertEquals(1500.0, sum["value"]!!.jsonPrimitive.content.toDouble(), 1e-9)

            val manifest = sum["manifest"]!!.jsonObject
            assertEquals("sum(销售额)", manifest["code"]!!.jsonPrimitive.content)
            assertTrue(manifest["inputHash"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(manifest["outputHash"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(manifest["env"]!!.jsonPrimitive.content.startsWith("str-agent-expr/"))

            // 组合表达式：平均客单价 = (100+60+25)/3
            val avgTicket = toolResult(out, 2)
            assertEquals(185.0 / 3, avgTicket["value"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `③ 计算进入溯源清单并可重放复核`() {
        val home = createTempDirectory("str-agent-compute-prov").toFile()
        val bootstrap = StrAgentHostBootstrap.create(home)
        try {
            bootstrap.prefs.set("compute.sandbox.enabled", "true")
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(1),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_compute","arguments":{"code":"sum(销售额)"}}}""",
                ),
            )
            val datasetId = toolResult(out, 0)["datasetId"]!!.jsonPrimitive.content

            val provOut = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_provenance","arguments":{"datasetId":"$datasetId"}}}""",
                ),
            )
            val prov = toolResult(provOut, 0)
            val steps = prov["steps"]!!.jsonArray
            assertTrue(
                steps.any { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_compute" },
                "计算步骤应进入溯源清单",
            )

            // 重放：同一份数据 + 同一段代码 → 同一结果 → 通过
            val replay = prov["replay"]!!.jsonArray
            val computeStep = replay.first { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_compute" }
            assertEquals(
                true,
                computeStep.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean(),
                "计算应可重放：${computeStep.jsonObject["detail"]!!.jsonPrimitive.content}",
            )
        } finally {
            bootstrap.close()
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
        append('"')
    }
}
