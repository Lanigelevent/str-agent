package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
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
 * T-113 查询分解 host 集成（真实 LlmQueryDecomposer 走全链）：
 * bootstrap(decomposer) → HostContext → Plugin → Orchestrator → core 追问文本分支；
 * ① 分解应答经真实分解器解析后逐子查询检索、RRF 合并——原始查询召不回的第二跳源进窗；
 * ② 回执 subQueries 恒输出（空表=未启用）；③ 未接线负控零变化。
 * 材料用运行时生成的临时文本（不依赖本机桌面路径，CI 可复现）。
 */
class QueryDecompIntegrationTest {

    /** 按 system 内容路由：分解调用（含「分解器」）与回答调用（含 text_material）分轨应答 */
    private class RoutingLlm : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        var nextDecomposeReply = ""
        var nextAnswerReply = "（占位应答）"

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls += messages
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.firstOrNull { it.first == "user" }?.second ?: ""
            return if (system.contains("分解器")) {
                ChatResult(nextDecomposeReply, 55)
            } else {
                ChatResult(nextAnswerReply, 55)
            }
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    /** 写临时材料：跳 1 段 + 8 填充段 + 跳 2 段（空行分段 → 导入侧每段一个 TextSegment） */
    private fun materialFile(): File {
        val filler = "第%d项议题讨论情况记录条目，内容涉及日常事务安排与例行检查工作。"
        val paras = buildList {
            add("本工程由华东建设集团承建，项目负责人是张三。")
            repeat(8) { i -> add(filler.format(i + 1)) }
            add("张三兼任集团安全生产委员会主任。")
        }
        val f = File.createTempFile("str-agent-decomp-", ".md")
        f.writeText(paras.joinToString("\n\n"))
        return f
    }

    @Test
    fun `分解全链——子查询合并召回第二跳源且回执回显`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-qd-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
            decomposer = com.stragent.core.ai.LlmQueryDecomposer,
        )
        try {
            val f = materialFile()
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath)
                put("name", "多跳材料")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

            llm.nextDecomposeReply = "1. 承建单位与项目负责人\n2. 张三兼任职务"
            llm.nextAnswerReply = "张三兼任集团安全生产委员会主任（段 10），承建单位为华东建设集团（段 1）。"
            val r = call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", "承建负责人的其他任职")
            })["structuredContent"]!!.jsonObject

            val subs = r.getValue("subQueries").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("承建单位与项目负责人", "张三兼任职务"), subs, "回执回显生效子查询")
            // 回答调用的材料块包含第二跳源——单查询召不回（负控见下），分解合并后进窗
            val answerUser = llm.calls.last().first { it.first == "user" }.second
            assertTrue(answerUser.contains("张三兼任集团安全生产委员会主任"), "第二跳源经分解合并进窗")
            assertTrue(answerUser.contains("承建"), "跳 1 源仍在窗")
            assertEquals("false", r.getValue("gateFailed").jsonPrimitive.content, "回答可回链材料")
            assertEquals(110, r.getValue("totalTokens").jsonPrimitive.content.toInt(), "分解 55 + 回答 55")
        } finally {
            b.close()
        }
    }

    @Test
    fun `未接线负控——不配 decomposer 时回执空表且行为单查询`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-qd-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        try {
            val f = materialFile()
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath)
                put("name", "多跳材料")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

            llm.nextAnswerReply = "承建单位为华东建设集团（段 1）。"
            val r = call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", "承建负责人的其他任职")
            })["structuredContent"]!!.jsonObject

            assertEquals(0, r.getValue("subQueries").jsonArray.size, "未启用：回执空表恒输出")
            val answerUser = llm.calls.single().first { it.first == "user" }.second
            assertTrue(!answerUser.contains("张三兼任"), "单查询召不回第二跳源")
        } finally {
            b.close()
        }
    }
}
