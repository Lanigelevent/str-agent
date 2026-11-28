package com.stragent.host

import com.stragent.agent.AgentEngine
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.data.abilityTools
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-089（T-086 边界段登记项）· 报告 MCP 回执 `charts` 的端到端测试。
 *
 * 路径：选内置 report 引擎插件（`report-deepdive`，kind=report 被注册表支持）
 * → 单 agent 循环跑 suggest_chart → finish → 回执 `charts` 非空透传。
 * 另钉住 classic 路径的恒输出不变量（空数组也是输出，T-032 同纪律）。
 *
 * 勘误记录（T-090 过程中确认）：T-089 首测用 `str-agent` 选引擎失败——那是宿主
 * 插件 manifest 的 id，不是 PluginRegistry 的清单条目；内置 `report-deepdive`
 * （kind=report）才是引擎选择的正确目标，agent 路径生产可达。
 */
class ReportReceiptChartsTest {

    /**
     * 按 prompt 形状分流 + 按轮次回放的假模型（分流纪律同 FakeLlmClient）。
     *
     * 关键（实测教训）：报告门禁的语义审查会**再调 LLM**（prompt 含 `"issues"`）——
     * 不分流的话，审查会拿到 loop 协议的 finish JSON 原文，污染进报告正文。
     */
    private class ChartScriptedLlm : LlmClient {
        private var turn = 0
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            val joined = messages.joinToString("\n") { it.second }
            // 语义审查 prompt（含 "issues"）→ 空问题清单（FakeLlmClient 同款分流）
            if (joined.contains("\"issues\"")) {
                return ChatResult(text = "{\"issues\":[]}", totalTokens = 8, truncated = false)
            }
            // 门禁修订 prompt（<role>你是报告修订编辑）→ 返回修订稿（含 {{chart:0}}；
            // 门禁若再报同样问题，预算内照发 gateFailed=true——报告照发不静默放行）
            if (joined.contains("你是报告修订编辑")) {
                return ChatResult(text = "## 报告\n\n见 {{chart:0}}。", totalTokens = 20, truncated = false)
            }
            turn += 1
            val content = if (turn == 1) {
                """{"tool":"suggest_chart","args":{"type":"bar","title":"各组销售额","data":[{"label":"West","value":725457.94},{"label":"East","value":678781.37}],"description":"分组对比"}}"""
            } else {
                // loop 协议要求 JSON 字符串值里的换行是字面 \n（原始字符串里写 \n；
                // 普通字符串的 \n 是真换行，会打断 JSON 解析）
                """{"tool":"finish","args":{"report":"## 报告\n\n见 {{chart:0}}。"}}"""
            }
            return ChatResult(text = content, totalTokens = 42, truncated = false)
        }

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): ChatResult = chat(model, messages, temperature, maxTokens)
    }

    private fun tempHome(): File = Files.createTempDirectory("str-agent-report-charts").toFile()

    private val csv = "地区,销售额\n华东,1000\n华北,300\n"

    @Test
    fun `报告回执带 charts——选内置 report 引擎后 suggest_chart 产物透传（T-089）`() {
        val home = tempHome()
        try {
            StrAgentHostBootstrap.create(home, llm = ChartScriptedLlm()).use { bootstrap ->
                // 选内置 report 引擎插件（kind=report 被注册表支持 → agent 循环路径）
                AgentEngine.setEngineId(bootstrap.prefs, "report-deepdive")
                val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "图表样本") })["datasetId"]
                        as kotlinx.serialization.json.JsonPrimitive
                    ).content

                val receipt = com.stragent.data.McpTraceContext.with(null) {
                    tools.first { it.name == "stragent_generate_report" }
                        .invoke(buildJsonObject { put("datasetId", datasetId) })
                }
                assertEquals("## 报告\n\n见 {{chart:0}}。", receipt["report"]!!.jsonPrimitive.content, "finish 的 args.report 应解出为报告正文")
                val charts = receipt["charts"] as JsonArray
                assertEquals(1, charts.size, "suggest_chart 的产物应随回执交出：$charts")
                val chart = charts[0].jsonObject
                assertEquals("bar", chart["type"]!!.jsonPrimitive.content)
                assertEquals("各组销售额", chart["title"]!!.jsonPrimitive.content)
                assertEquals(
                    listOf("West", "East"),
                    (chart["labels"] as JsonArray).map { it.jsonPrimitive.content },
                )
                assertEquals(
                    listOf(725457.94, 678781.37),
                    (chart["values"] as JsonArray).map { it.jsonPrimitive.content.toDouble() },
                )
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `classic 路径回执——charts 键恒输出为空数组（T-032 同纪律）`() {
        val home = tempHome()
        try {
            StrAgentHostBootstrap.create(home, llm = FakeLlmClient()).use { bootstrap ->
                val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "无图表样本") })["datasetId"]
                        as kotlinx.serialization.json.JsonPrimitive
                    ).content
                val receipt = com.stragent.data.McpTraceContext.with(null) {
                    tools.first { it.name == "stragent_generate_report" }
                        .invoke(buildJsonObject { put("datasetId", datasetId) })
                }
                assertEquals(0, (receipt["charts"] as JsonArray).size, "classic 不产图表建议，但键必须恒在")
                assertEquals("false", receipt["engineFallback"]!!.jsonPrimitive.content, "T-091：回落标记恒输出（默认 classic 不回落）")
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
