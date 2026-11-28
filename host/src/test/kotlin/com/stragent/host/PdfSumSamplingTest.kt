package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P-140 真实 PDF 总结场景替身采样（T-099/T-103 模式）：268 页基坑监测总结报告。
 *
 * 冻结应答库 text-sampling/pdfsum-responses.json：mapByHash 65 块要点（哈希对齐）+
 * reduce 3（两组节摘要与终稿）+ cod 2（密度迭代）+ report 14（概述与分章）+
 * review 1 + followup 5。钉住：真实内容过确定性门禁（数字白名单、锚点回链、
 * 五问 gateFailed==false）；stage 调用序钉住管线结构。
 */
class PdfSumSamplingTest {

    private val responsesResource = "text-sampling/pdfsum-responses.json"

    private class FrozenLlm(
        mapByHash: Map<String, String>,
        responses: Map<String, List<String>>,
    ) : LlmClient {
        private val mapByHash = HashMap(mapByHash)
        private val responses = HashMap(responses)
        private val cursor = HashMap<String, Int>()
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.lastOrNull()?.second ?: ""
            val stage = when {
                system.contains("文本分析员") -> "map"
                system.contains("总结撰写人") -> "reduce"
                system.contains("密度优化") -> "cod"
                system.contains("文档分析员") -> "followup"
                system.contains("修订员") -> "revise"
                system.contains("审稿人") || system.contains("审") -> "review"
                else -> "report"
            }
            counts.merge(stage, 1, Int::plus)
            val reply = when {
                stage == "map" ->
                    mapByHash[user.hashCode().toString()]
                        ?: error("map 无哈希应答: ${user.take(60)}")
                else -> {
                    val replies = responses.getValue(stage)
                    val n = cursor.merge(stage, 1, Int::plus)!! - 1
                    if (n < replies.size) replies[n] else replies.last()
                }
            }
            return ChatResult(reply, 60)
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    @Test
    fun `PDF 总结场景替身采样回放——真实 268 页材料`() {
        val pdfPath = RealMaterialFixture.requirePdf()
        val root = javaClass.classLoader.getResourceAsStream(responsesResource)!!
            .readBytes().decodeToString().let { Json.parseToJsonElement(it).jsonObject }
        val responses = root.entries
            .filter { it.key != "mapByHash" }
            .associate { (k, v) -> k to v.jsonArray.map { it.jsonPrimitive.content } }
        val mapByHash = root.getValue("mapByHash").jsonObject.entries
            .associate { (k, v) -> k to v.jsonPrimitive.content }
        val llm = FrozenLlm(mapByHash, responses)
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-pdfsum-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        try {
            val sc = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", pdfPath); put("name", "基坑监测总结报告")
            })["structuredContent"]!!.jsonObject
            if (!sc.containsKey("datasetId")) error("导入失败: $sc")
            val dsId = sc.getValue("datasetId").jsonPrimitive.content

            // summarize
            val sum = call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", dsId) })["structuredContent"]!!.jsonObject
            // 综述报告
            val rep = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("question", "请给出本区段基坑安全监测的综述评价")
            })["structuredContent"]!!.jsonObject
            // 五问
            val qs = listOf(
                "坑外地下水位下降超 1 m 的判定结果与最大下降量？",
                "立柱竖向位移的整组归零事件是怎么回事？",
                "数据可靠性分级结果如何？哪些项目只能作量级参考？",
                "墙身测斜的最大峰值与最大速率分别是什么？",
                "为什么说报警值未经设计确认时达线不等于超限认定？",
            )
            val followups = qs.map { q ->
                call(b, "stragent_ask_followup", buildJsonObject { put("datasetId", dsId); put("question", q) })["structuredContent"]!!.jsonObject
            }

            // 门禁自证：summary / 综述报告 / 五问
            assertTrue(sum.getValue("summary").jsonPrimitive.content.isNotBlank(), "summary 非空")
            assertTrue(
                sum.getValue("summary").jsonPrimitive.content.contains("15.8"),
                "summary 含密度迭代终稿内容",
            )
            assertEquals("false", rep.getValue("gateFailed").jsonPrimitive.content, "综述门禁过闸")
            assertTrue(rep.getValue("gateIssues").toString() == "[]", "综述无 gateIssues")
            assertTrue(
                rep.getValue("report").jsonPrimitive.content.contains("停测空窗 15.8 个月"),
                "综述含事实锚内容",
            )
            for ((n, f) in followups.withIndex()) {
                assertTrue(f.containsKey("gateFailed"), "Q$n 门禁字段恒输出")
                assertEquals(
                    "false",
                    f.getValue("gateFailed").jsonPrimitive.content,
                    "Q$n 应过闸：${f.getValue("answer").jsonPrimitive.content.take(80)}",
                )
            }
            // stage 调用序钉住管线结构
            assertEquals(130, llm.counts["map"], "map 调用数")
            assertEquals(3, llm.counts["reduce"], "reduce 调用数")
            assertEquals(2, llm.counts["cod"], "cod 调用数")
            assertEquals(14, llm.counts["report"], "report 调用数")
            assertEquals(1, llm.counts["review"], "review 调用数")
            assertEquals(5, llm.counts["followup"], "followup 调用数")
        } finally {
            b.close()
        }
    }
}
