package com.stragent.host

import com.stragent.data.McpStdioServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
 * 大文件聚合工具（tier-2）的端到端验证 —— **规模这条腿接进对外工具面**的证据。
 *
 * 三条断言：
 *  ① 5 万行 CSV 经 MCP 调用，数字与解析解一致（行数、合计、top 组）；
 *  ② 耗时在门限内（tier-2 的意义就在这）；
 *  ③ 安全边界：非白名单扩展名 / 不存在的路径，都给中文错误而不是静默失败。
 */
class LargeCsvAggregateToolTest {

    private val rows = 50_000

    private fun bigCsv(): File {
        val file = File(createTempDirectory("str-agent-large").toFile(), "big.csv")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("地区,销售额\n")
            for (i in 0 until rows) {
                writer.write("G${i % 5},$i\n")
            }
        }
        return file
    }

    private fun callTool(bootstrap: StrAgentHostBootstrap, name: String, args: String): JsonObject {
        val input = ByteArrayInputStream(
            ("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""" + "\n")
                .toByteArray(Charsets.UTF_8),
        )
        val output = ByteArrayOutputStream()
        McpStdioServer(bootstrap.mcpBridge, input, output).serve()
        val frame = Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject
        assertTrue(frame.containsKey("result"), "工具调用应成功：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    @Test
    fun `① 5 万行 CSV 的聚合结果与解析解一致且在耗时门限内`() {
        val file = bigCsv()
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-large-home").toFile())
        try {
            val args = """{"csvPath":${jsonString(file.absolutePath)},"dimension":"地区","metric":"销售额","mode":"sum","topN":8}"""
            val result = callTool(bootstrap, "stragent_aggregate_csv", args)

            assertEquals(rows, result["rowCount"]!!.jsonPrimitive.content.toInt(), "行数应完整")
            assertEquals("duckdb", result["engine"]!!.jsonPrimitive.content)
            assertEquals("columnar", result["tier"]!!.jsonPrimitive.content)

            // 合计 = 0 + 1 + ... + (rows-1)
            val expectedTotal = (0 until rows).sumOf { it.toDouble() }
            assertTrue(
                absDiff(result["grandTotal"]!!.jsonPrimitive.content.toDouble(), expectedTotal) <= 1e-6,
                "合计应为 ${expectedTotal}，实际 ${result["grandTotal"]}",
            )

            // 分组数 5 < topN 8 → 没有「其他 N 项」
            val items = result["items"]!!.jsonArray
            assertEquals(5, items.size, "应有 5 个分组，实际 ${items.size}")
            assertEquals(false, result["truncatedToTopN"]!!.jsonPrimitive.content.toBoolean())

            // top 组 = 各组之和最大者
            val top = items.maxByOrNull { it.jsonObject["value"]!!.jsonPrimitive.content.toDouble() }!!
            assertEquals("G4", top.jsonObject["label"]!!.jsonPrimitive.content)
            assertTrue(absDiff(top.jsonObject["value"]!!.jsonPrimitive.content.toDouble(), 250_015_000.0) <= 1e-3)

            // 耗时门限（tier-2 的意义）
            val elapsed = result["elapsedMs"]!!.jsonPrimitive.content.toLong()
            assertTrue(elapsed < 2000, "5 万行聚合应在 2s 内，实际 ${elapsed}ms")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `② 非白名单扩展名与不存在的路径都给中文错误`() {
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-large-home2").toFile())
        try {
            val txt = File(createTempDirectory("str-agent-large2").toFile(), "secret.txt")
            txt.writeText("不是表", Charsets.UTF_8)
            val bad = errorOf(bootstrap, """{"csvPath":${jsonString(txt.absolutePath)},"dimension":"a","metric":"b"}""")
            assertTrue(bad.contains("只支持"), "扩展名白名单应给中文提示，实际：$bad")

            val missing = errorOf(bootstrap, """{"csvPath":"C:/不存在的文件.csv","dimension":"a","metric":"b"}""")
            assertTrue(missing.contains("文件不存在"), "不存在的路径应给中文提示，实际：$missing")
        } finally {
            bootstrap.close()
        }
    }

    /** 取工具调用的错误文案 */
    private fun errorOf(bootstrap: StrAgentHostBootstrap, args: String): String {
        val input = ByteArrayInputStream(
            ("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_aggregate_csv","arguments":$args}}""" + "\n")
                .toByteArray(Charsets.UTF_8),
        )
        val output = ByteArrayOutputStream()
        McpStdioServer(bootstrap.mcpBridge, input, output).serve()
        val frame = Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject
        // T-079：执行错误在 isError 结果里（响应形状 success），协议错误仍在 frame["error"]
        frame["error"]?.let { return it.jsonObject["message"]!!.jsonPrimitive.content }
        val result = frame["result"]?.jsonObject
        assertTrue(
            result?.get("isError")?.jsonPrimitive?.content == "true",
            "该帧既非协议错误也非 isError 结果：$frame",
        )
        return result!!["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content
    }

    private fun absDiff(a: Double, b: Double): Double = kotlin.math.abs(a - b)

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(ch)
            }
        }
        append('"')
    }
}
