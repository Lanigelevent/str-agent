package com.stragent.host

import com.stragent.core.ai.CachedChunkContextClient
import com.stragent.core.ai.LlmChunkContextualizer
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
 * T-114 块上下文 host 集成（真实 LlmChunkContextualizer + 缓存走全链）：
 * ① 补语桥接——问题与目标段零词面重合时经补语命中进窗（材料里只有原文，索引专用纪律）；
 * ② 缓存——同材料第二次追问上下文零 LLM 调用，回执计数不降；
 * ③ 未接线负控零变化。材料运行时生成（CI 可复现）。
 */
class ChunkContextIntegrationTest {

    /** 按 system 内容路由：补全器 / 回答分轨应答 */
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
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.firstOrNull { it.first == "user" }?.second ?: ""
            return if (system.contains("补全器")) {
                // 含目标段的批次：块 5 给桥接补语；其余批次给通用补语（问题 bigram 无重合）
                if (user.contains("该部位设计值为十公分")) {
                    ChatResult(
                        "块1：工程文档常规段落说明\n块2：工程文档常规段落说明\n" +
                            "块3：工程文档常规段落说明\n块4：工程文档常规段落说明\n" +
                            "块5：桥梁工程桥面铺装设计参数说明",
                        55,
                    )
                } else {
                    ChatResult(
                        "块1：工程文档常规段落说明\n块2：工程文档常规段落说明\n" +
                            "块3：工程文档常规段落说明\n块4：工程文档常规段落说明\n" +
                            "块5：工程文档常规段落说明",
                        55,
                    )
                }
            } else {
                ChatResult(nextAnswerReply, 55)
            }
        }

        fun ctxCalls() = calls.count { m -> m.firstOrNull { it.first == "system" }?.second?.contains("补全器") == true }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    /** 临时材料：段 1（问题词面命中）+ 8 填充 + 段 10（零重合目标） */
    private fun materialFile(): File {
        val filler = "第%d项议题讨论情况记录条目，内容涉及日常事务安排与例行检查工作。"
        val paras = buildList {
            add("本工程为桥梁工程，含桥面系施工内容。")
            repeat(8) { i -> add(filler.format(i + 1)) }
            add("该部位设计值为十公分。")
        }
        val f = File.createTempFile("str-agent-ctx-", ".md")
        f.writeText(paras.joinToString("\n\n"))
        return f
    }

    private fun import(b: StrAgentHostBootstrap, f: File): String =
        call(b, "stragent_import_text", buildJsonObject {
            put("filePath", f.absolutePath)
            put("name", "上下文材料")
        })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

    private fun followup(b: StrAgentHostBootstrap, dsId: String, q: String) =
        call(b, "stragent_ask_followup", buildJsonObject {
            put("datasetId", dsId); put("question", q)
        })["structuredContent"]!!.jsonObject

    @Test
    fun `补语桥接与缓存——目标段进窗二次追问零上下文调用`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-ctx-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
            chunkContext = CachedChunkContextClient(LlmChunkContextualizer, com.stragent.data.FileDatasetStore(File(System.getProperty("java.io.tmpdir"), "str-agent-ctx-kv-" + System.nanoTime()).also { it.mkdirs() })),
        )
        try {
            val dsId = import(b, materialFile())
            val r1 = followup(b, dsId, "桥面铺装厚度是多少")
            assertEquals(10, r1.getValue("chunkContextCount").jsonPrimitive.content.toInt(), "回执计数：全部块获得补语")
            val answerUser1 = llm.calls.last().first { it.first == "user" }.second
            assertTrue(answerUser1.contains("该部位设计值为十公分"), "补语桥接：目标段进窗")
            assertFalse(answerUser1.contains("桥梁工程桥面铺装设计参数说明"), "索引专用：补语不进材料")
            val ctxCallsAfterFirst = llm.ctxCalls()
            assertEquals(2, ctxCallsAfterFirst, "10 块 = 2 次批量轻调用")
            // 第二次追问：缓存全命中，上下文零新调用，计数不降
            val r2 = followup(b, dsId, "桥面铺装厚度是多少")
            assertEquals(10, r2.getValue("chunkContextCount").jsonPrimitive.content.toInt(), "缓存命中：计数不降")
            assertEquals(ctxCallsAfterFirst, llm.ctxCalls(), "同材料二次追问上下文零调用")
        } finally {
            b.close()
        }
    }

    @Test
    fun `未接线负控——计数 0 且零重合段不进窗`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-ctx-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        try {
            val dsId = import(b, materialFile())
            val r = followup(b, dsId, "桥面铺装厚度是多少")
            assertEquals(0, r.getValue("chunkContextCount").jsonPrimitive.content.toInt(), "未启用恒输出 0")
            val answerUser = llm.calls.single().first { it.first == "user" }.second
            assertFalse(answerUser.contains("该部位设计值为十公分"), "单查询召不回零重合段（对照①）")
        } finally {
            b.close()
        }
    }
}
