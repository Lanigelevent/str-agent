package com.stragent.host

import com.stragent.data.McpStdioServer
import com.stragent.data.ProvenanceRecorder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.stragent.core.i18n.Messages
import java.util.Locale

/**
 * T-073 · 追问路径溯源（D21 F-2）端到端验证。
 *
 * 要证明的是**用户能感知的那件事**：追问回答里引用的数字，回执能指出它出现在
 * 哪条已留痕步骤的入参/结果里（`provenanceRef`），并且 stragent_provenance 的
 * steps 数组按同一套下标对得上、重放覆盖这些步骤——引用数字从此可回链。
 *
 * 三种事实分开断言（诚实口径）：
 *  a) 命中：引用数字出现在留痕步骤的数值面里 → 列出步骤下标与命中数字；
 *  b) 未命中：数字来自简报的确定性计算（brief）→ 如实说「未命中任何留痕步骤」；
 *  c) 未接线/无留痕：溯源层没接或进程内没有步骤 → 如实说明，不装作归因成功。
 */
class FollowupProvenanceTest {

    private val csv = "地区,销售额\n华东,100\n华南,80\n华北,60\n"

    private fun bootstrap(llm: FakeLlmClient) =
        StrAgentHostBootstrap.create(createTempDirectory("str-agent-followup-prov").toFile(), llm = llm)

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "第 $index 帧不是成功响应：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    private fun callFrame(id: Int, name: String, args: String): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""

    private fun importFrame(id: Int, csv: String): String {
        val escaped = JsonPrimitive(csv).toString()
        return callFrame(id, "stragent_import_table", """{"csv":$escaped,"name":"样本"}""")
    }

    @Test
    fun `a 命中——引用数字归因到留痕步骤且重放覆盖`() {
        val llm = FakeLlmClient(followup = "华东地区销售额为 100 元。")
        val b = bootstrap(llm)
        try {
            val out = runStdio(
                b.mcpBridge,
                listOf(
                    importFrame(1, csv),
                    callFrame(2, "stragent_session_group_aggregate", """{"dimension":"地区","metric":"销售额","mode":"sum"}"""),
                ),
            )
            val datasetId = toolResult(out, 0)["datasetId"]!!.jsonPrimitive.content

            val followupOut = runStdio(
                b.mcpBridge,
                listOf(
                    callFrame(3, "stragent_ask_followup", """{"datasetId":"$datasetId","question":"华东销售额多少？"}"""),
                ),
            )
            val receipt = toolResult(followupOut, 0)
            val refs = receipt["provenanceRef"]!!.jsonArray
            assertTrue(refs.isNotEmpty(), "引用数字 100 必须命中留痕步骤：$receipt")
            val refTools = refs.map { it.jsonObject["tool"]!!.jsonPrimitive.content }
            assertTrue(refTools.contains("stragent_import_table"), "导入步骤的入参数值面含 100：$refTools")
            assertTrue(refTools.contains("stragent_session_group_aggregate"), "聚合步骤的结果数值面含 100：$refTools")
            val agg = refs.first { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_session_group_aggregate" }.jsonObject
            assertTrue(
                agg["matched"]!!.jsonArray.any { it.jsonPrimitive.content == "100" },
                "命中数字要回传：$agg",
            )
            val note = receipt["provenanceNote"]!!.jsonPrimitive.content
            assertTrue(note.contains("stragent_provenance"), "note 要说明如何复核：$note")

            // stragent_provenance：同一套下标 + 数值面可审计 + 重放一一对应
            val provOut = runStdio(
                b.mcpBridge,
                listOf(callFrame(4, "stragent_provenance", """{"datasetId":"$datasetId"}""")),
            )
            val prov = toolResult(provOut, 0)
            val steps = prov["steps"]!!.jsonArray
            val refIndices = refs.map { it.jsonObject["step"]!!.jsonPrimitive.content.toInt() }
            assertTrue(refIndices.all { it in steps.indices }, "归因下标必须落在 steps 数组内（重放按同一数组覆盖）：$refIndices / ${steps.size}")
            assertTrue(
                steps.any { step ->
                    (step.jsonObject["numTokens"] as? kotlinx.serialization.json.JsonArray)
                        ?.any { it.jsonPrimitive.content == "100" } == true
                },
                "留痕步骤的数值面要在 stragent_provenance 里可见可审计",
            )
            assertEquals(steps.size, prov["replay"]!!.jsonArray.size, "replay 与 steps 同下标一一对应")
        } finally {
            b.close()
        }
    }

    @Test
    fun `b 未命中——数字来自简报计算时如实说明`() {
        val llm = FakeLlmClient(followup = "整体销售额合计是 777 元。")
        val b = bootstrap(llm)
        try {
            val out = runStdio(b.mcpBridge, listOf(importFrame(1, csv)))
            val datasetId = toolResult(out, 0)["datasetId"]!!.jsonPrimitive.content
            val followupOut = runStdio(
                b.mcpBridge,
                listOf(callFrame(2, "stragent_ask_followup", """{"datasetId":"$datasetId","question":"合计多少？"}""")),
            )
            val receipt = toolResult(followupOut, 0)
            assertTrue(receipt["provenanceRef"]!!.jsonArray.isEmpty(), "777 不在任何留痕步骤的数值面里：$receipt")
            val note = receipt["provenanceNote"]!!.jsonPrimitive.content
            assertTrue(note.contains(Messages.get("followup.provenance.no_match")), "未命中要如实说明：$note")
            // 「要点名数字来自简报（brief）」这条语义必须**双语都点名**——
            // 只查中文表会在英文宿主上假红，只查英文表会在中文宿主上假红；
            // 两张表都查则任何一张漏掉「来源」都红（比只查中文表更严，非弱化）。
            assertTrue(
                Messages.get("followup.provenance.no_match", Locale.SIMPLIFIED_CHINESE).contains("简报") &&
                    Messages.get("followup.provenance.no_match", Locale.ENGLISH).contains("brief"),
                "未命中文案要点名数字的真实来源（brief）——中英两表都要点名",
            )
        } finally {
            b.close()
        }
    }

    @Test
    fun `c 未接线与无留痕——如实说明不装作归因成功`() {
        val b = bootstrap(FakeLlmClient())
        b.use {
            // 先直接导入（不经 MCP 桥 → 不留痕）：同一份数据集供两条路径共用
            val import = (com.stragent.data.abilityTools(it.abilityDatasets)).first { t -> t.name == "stragent_import_table" }
            val datasetId = import.invoke(buildJsonObject { put("csv", csv); put("name", "样本") })["datasetId"]!!.jsonPrimitive.content
            val args = buildJsonObject { put("datasetId", datasetId); put("question", "华东销售额多少？") }

            // 未接线：pluginTools 不传 provenance → 回执如实说「未接线」
            val unwired = pluginTools(it.plugin, it.abilityDatasets).first { t -> t.name == "stragent_ask_followup" }
            val unwiredReceipt = unwired.invoke(args)
            assertTrue(
                unwiredReceipt["provenanceRef"]!!.jsonArray.isEmpty(),
                "未接线时归因必空：${unwiredReceipt}",
            )
            assertTrue(unwiredReceipt.str("provenanceNote").contains(Messages.get("followup.provenance.not_wired")), "未接线要如实说明：${unwiredReceipt}")

            // 已接线但进程内无留痕（绕过桥直接调用）→ 回执如实说「还没有分析留痕」
            val wired = pluginTools(it.plugin, it.abilityDatasets, provenance = ProvenanceRecorder(it.abilityDatasets))
                .first { t -> t.name == "stragent_ask_followup" }
            val wiredReceipt = wired.invoke(args)
            assertTrue(wiredReceipt["provenanceRef"]!!.jsonArray.isEmpty(), "无留痕时归因必空：$wiredReceipt")
            assertTrue(
                wiredReceipt.str("provenanceNote").contains(Messages.get("followup.provenance.no_steps")),
                "无留痕要如实说明：$wiredReceipt",
            )
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")
}
