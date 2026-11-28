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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P-145 适配配置语义采样（T-099/T-103/P-140 模式）：真实 268 页 PDF 上的
 * deep + civil 行业 + 施工方受众 + 「风险与遗留」口径四参适配全链。
 *
 * 冻结应答库 text-sampling/pdfsum-adapt-responses.json：mapByHash 130
 * （summarize 相位复用默认采样哈希 + report 相位带 hints 的 65 个新哈希）+
 * report 20 章（概述/关键事实/结论复用默认采样全量白名单文本；17 组章为
 * 按段区间手写的无数字主题文——数字约束是上限不是配额）+ reduce/cod/review/followup
 * 复用默认采样（reduce 走 summarize 相位无 hints、followup 走会话偏好）。
 * 钉住：适配配置下 20 章结构（deep 组更细）、targetChars=3500、无修订过闸、
 * outputProfile 回显、五问 gateFailed==false。
 */
class PdfAdaptSamplingTest {

    private val responsesResource = "text-sampling/pdfsum-adapt-responses.json"

    private class FrozenLlm(
        mapByHash: Map<String, String>,
        responses: Map<String, List<String>>,
    ) : LlmClient {
        private val mapByHash = HashMap(mapByHash)
        private val responses = HashMap(responses)
        private val cursor = HashMap<String, Int>()
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val capture = mutableListOf<String>()

        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.lastOrNull()?.second ?: ""
            val stage = when {
                system.contains("文本分析员") -> "map"
                system.contains("总结撰写人") -> "reduce"
                system.contains("密度优化") -> "cod"
                system.contains("文档分析员") -> "followup"
                system.contains("修订员") -> "revise"
                system.contains("审稿人") -> "review"
                else -> "report"
            }
            counts.merge(stage, 1, Int::plus)
            val n = cursor.merge(stage, 1, Int::plus)!! - 1
            capture += "==== [$stage#$n] ====\n" + messages.joinToString("\n---\n") { "[${it.first}] ${it.second}" }
            val reply = when {
                stage == "map" && mapByHash.isNotEmpty() ->
                    mapByHash[user.hashCode().toString()]
                        ?: error("map 无哈希应答: ${user.take(60)}")
                responses.isNotEmpty() && responses.containsKey(stage) -> {
                    val replies = responses.getValue(stage)
                    if (n < replies.size) replies[n] else replies.last()
                }
                else -> "（捕获占位：待助手撰写 $stage#$n）"
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
    fun `适配配置语义采样回放——真实 268 页材料`() {
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
            // 综述报告（适配配置：deep + 工程行业 + 受众 + 要求）
            val rep = call(b, "stragent_generate_report", buildJsonObject {
                put("datasetId", dsId); put("question", "请给出本区段基坑安全监测的综述评价")
                put("depth", "deep"); put("industry", "civil")
                put("audience", "施工方项目经理，非监测专业")
                put("requirements", "只写风险与遗留事项，按工程审阅口径组织")
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

            // 回放自证：门禁 + 适配结构 + 回显
            assertTrue(sum.getValue("summary").jsonPrimitive.content.isNotBlank(), "summary 非空")
            assertEquals("3500", rep.getValue("targetChars").jsonPrimitive.content, "deep 篇幅轴（2000×1.75）")
            assertEquals("false", rep.getValue("gateFailed").jsonPrimitive.content, "适配配置综述过闸")
            assertTrue(rep.getValue("gateIssues").toString() == "[]", "无 gateIssues")
            val echo = rep.getValue("outputProfile").jsonObject
            assertEquals("deep", echo.getValue("overrides").jsonObject.getValue("analysis.depth").jsonPrimitive.content, "overrides 回显 depth")
            assertTrue(
                rep.getValue("report").jsonPrimitive.content.contains("归零换段口径是本章核心"),
                "适配组章正文在场",
            )
            assertTrue(
                rep.getValue("report").jsonPrimitive.content.contains("停测空窗 15.8 个月"),
                "复用概述/结论的锚内容在场",
            )
            for ((n, f) in followups.withIndex()) {
                assertEquals("false", f.getValue("gateFailed").jsonPrimitive.content, "Q$n 应过闸")
            }
            // 适配结构调用序：map 130（两相位）、report 20 章（概述+17组+关键事实+结论）
            assertTrue((llm.counts["map"] ?: 0) in 130..131, "map 调用数（允许多 1 次块内重试，T-129 扇出下偶发）")
            assertEquals(3, llm.counts["reduce"], "reduce 调用数")
            assertEquals(2, llm.counts["cod"], "cod 调用数")
            assertEquals(20, llm.counts["report"], "report 章数（deep 组更细）")
            assertEquals(1, llm.counts["review"], "review 调用数")
            assertEquals(5, llm.counts["followup"], "followup 调用数")
            assertEquals(null, llm.counts["revise"], "无门禁修订（真实应答一次过闸）")
        } finally {
            b.close()
        }
    }
}
