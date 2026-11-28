package com.stragent.core.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T-171 · 用量与成本账本测试。
 *
 * 覆盖：累计与分面（按工具 / 按引擎）/ 成本估算（有单价 → 估算金额；无单价 → **不写金额**
 * 且 basis=unpriced）/ 单次用量块 / 零 token 不计 / 幂等快照形状。
 */
class UsageLedgerTest {

    @Test
    fun `累计——按工具与按引擎分面`() {
        val ledger = UsageLedger(costPer1kTokens = 0.001)
        ledger.record("stragent_generate_report", 1000, "classic")
        ledger.record("stragent_generate_report", 500, "classic")
        ledger.record("stragent_summarize_text", 200)

        assertEquals(1700L, ledger.totalTokens())
        val snapshot = ledger.snapshot()
        val totals = snapshot["totals"]!!.jsonObject
        assertEquals(3, totals["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(1700, totals["tokens"]!!.jsonPrimitive.content.toInt())

        val byTool = snapshot["byTool"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, byTool.size, "两个工具分面：$byTool")
        val report = byTool.first { it["tool"]!!.jsonPrimitive.content == "stragent_generate_report" }
        assertEquals(2, report["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(1500, report["tokens"]!!.jsonPrimitive.content.toInt())

        val byEngine = snapshot["byEngine"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, byEngine.size, "只有报告记引擎分面：$byEngine")
        assertEquals("classic", byEngine.first()["engine"]!!.jsonPrimitive.content)
    }

    @Test
    fun `成本估算——有单价给估算金额且 basis 明写 estimate`() {
        val ledger = UsageLedger(costPer1kTokens = 0.002)
        ledger.record("t", 2000)
        val totals = ledger.snapshot()["totals"]!!.jsonObject
        assertEquals("estimate", totals["costBasis"]!!.jsonPrimitive.content)
        assertEquals(0.004, totals["estCostUsd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(0.002, totals["rateUsdPer1kTokens"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `无单价——不写金额（不把未知成本写成 0）`() {
        val ledger = UsageLedger(costPer1kTokens = null)
        ledger.record("t", 2000)
        val totals = ledger.snapshot()["totals"]!!.jsonObject
        assertEquals("unpriced", totals["costBasis"]!!.jsonPrimitive.content)
        assertFalse(totals.containsKey("estCostUsd"), "未配单价就不该有金额：$totals")
        assertFalse(totals.containsKey("rateUsdPer1kTokens"), "未配单价就没有单价：$totals")
        // token 数照给——零成本路径也有结构化数字
        assertEquals(2000, totals["tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `单次用量块——随回执给出，带口径与金额`() {
        val ledger = UsageLedger(costPer1kTokens = 0.001)
        val block = ledger.usageBlock("stragent_generate_report", 1500, "classic")
        assertEquals("stragent_generate_report", block["tool"]!!.jsonPrimitive.content)
        assertEquals(1, block["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(1500, block["tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("classic", block["engine"]!!.jsonPrimitive.content)
        assertEquals("estimate", block["costBasis"]!!.jsonPrimitive.content)
        assertNotNull(block["estCostUsd"], "有单价时给估算金额")
    }

    @Test
    fun `按模型分面——精确模型名入账，未知名落 unknown，与工具分面分开`() {
        val ledger = UsageLedger(costPer1kTokens = 0.002)
        ledger.recordLlmCall("step-3.5-flash", 1000, ok = true)
        ledger.recordLlmCall("gpt-4o-2024-08-06", 500, ok = true)
        ledger.recordLlmCall("gpt-4o-2024-08-06", 200, ok = false)
        ledger.recordLlmCall(null, 10, ok = true)

        val byModel = ledger.snapshot()["byModel"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .map { it.jsonObject }
        assertEquals(3, byModel.size, "三个模型桶：${byModel}")
        val gpt = byModel.first { it["model"]!!.jsonPrimitive.content == "gpt-4o-2024-08-06" }
        assertEquals(2, gpt["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(700, gpt["tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, gpt["errors"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.0014, gpt["estCostUsd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(byModel.any { it["model"]!!.jsonPrimitive.content == "unknown" }, "未知名落 unknown")
        // 两个分面来自不同采集点，口径写在 note 里（不相加）
        val note = ledger.snapshot()["note"]!!.jsonPrimitive.content
        assertTrue(note.contains("不相加"), "快照要写明分面口径：$note")
    }

    @Test
    fun `模型标签族收敛——series 上界固定，精确名不丢`() {
        val f = com.stragent.core.observability.StrAgentMetrics::modelFamilyLabel
        assertEquals("step", f("step-3.5-flash"))
        assertEquals("gpt", f("GPT-4o-2024-08-06"))
        assertEquals("glm", f("glm-4.6"))
        assertEquals("other", f("some-random-model-v7"))
        assertEquals("unknown", f(null))
        // 同一族的多个版本 → 同一标签（series 不随版本升级增长）
        assertEquals(f("gpt-4o"), f("gpt-4o-2024-08-06"))
    }

    @Test
    fun `零 token 不计——不知道就不记`() {
        val ledger = UsageLedger(costPer1kTokens = 0.001)
        ledger.record("t", 0)
        ledger.record("t", -5)
        assertEquals(0L, ledger.totalTokens())
        val byTool = ledger.snapshot()["byTool"]!!.jsonArray
        assertEquals(0, byTool.size, "没有用量就没有分面桶：$byTool")
    }
}
