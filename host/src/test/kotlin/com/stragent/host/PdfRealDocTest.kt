package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实 PDF 材料回归（D4 P-139）：268 页 Word 导出 PDF（CID 字体 + FlateDecode）。
 *
 * 钉住三件事：
 *  ① PDFBox 抽取全文可读（关键实体可检索、零乱码、页脚过滤）；
 *  ② summarize_text 在真实材料上端到端（map-reduce 路由、块数过限、要点产出）；
 *  ③ 追问门禁恒输出 gateFailed 字段（替身无编造 → 过闸）。
 *
 * 阶段感知替身：map 阶段回合法要点 JSON，其余回普通文本——管线机制验证
 * （路由/分块/门禁/回执），内容质量由真实 Key 抽样验收（T-061 先例）。
 */
class PdfRealDocTest {


    private class StageLlm : com.stragent.core.ports.LlmClient {
        val calls = LinkedHashMap<String, Int>()
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): com.stragent.core.ports.ChatResult {
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val stage = if (system.contains("文本分析员")) "map" else "other"
            calls.merge(stage, 1, Int::plus)
            val reply = if (stage == "map") {
                """{"points":[{"text":"本块要点概述","quote":"","segment":1}],"keyNumbers":[],"summary":"本块概述。"}"""
            } else {
                "综合材料内容可得：相关对象整体处于受控状态，未见新的异常发展。（替身应答）"
            }
            return com.stragent.core.ports.ChatResult(reply, 50)
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    @Test
    fun `PDF 抽取与总结追问全链——真实 268 页材料`() {
        val pdfPath = RealMaterialFixture.requirePdf()
        val llm = StageLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-pdfreal-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        try {
            val sc = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", pdfPath); put("name", "基坑监测总结报告")
            })["structuredContent"]!!.jsonObject
            if (!sc.containsKey("datasetId")) error("导入失败: $sc")
            val dsId = sc.getValue("datasetId").jsonPrimitive.content
            val text = b.abilityDatasets.mustDataset(dsId).text!!
            val all = text.segments.joinToString("") { it.text }

            // ① 全文可读：关键实体可检索、零乱码、页脚已过滤
            assertTrue(all.contains("CYSW-14"), "关键孔号可检索")
            assertTrue(all.contains("10,935"), "关键量值可检索")
            assertTrue(all.contains("数据可靠性分级"), "关键章节可检索")
            assertEquals(0, all.count { it == '\uFFFD' }, "零替换符乱码")
            assertTrue(text.segments.none { Regex("^第 \\d+ 页 / 共 \\d+ 页$").matches(it.text) }, "页脚已过滤")
            assertTrue(text.segments.size in 300..800, "段数在段落粒度区间（实测 526）：${text.segments.size}")

            // ② summarize_text 端到端：map-reduce 路由、块数过限（<96）、要点产出
            val sum = call(b, "stragent_summarize_text", buildJsonObject {
                put("datasetId", dsId)
            })["structuredContent"]!!.jsonObject
            if (sum.containsKey("error")) error("summarize 失败: ${sum.getValue("error")}")
            assertEquals("map-reduce", sum.getValue("route").jsonPrimitive.content)
            assertTrue(sum.getValue("chunkCount").jsonPrimitive.content.toInt() < 96, "块数过限")
            assertTrue(sum.getValue("pointsTotal").jsonPrimitive.content.toInt() > 0, "要点非空")
            assertEquals("[]", sum.getValue("failedChunks").toString(), "无失败块")
            assertTrue(sum.getValue("summary").jsonPrimitive.content.isNotBlank(), "summary 非空")

            // ③ 十问追问：门禁字段恒输出，替身应答全过闸
            val questions = listOf(
                "坑外地下水位下降超过 1 m 的判定结果是什么？最大下降量多少？",
                "立柱结构竖向位移的整组归零换段事件是什么情况？",
                "本报告的数据可靠性分级结果如何？",
                "支撑轴力为什么整项不可判？",
                "墙身测斜的最大峰值是多少，出现在哪一孔？",
                "坑外地表沉降的最大值与越线点数？",
                "底板沉降的判定结果如何？",
                "监测频率达标率按什么口径计算？",
                "报警值未经设计方确认带来什么影响？",
                "资料完整性核查 18 项的判定分布如何？",
            )
            for ((n, q) in questions.withIndex()) {
                val r = call(b, "stragent_ask_followup", buildJsonObject {
                    put("datasetId", dsId); put("question", q)
                })["structuredContent"]!!.jsonObject
                assertTrue(r.containsKey("gateFailed"), "Q${n + 1} 门禁字段恒输出")
                assertEquals(
                    "false",
                    r.getValue("gateFailed").jsonPrimitive.content,
                    "Q${n + 1} 替身无编造应过闸：${r.getValue("answer").jsonPrimitive.content.take(60)}",
                )
            }
        } finally {
            b.close()
        }
    }
}
