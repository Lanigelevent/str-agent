package com.stragent.host

import com.stragent.core.ports.BlockSummarizer
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-117 块摘要器 host 集成（bootstrap 装配 → HostContext → Plugin → Orchestrator → core 全链）：
 * 主题词只在摘要里的问题经摘要节点把远端块成员段带进窗；未装配负控 = T-116 原块行为。
 */
class SummaryTreeIntegrationTest {

    private class RoutingLlm : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        var nextAnswerReply = "按材料回答。"

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls += messages
            return ChatResult(nextAnswerReply, 55)
        }
    }

    /** 固定摘要替身：含目标段的块给主题摘要，其余通用（P-150 替身纪律） */
    private class FixedSummarizer : BlockSummarizer {
        override fun summariesFor(
            llm: LlmClient,
            model: String,
            blocks: List<String>,
            cancelSignal: () -> Boolean,
        ): List<String?> = blocks.map { b ->
            if (b.contains("第030标段")) "本块汇总桥梁附属结构验收专题内容，含中间验收组织与资料归档安排。"
            else "本块汇总日常巡检记录与现场管理情况。"
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    /** ~204 字/段 × 40 段：细层每段独立成句组；1-3 段含「开工」；30 段为验收专题目标 */
    private fun materialFile(): File {
        fun seg(i: Int): String {
            val head = "管廊项目第%03d标段巡检批次记录：".format(i)
            val body = if (i <= 3) "本日完成该标段开工条件核查与首件工程验收交底，" else "本日对该区域管廊本体结构与排水设施开展例行巡检，"
            val tail = "巡检发现的零星缺陷已当场处置并登记台账，现场材料堆放有序，照明与通风设备运行正常。" +
                "后续按计划开展下一批次巡检工作。".repeat(6)
            return head + body + tail
        }
        val f = File.createTempFile("str-agent-stree-", ".md")
        f.writeText((1..40).map { seg(it) }.joinToString("\n\n"))
        return f
    }

    @Test
    fun `摘要树全链——主题问题经摘要节点召回远端块成员段`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-stree-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
            blockSummarizer = FixedSummarizer(),
        )
        try {
            val f = materialFile()
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath)
                put("name", "摘要树材料")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content
            val r = call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", "开工与桥梁附属结构验收专题情况")
            })["structuredContent"]!!.jsonObject
            val answerUser = llm.calls.last().first { it.first == "user" }.second
            assertTrue(answerUser.contains("第030标段"), "摘要节点命中：远端块成员段整块进窗")
            assertFalse(answerUser.contains("桥梁附属结构验收专题"), "索引专用：摘要不进材料")
            assertEquals("false", r.getValue("gateFailed").jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun `未装配负控——原块粗层行为远端段不进窗`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-stree-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        try {
            val f = materialFile()
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath)
                put("name", "摘要树材料")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content
            call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", "开工与桥梁附属结构验收专题情况")
            })
            val answerUser = llm.calls.last().first { it.first == "user" }.second
            assertFalse(answerUser.contains("第030标段"), "原块粗层（T-116）：主题词只在摘要里时远端段不进窗")
        } finally {
            b.close()
        }
    }
}
