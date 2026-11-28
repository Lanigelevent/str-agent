package com.stragent.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-171 · 用量与成本接线测试（宿主层）。
 *
 * 覆盖：①报告回执带 `usage` 块（tokens + 口径，金额恒标估算）；②账本按工具累计；
 * ③/ui 驾驶舱总览带累计用量视图。
 */
class UsageLedgerWiringTest {

    private val csv = "地区,销售额\n华东,1000\n华北,300\n"

    @Test
    fun `报告回执带 usage 块——口径明写，金额是估算`() {
        val home = Files.createTempDirectory("usage-ledger").toFile()
        try {
            StrAgentHostBootstrap.create(home, llm = FixedTokenLlm(1200)).use { bootstrap ->
                val ledger = com.stragent.core.observability.UsageLedger(costPer1kTokens = 0.002)
                val tools = com.stragent.data.abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                    usageLedger = ledger,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "样本") })["datasetId"]
                        as JsonPrimitive
                    ).content

                val receipt = tools.first { it.name == "stragent_generate_report" }
                    .invoke(buildJsonObject { put("datasetId", datasetId) })

                val usage = receipt["usage"]?.jsonObject
                    ?: error("回执应带 usage 块：$receipt")
                assertEquals("stragent_generate_report", usage["tool"]!!.jsonPrimitive.content)
                assertEquals(1, usage["calls"]!!.jsonPrimitive.content.toInt())
                assertTrue(usage["tokens"]!!.jsonPrimitive.content.toInt() > 0, "token 数应大于 0：$usage")
                assertEquals("estimate", usage["costBasis"]!!.jsonPrimitive.content, "金额口径必须明写是估算")
                assertTrue(usage.containsKey("estCostUsd"), "配了单价就该给估算金额：$usage")

                // 账本已累计（按工具分面）
                val snapshot = ledger.snapshot()
                val byTool = snapshot["byTool"]!!.let { it as JsonArray }.map { it.jsonObject }
                assertTrue(byTool.any { it["tool"]!!.jsonPrimitive.content == "stragent_generate_report" }, "账本按工具分面：$byTool")
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `未配单价——回执给 token 数但不给金额`() {
        val home = Files.createTempDirectory("usage-ledger-noprice").toFile()
        try {
            StrAgentHostBootstrap.create(home, llm = FixedTokenLlm(800)).use { bootstrap ->
                val ledger = com.stragent.core.observability.UsageLedger(costPer1kTokens = null)
                val tools = com.stragent.data.abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                    usageLedger = ledger,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "样本") })["datasetId"]
                    as JsonPrimitive
                    ).content
                val receipt = tools.first { it.name == "stragent_generate_report" }
                    .invoke(buildJsonObject { put("datasetId", datasetId) })
                val usage = receipt["usage"]!!.jsonObject
                assertEquals("unpriced", usage["costBasis"]!!.jsonPrimitive.content)
                assertTrue(!usage.containsKey("estCostUsd"), "未配单价不该有金额：$usage")
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `驾驶舱总览带累计用量视图`() {
        val ledger = com.stragent.core.observability.UsageLedger(costPer1kTokens = 0.001)
        ledger.record("stragent_generate_report", 3000, "classic")
        val provider = UiDashboardData(
            com.stragent.data.AbilityDatasets(), RecentReports(), "1.1.0", usageLedger = ledger,
        )
        val overview = provider.overview()
        val usage = overview["usage"]!!.jsonObject
        val totals = usage["totals"]!!.jsonObject
        assertEquals(3000, totals["tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("estimate", totals["costBasis"]!!.jsonPrimitive.content)
        assertEquals(0.003, totals["estCostUsd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    /** 固定 token 数的假模型（只关心用量口径，不关心内容） */
    private class FixedTokenLlm(private val tokens: Int) : com.stragent.core.ports.LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): com.stragent.core.ports.ChatResult =
            com.stragent.core.ports.ChatResult(
                text = "## 报告\n\n华东 1000。",
                totalTokens = tokens,
                truncated = false,
            )

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): com.stragent.core.ports.ChatResult = chat(model, messages, temperature, maxTokens)
    }
}
