package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
 * 文本管线真实形状抽样（T-099，T-060 先例的感知 prompt 版）：
 *
 * - **捕获轮**：真实管线（import_text → summarize → followup → generate_report）发出的每条
 *   prompt 落盘 `host/build/text-sampling/prompts.json`；无应答文件时测试以指引失败。
 * - **模型轮（助手作为 API Key 替身）**：助手阅读 prompts，逐条撰写应答，存
 *   `host/src/test/resources/text-sampling/responses.json`（按调用序对齐）。
 * - **回放轮**：应答按序回灌真实管线，产物由**确定性门禁**客观打分（quote 逐字支撑、
 *   锚点有效、数字白名单、篇幅），报告落 `host/build/text-sampling/sampling-report.md`。
 *
 * 诚实边界：应答由助手撰写（非真实端点），模型侧质量判断部分自指；但**门禁是客观的**——
 * quote 是否逐字在材料里、锚是否越界、数字是否在白名单，不受自评影响。真实 Key 抽样仍待。
 *
 * 回放轮进入常规测试集：resources 自带冻结应答 → CI 持续验证「抽样可重复 + 门禁可过」。
 */
class TextPipelineSamplingTest {

    private val samplingDir = File("build/text-sampling")
    private val responsesResource = "text-sampling/responses.json"

    private fun tempHome() =
        File(System.getProperty("java.io.tmpdir"), "str-agent-smp-" + System.nanoTime()).also { it.mkdirs() }

    /** 冻结语料（助手设计的验收材料）：中文经营月报，8 段，含可核对数字 */
    private val material: String = listOf(
        "华东区 2026 年第三季度经营月报（内部稿）。",
        "7 月销售收入 128 万元，环比增长 12%，创年内单月新高。",
        "8 月受台风影响物流受阻，收入回落至 96 万元，环比下降 25%。",
        "9 月旺季回补，收入反弹至 135 万元，三季度合计 359 万元。",
        "毛利率方面，三季度稳定在 30% 附近，其中 7 月最高达到 33%。",
        "费用端，仓储成本同比上升 18%，是利润的主要拖累项。",
        "客户结构上，前五大客户收入占比 42%，集中度风险需要关注。",
        "下季度计划：扩充两条产线，预计新增产能 25%，目标是收入突破 150 万元。",
    ).joinToString("\n\n")

    /** 按序应答的替身：捕获模式记录并失败；回放模式按序弹出资源文件里的应答 */
    private class SamplingLlm(
        private val capture: MutableList<String>,
        private val responses: Map<String, String>?,
        private val responsesPath: String,
    ) : LlmClient {
        var index = 0
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
                system.contains("语义") || system.contains("审") -> "review"
                else -> "other"
            }
            capture += "==== [$index] stage=$stage sys=${system.take(48)} ====\n$user"
            // 捕获模式：占位应答让管线跑完整条链（抛异常会被 MCP isError 包装吞掉，序列会残缺）
            index++
            // 回放：按 stage 查表（重试幂等）；捕获：占位让管线跑完整链（异常会被 MCP isError 吞掉）
            val reply = responses?.get(stage)
                ?: "（捕获占位：本条应答待助手撰写）"
            return ChatResult(reply, 40)
        }
    }

    private fun responsesOrNull(): Map<String, String>? {
        val stream = javaClass.classLoader.getResourceAsStream(responsesResource) ?: return null
        val obj = Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
        return obj.entries.associate { it.key to it.value.jsonPrimitive.content }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        )

    private fun importMaterial(b: StrAgentHostBootstrap, name: String): String {
        val f = File(b.home, name)
        f.writeText(material, Charsets.UTF_8)
        val r = call(b, "stragent_import_text", buildJsonObject { put("filePath", f.absolutePath) })
        return r["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content
    }

    @Test
    fun `抽样全链——捕获或回放，回放轮以确定性门禁客观打分`() {
        samplingDir.mkdirs()
        val responses = responsesOrNull()
        val capture = mutableListOf<String>()
        val llm = SamplingLlm(capture, responses, responsesResource)
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm)
        try {
            // ---- 纯文本态：summarize + followup ----
            val dsText = importMaterial(b, "q3.txt")
            val sum = call(
                b, "stragent_summarize_text",
                buildJsonObject {
                    put("datasetId", dsText)
                    put("maxPoints", 9999)
                    put("maxWords", 300)
                },
            )["structuredContent"]!!.jsonObject

            val fu = call(
                b, "stragent_ask_followup",
                buildJsonObject {
                    put("datasetId", dsText)
                    put("question", "三季度收入走势如何？费用端有什么风险？")
                },
            )["structuredContent"]!!.jsonObject

            // ---- 报告（同数据集）----
            val rep = call(
                b, "stragent_generate_report",
                buildJsonObject { put("datasetId", dsText) },
            )["structuredContent"]!!.jsonObject

            // prompts 落盘（无论捕获/回放，产物始终可审计）
            File(samplingDir, "prompts.json").writeText(
                Json.encodeToString(
                    kotlinx.serialization.json.JsonArray.serializer(),
                    kotlinx.serialization.json.buildJsonArray {
                        capture.forEach { add(JsonPrimitive(it)) }
                    },
                ),
                Charsets.UTF_8,
            )

            if (responses == null) {
                // 捕获轮到此为止：产物已落盘，测试失败以指引用助手撰写应答
                kotlin.test.fail(
                    "捕获完成：共 ${capture.size} 条 prompt 已落盘 build/text-sampling/prompts.json。" +
                        "请由助手撰写 $responsesResource（[{\"reply\":\"...\"}] 按调用序）后重跑本测试进入回放轮。",
                )
            }

            // ================= 回放轮：确定性门禁客观打分 =================
            val lines = mutableListOf("# 文本管线抽样报告（T-099，助手替身版）", "")
            var hardFails = 0

            // ---- summarize：quote 逐字支撑 + 锚点有效 + 数字在材料 ----
            val summary = sum.getValue("summary").jsonPrimitive.content
            val points = sum.getValue("points").jsonArray
            lines += "## summarize（route=${sum.getValue("route").jsonPrimitive.content}）"
            lines += "- summary ${summary.length} 字；points ${points.size} 条"
            var quoteMiss = 0
            var anchorMiss = 0
            for (p in points) {
                val o = p.jsonObject
                val q = o.getValue("quote").jsonPrimitive.content
                val seg = o.getValue("segmentIndex").jsonPrimitive.content.toInt()
                if (q.isNotBlank() && !material.contains(q)) { quoteMiss++; lines += "  - ✗ quote 不在材料：$q" }
                if (seg < 1 || seg > 8) anchorMiss++
            }
            if (quoteMiss > 0) hardFails++
            if (anchorMiss > 0) hardFails++
            lines += "- quote 逐字支撑：${points.size - quoteMiss}/${points.size}；锚点越界：$anchorMiss"
            val nums = sum.getValue("numbersWithAnchors").jsonArray.map { it.jsonObject.getValue("number").jsonPrimitive.content }
            val numMiss = nums.count { !material.contains(it) }
            if (numMiss > 0) hardFails++
            lines += "- 数字白名单：${nums.size} 个，不在材料中 $numMiss 个（${nums.joinToString("、")}）"
            lines += ""

            // ---- followup：门禁过闸 + 带锚 ----
            val answer = fu.getValue("answer").jsonPrimitive.content
            lines += "## followup"
            lines += "- answer：${answer.take(120)}…"
            assertTrue(!fu.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict(), "追问门禁应过闸")
            lines += "- gateFailed=false（回答数字全部在命中材料内）"
            lines += ""

            // ---- report：门禁恒输出 + 锚点有效 + 篇幅 ----
            val report = rep.getValue("report").jsonPrimitive.content
            lines += "## report"
            lines += "- 正文 ${report.length} 字；targetChars=${rep.getValue("targetChars").jsonPrimitive.content}"
            assertTrue(!rep.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict(), "报告门禁应过闸")
            assertTrue(report.contains("段 "), "报告应带段锚")
            // 报告中的「段 N」必须落在材料范围
            Regex("""段\s*(\d+)""").findAll(report).forEach {
                val n = it.groupValues[1].toInt()
                assertTrue(n in 1..8, "报告锚点越界：段 $n")
            }
            lines += "- gateFailed=false；锚点全部在 1..8"
            lines += ""

            lines += "## 结论"
            lines += "- 确定性门禁硬失败：$hardFails（0 = 本抽样全部过闸）"
            lines += "- 模型调用 ${capture.size} 次（segment/reduce/cod/followup/report/review 分阶段见 prompts.json）"
            File(samplingDir, "sampling-report.md").writeText(lines.joinToString("\n"), Charsets.UTF_8)
            lines.forEach { println(it) }
            assertEquals(0, hardFails, "抽样门禁硬失败 $hardFails 项，详见 build/text-sampling/sampling-report.md")
        } finally {
            b.close()
        }
    }
}
