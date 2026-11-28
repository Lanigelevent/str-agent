package com.stragent.host

import com.stragent.core.ports.VisionClient
import com.stragent.core.ports.VisionImage
import com.stragent.core.ports.VisionRequest
import com.stragent.core.ports.VisionResult
import com.stragent.core.trace.VisionManifest
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
 * 视觉工具（阶段 3）的端到端验证。
 *
 * **后端大模型由我扮演**：桩返回符合主流响应形状的内容（抽取模式返回严格 JSON 序列）。
 * 断言两条主线：
 *  ① describe 模式：看图回答，产出可溯源清单；
 *  ② extract_series 模式：图表 → 数据序列 → **CSV → 既有分析链**（闭环：
 *     抽出的数能被 import_table 导入并做分组聚合 —— 图里的数与表里的数走同一条流水线）。
 */
class VisionToolTest {

    /** 我扮演的模型：抽取模式给严格 JSON，描述模式给一句话。 */
    private class StubVision : VisionClient {
        override val id: String = "stub-vision"
        override fun isAvailable(): Boolean = true

        override fun describe(request: VisionRequest): VisionResult {
            val text = if (request.prompt.contains("逐点")) {
                """{"title":"月度销售额","xAxis":["1月","2月","3月"],"series":[{"name":"销售额","points":[{"x":"1月","y":100},{"x":"2月","y":150},{"x":"3月","y":null}]}]}"""
            } else {
                "这是一张月度销售额柱形图，2 月最高。"
            }
            val bytes = (request.image as? VisionImage.LocalFile)?.let { File(it.path).readBytes() } ?: ByteArray(0)
            return VisionResult(
                text = text,
                model = "stub-vision",
                totalTokens = 42,
                manifest = VisionManifest(
                    imageHash = VisionManifest.hashOfBytes(bytes),
                    promptHash = VisionManifest.hashOf(request.prompt),
                    outputHash = VisionManifest.hashOf(text),
                    model = "stub-vision",
                    env = "stub/1.0",
                ),
            )
        }
    }

    private fun pngFile(): File {
        val file = File(createTempDirectory("str-agent-vision-tool").toFile(), "chart.png")
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        return file
    }

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "第 $index 帧不是成功响应：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    @Test
    fun `① describe 模式：看图回答并给出可溯源清单`() {
        val bootstrap = StrAgentHostBootstrap.create(
            createTempDirectory("str-agent-vision-1").toFile(),
            vision = StubVision(),
        )
        try {
            val image = pngFile()
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_analyze_image","arguments":{"imagePath":${jsonString(image.absolutePath)},"question":"最高的是哪个月？"}}}""",
                ),
            )
            val result = toolResult(out, 0)
            assertEquals("describe", result["mode"]!!.jsonPrimitive.content)
            assertTrue(result["text"]!!.jsonPrimitive.content.contains("2 月"))
            val manifest = result["manifest"]!!.jsonObject
            assertTrue(manifest["imageHash"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(manifest["outputHash"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("stub-vision", manifest["model"]!!.jsonPrimitive.content)
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `② extract_series 模式：图表 → 序列 → CSV → 既有分析链闭环`() {
        val bootstrap = StrAgentHostBootstrap.create(
            createTempDirectory("str-agent-vision-2").toFile(),
            vision = StubVision(),
        )
        try {
            val image = pngFile()
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_analyze_image","arguments":{"imagePath":${jsonString(image.absolutePath)},"mode":"extract_series"}}}""",
                ),
            )
            val result = toolResult(out, 0)
            assertEquals("extract_series", result["mode"]!!.jsonPrimitive.content)
            val series = result["series"]!!.jsonObject
            assertEquals("月度销售额", series["title"]!!.jsonPrimitive.content)
            val csv = result["csv"]!!.jsonPrimitive.content
            assertTrue(csv.startsWith("x,销售额"), "CSV 应可被导入：$csv")

            // 闭环：把抽出的 CSV 喂回既有分析链
            val closed = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":${jsonString(csv)},"name":"图里抽出的数"}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_session_group_aggregate","arguments":{"dimension":"x","metric":"销售额","mode":"sum"}}}""",
                ),
            )
            val aggregate = toolResult(closed, 1)
            val text = aggregate.toString()
            // 按月分组：1月=100、2月=150；3月读不出（null）→ 不进统计、也**不许编造**
            assertTrue(text.contains("150"), "应有 2月=150：$text")
            assertTrue(text.contains("100"), "应有 1月=100：$text")
            assertTrue(!text.contains("3月"), "读不出的点不应进统计：$text")
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
