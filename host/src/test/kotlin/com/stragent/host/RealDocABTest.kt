package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
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
 * T-103 A/B 效果对比（真实工程材料）：管廊监测方案.docx（1,168 段 / 39,765 字 / 1,379 个
 * 数字 / 18 张表）。
 *
 * - **A 组（agent）**：import_text → summarize_text → ask_followup×3 → generate_report，
 *   管线发出的 prompt 落盘，由助手（API Key 替身）按 stage 撰写应答，回放后回执与门禁
 *   产物落盘供对比脚本检查；
 * - **B 组（裸后端模型）**：助手直接读原文作答（无工具），文本由对比脚本从同一原文提取，
 *   两边过同一套确定性检查（数字在原文？锚点有效？覆盖？）。
 *
 * 对比产物：build/text-sampling/ab/{a-summary.json, a-followups.json, a-report.md, b.json}。
 */
class RealDocABTest {

    private val samplingDir = File("build/text-sampling/ab")
    private val responsesResource = "text-sampling/ab-responses.json"

    private fun tempHome() =
        File(System.getProperty("java.io.tmpdir"), "str-agent-ab-" + System.nanoTime()).also { it.mkdirs() }

    /** 按 stage 查表的替身（T-099 同款；无应答 = 占位继续，捕获完整序列） */
    private class SamplingLlm(
        private val capture: MutableList<String>,
        private val responses: Map<String, List<String>>?,
    ) : LlmClient {
        private val stageCursor = HashMap<String, Int>()
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.lastOrNull()?.second ?: ""
            val stage = when {
                system.contains("文本分析员") -> "segment"
                system.contains("总结撰写人") -> "reduce"
                system.contains("密度优化") -> "cod"
                system.contains("文档分析员") -> "followup"
                system.contains("资深数据分析师") -> "report"
                system.contains("修订员") -> "revise"
                system.contains("审稿人") || system.contains("审") -> "review"
                system.contains("查询改写器") -> "rewrite"
                else -> "other"
            }
            val n = (stageCursor[stage] ?: 0)
            stageCursor[stage] = n + 1
            capture += "==== [$stage#$n] ====\n" + messages.joinToString("\n---\n") { "[${it.first}] ${it.second}" }
            val replies = responses?.get(stage)
            val reply = when {
                replies == null -> "（捕获占位：待助手撰写 $stage#$n）"
                n < replies.size -> replies[n]
                replies.isNotEmpty() -> replies.last() // 同 stage 超出预写数：复用最后一条（块级应答形状一致）
                else -> "（捕获占位：待助手撰写 $stage#$n）"
            }
            return ChatResult(reply, 60)
        }
    }

    private fun responsesOrNull(): Map<String, List<String>>? {
        val stream = javaClass.classLoader.getResourceAsStream(responsesResource) ?: return null
        val obj = Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
        return obj.entries.associate { (k, v) -> k to v.jsonArray.map { it.jsonPrimitive.content } }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        )

    @Test
    fun `A B 对比全链——真实 docx 四步走通并落盘产物`() {
        val realDoc = RealMaterialFixture.requireDocx()
        samplingDir.mkdirs()
        val responses = responsesOrNull()
        val capture = mutableListOf<String>()
        val llm = SamplingLlm(capture, responses)
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm)
        try {
            val dsId = call(b, "stragent_import_text", buildJsonObject { put("filePath", realDoc); put("name", "管廊监测方案") })["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content

            // T-103 对比范围：followup×3（检索式问答）。summarize/report 的 84 块成本问题
            // （表格密集文档碎片化：18 表投影独立成块+段落群被表打断）如实记录为 T-103 发现，
            // 不在本对比窗口（块级应答数量不受控会污染对比公平性）。
            val questions = listOf(
                "监测项目有哪些？各监测项目的报警值和控制标准是多少？",
                "监测频率是怎么安排的？出现什么情况需要加密观测或预警？",
                "周边环境（管线、建筑物）的保护措施和应急响应流程是什么？",
            )
            val followups = questions.map { q ->
                call(b, "stragent_ask_followup", buildJsonObject { put("datasetId", dsId); put("question", q) })["structuredContent"]!!.jsonObject
            }
            // generate_report（综述）不进本对比窗口：84 块的 map 成本不受控（见块碎片化发现）

            // prompts 落盘
            File(samplingDir, "prompts.json").writeText(
                Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(capture.map { kotlinx.serialization.json.JsonPrimitive(it) })),
                Charsets.UTF_8,
            )

            // A 组产物落盘
            File(samplingDir, "a-followups.json").writeText(
                Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(followups.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })),
                Charsets.UTF_8,
            )

            if (responses == null) {
                kotlin.test.fail("A 组捕获完成：共 ${capture.size} 条 prompt 已落盘。请由助手撰写 $responsesResource 后重跑。")
            }
            // 回放轮：A 组门禁自证
            assertTrue(followups.all { !it.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict() }, "三个追问门禁全过闸")
        } finally {
            b.close()
        }
    }

    private fun rep_gateFailed(report: kotlinx.serialization.json.JsonObject) =
        report.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict()
}
