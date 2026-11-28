package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.data.abilityTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-044 · 发稿门禁字段透传到 MCP 回执（端到端）。
 *
 * 门禁在 core/str-agent 里跑完还不够——外部 agent 与 UI 只能看到**回执**。
 * 本测试证明三个字段真的到了回执上，且语义与内部一致：
 *  - `gateFailed`：硬伤经 ≤2 轮修订仍未清零（报告照发，但不静默放行）；
 *  - `gateIssues`：终稿的全量问题清单（含软问题），失败时是宿主的行动依据；
 *  - `gateRevisions`：门禁花掉的修订轮数（成本可见）。
 *
 * 追问回执只有前两个字段：对话回答是记录级门禁，不改写、不修订。
 */
class ReportGateOutputTest {

    /** 按 prompt 形状分流：修订 → 预设修订稿；语义审查 → 空问题；追问 → 预设回答；其余 → 成文稿 */
    private class GateLlm(
        private val report: String,
        private val revised: String,
        private val followup: String = "总销售额 1500 元。",
    ) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls.add(messages)
            val joined = messages.joinToString("\n") { it.second }
            val text = when {
                joined.contains("<gate_issues>") -> revised
                joined.contains("\"issues\"") -> """{"issues":[]}"""
                joined.contains("<followup_requirements>") -> followup
                else -> report
            }
            return ChatResult(text, (text.length / 4).coerceAtLeast(1))
        }
    }

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n华东,200,8\n"

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-gate-${System.nanoTime()}").also { it.mkdirs() }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private fun JsonObject.bool(key: String): Boolean = this.str(key).toBooleanStrict()

    private fun JsonObject.int(key: String): Int = this.str(key).toInt()

    private fun JsonObject.issues(key: String): List<String> =
        (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: error("缺少数组字段 $key：$this")

    private class Env(
        val bootstrap: StrAgentHostBootstrap,
        val datasetId: String,
    ) : AutoCloseable {
        val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory)
        fun tool(name: String) = tools.first { it.name == name }
        override fun close() = bootstrap.close()
    }

    private fun env(llm: LlmClient): Env {
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = llm)
        // 走真实导入工具（与外部 agent 同一条路径）
        val tools = abilityTools(bootstrap.abilityDatasets) + pluginTools(bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory)
        val id = tools.first { it.name == "stragent_import_table" }
            .invoke(buildJsonObject { put("csv", csv); put("name", "门禁样本") })
            .str("datasetId")
        return Env(bootstrap, id)
    }

    @Test
    fun `报告回执——修订后过闸：gateFailed=false、轮数与问题清单可见`() {
        val llm = GateLlm(report = "## 总体概览\n\n总销售额 888888 元。\n", revised = "## 总体概览\n\n总销售额 1500 元。\n")
        env(llm).use { e ->
            val receipt = e.tool("stragent_generate_report").invoke(buildJsonObject { put("datasetId", e.datasetId) })
            assertEquals("## 总体概览\n\n总销售额 1500 元。", receipt.str("report"), "回执带的是修订后的终稿")
            assertFalse(receipt.bool("gateFailed"))
            assertEquals(1, receipt.int("gateRevisions"))
            assertTrue(receipt.issues("gateIssues").isNotEmpty(), "软问题注记也应可见：$receipt")
            assertTrue(llm.calls.any { it.joinToString("\n") { m -> m.second }.contains("<gate_issues>") }, "修订确实发生过")
        }
    }

    @Test
    fun `报告回执——两轮修不干净：gateFailed=true 且问题清单可定位`() {
        val dirty = "## 总体概览\n\n总销售额 888888 元。\n"
        val stillDirty = "## 总体概览\n\n总销售额 999999 元。\n"
        val llm = GateLlm(report = dirty, revised = stillDirty)
        env(llm).use { e ->
            val receipt = e.tool("stragent_generate_report").invoke(buildJsonObject { put("datasetId", e.datasetId) })
            assertTrue(receipt.bool("gateFailed"), "修不干净必须如实标记失败：$receipt")
            assertEquals(2, receipt.int("gateRevisions"))
            assertTrue(
                receipt.issues("gateIssues").any { it.contains("unsourced_number") && it.contains("999999") },
                "问题清单要能定位到未溯源数字：${receipt.issues("gateIssues")}",
            )
            assertTrue(receipt.str("report").contains("999999"), "报告照发（不静默丢稿）")
        }
    }

    @Test
    fun `追问回执——记录级门禁：假数字标记失败但回答原样返回`() {
        val llm = GateLlm(
            report = "## 总体概览\n\n总销售额 1500 元。\n",
            revised = "## 总体概览\n\n总销售额 1500 元。\n",
            followup = "华东的销售额是 888888 元，远高于华北。",
        )
        env(llm).use { e ->
            val receipt = e.tool("stragent_ask_followup").invoke(
                buildJsonObject { put("datasetId", e.datasetId); put("question", "华东销售额多少？") },
            )
            assertEquals("华东的销售额是 888888 元，远高于华北。", receipt.str("answer"), "对话回答不许被静默改写")
            assertTrue(receipt.bool("gateFailed"))
            assertFalse(receipt.containsKey("gateRevisions"), "追问是记录级门禁，没有修订轮数")
            assertTrue(
                receipt.issues("gateIssues").any { it.contains("unsourced_number") && it.contains("888888") },
                "${receipt.issues("gateIssues")}",
            )
        }
    }
}
