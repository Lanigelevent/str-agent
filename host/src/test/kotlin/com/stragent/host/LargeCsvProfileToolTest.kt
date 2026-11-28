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
 * 大文件列画像工具（tier-2）的验证 —— 大文件除了能聚合，还要能判断「数据能不能用」。
 *
 * 断言：
 *  ① 5 万行文件的数值列画像：count / missing / sum / mean 与解析解一致；
 *  ② 文本列画像：distinct 与高频值与解析解一致；
 *  ③ 列不存在时给中文错误并列出该文件的列。
 */
class LargeCsvProfileToolTest {

    private val rows = 50_000

    private fun bigCsv(): File {
        val file = File(createTempDirectory("str-agent-profile").toFile(), "big.csv")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("地区,销售额,订单数\n")
            for (i in 0 until rows) {
                writer.write("G${i % 5},$i,${i % 7}\n")
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
    fun `① 数值列画像与解析解一致`() {
        val file = bigCsv()
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-profile-home").toFile())
        try {
            val args = """{"csvPath":${jsonString(file.absolutePath)},"column":"销售额"}"""
            val result = callTool(bootstrap, "stragent_profile_csv", args)
            val stat = result["columns"]!!.jsonArray[0].jsonObject

            assertEquals("销售额", stat["name"]!!.jsonPrimitive.content)
            assertEquals("number", stat["type"]!!.jsonPrimitive.content)
            assertEquals(rows, stat["count"]!!.jsonPrimitive.content.toInt(), "count 是参与统计的样本数")
            assertEquals(0, stat["missing"]!!.jsonPrimitive.content.toInt())

            val expectedSum = (0 until rows).sumOf { it.toDouble() }
            assertTrue(
                kotlin.math.abs(stat["sum"]!!.jsonPrimitive.content.toDouble() - expectedSum) <= 1e-6,
                "sum 应为 ${expectedSum}",
            )
            assertTrue(
                kotlin.math.abs(stat["mean"]!!.jsonPrimitive.content.toDouble() - expectedSum / rows) <= 1e-6,
                "mean 应为 ${expectedSum / rows}",
            )
            // 中位数与分位数（线性插值，PERCENTILE.INC 同口径）
            val median = stat["median"]!!.jsonPrimitive.content.toDouble()
            assertTrue(kotlin.math.abs(median - 24999.5) <= 1e-6, "median 应为 24999.5，实际 $median")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `② 文本列画像与解析解一致`() {
        val file = bigCsv()
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-profile-home2").toFile())
        try {
            val args = """{"csvPath":${jsonString(file.absolutePath)},"column":"地区"}"""
            val result = callTool(bootstrap, "stragent_profile_csv", args)
            val stat = result["columns"]!!.jsonArray[0].jsonObject

            assertEquals("text", stat["type"]!!.jsonPrimitive.content)
            assertEquals(rows, stat["count"]!!.jsonPrimitive.content.toInt())
            assertEquals(5, stat["distinct"]!!.jsonPrimitive.content.toInt(), "地区只有 5 个取值")
            val top = stat["topValues"]!!.jsonArray[0].jsonObject
            assertEquals(rows / 5, top["count"]!!.jsonPrimitive.content.toInt(), "最高频分组各 1 万行")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

    @Test
    fun `③ 列不存在时给中文错误并列出该文件的列`() {
        val file = bigCsv()
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-profile-home3").toFile())
        try {
            val args = """{"csvPath":${jsonString(file.absolutePath)},"column":"不存在的列"}"""
            val input = ByteArrayInputStream(
                ("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_profile_csv","arguments":$args}}""" + "\n")
                    .toByteArray(Charsets.UTF_8),
            )
            val output = ByteArrayOutputStream()
            McpStdioServer(bootstrap.mcpBridge, input, output).serve()
            val frame = Json.parseToJsonElement(output.toString(Charsets.UTF_8).trim()).jsonObject
            // T-079：执行错误在 isError 结果里（响应形状 success），协议错误仍在 frame["error"]
            val message = frame["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: frame["result"]!!.jsonObject["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content
            assertTrue(message.contains("不存在"), "应提示列不存在，实际：$message")
            assertTrue(message.contains("销售额"), "错误应列出该文件的列，实际：$message")
        } finally {
            bootstrap.close()
            file.delete()
        }
    }

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
