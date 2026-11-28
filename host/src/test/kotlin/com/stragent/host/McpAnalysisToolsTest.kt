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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MCP 工具面扩展验证（T-007）—— 16 个策展分析工具对外可枚举、可调用。
 *
 * 三条断言：
 *  ① 目录里能看到分析工具（未导入数据时也在：元数据是静态的）；
 *  ② 导入数据后，分析工具作用于**当前数据集**，数字与导入内容一致；
 *  ③ 工具面仍是**只读**的：网络型工具（web_search / web_fetch）不进来。
 */
class McpAnalysisToolsTest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n华东,200,8\n"

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun toolNames(out: List<String>): List<String> =
        Json.parseToJsonElement(out.last()).jsonObject
            .getValue("result").jsonObject.getValue("tools").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }

    /** tools/call 的结果包在 content[0].text 里（JSON 字符串），取出内层对象 */
    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "第 $index 帧不是成功响应：$frame")
        val content = frame["result"]!!.jsonObject
        val text = content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `① 目录列出策展分析工具，且保持只读边界`() {
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-mcp-tools").toFile())
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""),
            )
            val names = toolNames(out)

            // 数据契约 + 插件能力 + 策展分析工具
            assertTrue(names.contains("stragent_import_table"), "应含数据契约 import_table")
            assertTrue(names.contains("stragent_generate_report"), "应含插件能力 generate_report")
            assertTrue(names.contains("stragent_session_overview"), "应含策展工具 session_overview")
            assertTrue(names.contains("stragent_session_group_aggregate"), "应含策展工具 session_group_aggregate")
            assertTrue(names.contains("stragent_session_audit_report"), "应含策展工具 session_audit_report")

            // 只读边界：网络型工具不进对外工具面
            assertFalse(names.contains("web_search"), "web_search 不应进只读工具面")
            assertFalse(names.contains("web_fetch"), "web_fetch 不应进只读工具面")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `② 导入后分析工具作用于当前数据集，数字与导入内容一致`() {
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-mcp-tools2").toFile())
        try {
            val csvJson = buildString {
                append('"')
                csv.forEach { ch ->
                    when (ch) {
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        else -> append(ch)
                    }
                }
                append('"')
            }
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":$csvJson,"name":"样本"}}}""",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_session_overview","arguments":{}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_session_group_aggregate","arguments":{"dimension":"地区","metric":"销售额","mode":"sum"}}}""",
                ),
            )
            assertEquals(3, out.size, "三帧请求应有三帧响应：$out")

            // 概况：3 行 3 列（与导入内容一致）
            val overview = toolResult(out, 1)
            assertEquals(3, overview["rows"]!!.jsonPrimitive.content.toInt())
            assertEquals(3, overview["columns"]!!.jsonPrimitive.content.toInt())

            // 分组聚合：华东 1200、华北 300（数字可回溯到原始行）
            val aggregate = toolResult(out, 2).toString()
            assertTrue(aggregate.contains("1200"), "华东销售额应为 1000+200=1200，实际：$aggregate")
            assertTrue(aggregate.contains("300"), "华北销售额应为 300，实际：$aggregate")
        } finally {
            bootstrap.close()
        }
    }
}
