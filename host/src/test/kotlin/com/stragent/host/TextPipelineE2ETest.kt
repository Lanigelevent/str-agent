package com.stragent.host

import com.stragent.data.McpStdioServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-096 端到端：stdio 全链——导入文本 → 总结 → 追问 → 综述报告，全部经真实 MCP 桥
 * dispatch 路径（与外部客户端同接线），确定性假模型驱动，可离线可重复。
 *
 * 断言口径：每步回执的恒输出字段在（summary/points/anchors/gateFailed 等）；
 * 数字白名单纪律端到端成立（追问/报告的数字都必须来自材料）。
 */
class TextPipelineE2ETest {

    private fun tempHome(): java.io.File {
        val dir = Files.createTempDirectory("stragent-e2e").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun jsonString(s: String): String = JsonPrimitive(s).toString()

    @Test
    fun `stdio 全链——导入文本到总结追问报告四步走通`() {
        val fake = FakeLlmClient(
            report = "## 概述\n材料显示销售额 45.3（段 1），整体向好。",
            followup = "销售额 45.3 万元（段 1）。",
        )
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = fake)
        try {
            val txt = Files.createTempFile("material", ".txt").toFile()
            txt.writeText(
                "第一季度销售额 45.3 万元，环比上升。\n\n第二季度毛利率保持稳定，渠道扩张按计划推进。",
                Charsets.UTF_8,
            )
            txt.deleteOnExit()

            // ---- 阶段 1：导入（拿 datasetId） ----
            val out1 = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_import_text","arguments":{"filePath":${jsonString(txt.absolutePath)},"name":"验收材料"}}}""",
                ),
            )
            assertEquals(1, out1.size)
            val imported = Json.parseToJsonElement(out1[0]).jsonObject.getValue("result").jsonObject
            val structured = imported.getValue("structuredContent").jsonObject
            assertEquals("text", structured.getValue("kind").jsonPrimitive.content)
            val datasetId = structured.getValue("datasetId").jsonPrimitive.content
            assertTrue(datasetId.startsWith("ds_"), "datasetId=ds_*：$datasetId")
            assertEquals(2, structured.getValue("text").jsonObject.getValue("segments").jsonPrimitive.content.toInt())

            // ---- 阶段 2：总结/追问/报告（显式携带 datasetId） ----
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_summarize_text","arguments":{"datasetId":"$datasetId"}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_ask_followup","arguments":{"datasetId":"$datasetId","question":"销售额多少"}}}""",
                    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"stragent_generate_report","arguments":{"datasetId":"$datasetId"}}}""",
                ),
            )
            assertEquals(3, out.size, "三步应各有响应帧：$out")

            // ---- ② 总结：summary/points/anchors 恒输出 ----
            val summarized = Json.parseToJsonElement(out[0]).jsonObject.getValue("result").jsonObject
            val sContent = summarized.getValue("structuredContent").jsonObject
            assertEquals("stuff", sContent.getValue("route").jsonPrimitive.content, "短材料 stuff 直通")
            assertTrue(sContent.getValue("summary").jsonPrimitive.content.isNotBlank())
            assertTrue(sContent.getValue("points").toString().contains("45.3"), "要点含材料关键数字")
            assertTrue(sContent.getValue("numbersWithAnchors").toString().contains("45.3"))
            assertEquals("[]", sContent.getValue("failedChunks").toString())

            // ---- ③ 追问：命中材料并回答，数字回链过闸 ----
            val answered = Json.parseToJsonElement(out[1]).jsonObject.getValue("result").jsonObject
            val aContent = answered.getValue("structuredContent").jsonObject
            val followupText = aContent.getValue("answer").jsonPrimitive.content
            assertTrue(followupText.contains("45.3"), "追问正文带材料数字：$aContent")
            assertEquals("false", aContent.getValue("gateFailed").jsonPrimitive.toString(), "回答数字都在命中材料内 → 过闸")

            // ---- ④ 综述报告：正文回传 + 门禁恒输出字段 ----
            val reported = Json.parseToJsonElement(out[2]).jsonObject.getValue("result").jsonObject
            val rContent = reported.getValue("structuredContent").jsonObject
            assertTrue(rContent.getValue("report").jsonPrimitive.content.contains("销售额 45.3"))
            assertEquals("false", rContent.getValue("gateFailed").jsonPrimitive.toString(), "假模型稿干净 → 过闸")
            assertEquals(450, rContent.getValue("targetChars").jsonPrimitive.content.toInt(), "<2K 材料 → 短报告")
        } finally {
            bootstrap.close()
        }
    }
}
