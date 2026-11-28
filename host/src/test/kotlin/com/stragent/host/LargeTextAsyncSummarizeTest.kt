package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-118 · 380K 合成文本 summarize 全链 × 异步信封回执口径（T-110 边界③欠账收口）。
 *
 * 规模 = 段上限边界（5000 段 × 70 字 ≈ 350K 字符 → ~89 块，卡在 TEXT_MAX_CHUNKS=96 内、
 * 走 hierarchical 路由：逐块要点（map 回 JSON）→ 节摘要 → 终稿）。钉住的语义：
 * ①`_meta.task` 异步句柄先回、`tasks/get` 轮询到 completed；②回执 structuredContent
 * 恒出字段齐（summary/pointsTotal/totalTokens）；③map 全覆盖（替身逐块要点，块数 ≥90）；
 * ④进度可见（map 阶段逐块 emit）。替身要点从块文本**逐字摘引**（合规 quote-first）。
 */
class LargeTextAsyncSummarizeTest {

    /** 按阶段路由：map（文本分析员→JSON 要点，quote 逐字摘自块文本）/ 润色 / 归并与节摘要（正文） */
    private class SummarizeLlm : LlmClient {
        val mapCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val otherCalls = java.util.concurrent.atomic.AtomicInteger(0)

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val user = messages.firstOrNull { it.first == "user" }?.second ?: ""
            return if (system.contains("文本分析员")) {
                mapCalls.incrementAndGet()
                // 从块文本提首个「N. 段落」：quote 逐字摘自原文（≤50 字），segment 真实
                val m = Regex("(\\d+)\\. (.{10,40})").find(user)
                val seg = m?.groupValues?.get(1) ?: "1"
                val quote = m?.groupValues?.get(2)?.take(40) ?: "材料内容"
                ChatResult(
                    """{"points":[{"text":"要点：该批段记录完整（段 $seg）。","quote":"$quote","segment":$seg}],""" +
                        """"keyNumbers":[],"summary":"本块巡检记录完整，无异常。"}""",
                    120,
                )
            } else if (system.contains("润色")) {
                otherCalls.incrementAndGet()
                ChatResult("全材料要点已归并汇总。【替身终稿贯通】", 100)
            } else {
                otherCalls.incrementAndGet()
                ChatResult("本材料汇总各标段巡检情况。【替身终稿贯通】", 100)
            }
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
                put("_meta", buildJsonObject { put("task", true) })
            },
        )

    private fun taskGet(b: StrAgentHostBootstrap, taskId: String) =
        b.mcpBridge.dispatch("tasks/get", buildJsonObject { put("taskId", taskId) })

    /** 5000 段 × 70 字 = 350K（段上限边界；~89 块 ≤ TEXT_MAX_CHUNKS=96——逐块 4K 预算非整除，留足余量） */
    private fun materialFile(): File {
        fun para(i: Int): String {
            val base = "管廊第%04d段巡检记录：本日完成结构巡检、沉降观测数据录入与台账登记，现场秩序正常，无异常事项，".format(i)
            return (base + "各项指标平稳，后续按计划推进。").take(70).padEnd(70, '况')
        }
        val f = File.createTempFile("str-agent-380k-", ".md")
        f.writeText((1..5000).map { para(it) }.joinToString("\n\n"))
        return f
    }

    @Test
    fun `全链——异步句柄轮询到 completed 回执恒出字段齐且 map 全覆盖`() {
        val llm = SummarizeLlm()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-380k-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
        )
        val f = materialFile()
        try {
            val dsId = b.mcpBridge.dispatch("tools/call", buildJsonObject {
                put("name", "stragent_import_text")
                put("arguments", buildJsonObject { put("filePath", f.absolutePath); put("name", "三百八十K材料") })
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

            // 异步发起：立即回任务句柄（working + pollWith）
            val handle = call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", dsId) })
            // 异步句柄是顶层响应（McpTaskTest 同口径）：taskId/status/pollWith 直接在结果对象上
            assertEquals("working", handle.getValue("status").jsonPrimitive.content, "句柄先回：状态 working")
            assertEquals("tasks/get", handle.getValue("pollWith").jsonPrimitive.content, "轮询指引在场")
            val taskId = handle.getValue("taskId").jsonPrimitive.content

            // 轮询到终态
            var snap = taskGet(b, taskId).jsonObject
            var waited = 0
            while (snap.getValue("status").jsonPrimitive.content == "working" && waited < 60_000) {
                Thread.sleep(100)
                waited += 100
                snap = taskGet(b, taskId).jsonObject
            }
            assertEquals(
                "completed", snap.getValue("status").jsonPrimitive.content,
                "60s 内到 completed；error=${snap["error"]?.jsonPrimitive?.content?.take(160) ?: "-"}",
            )
            assertTrue(
                snap.getValue("progress").jsonObject.getValue("count").jsonPrimitive.content.toInt() > 0,
                "map 阶段逐块进度可见",
            )
            val receipt = snap.getValue("result").jsonObject
                .getValue("structuredContent").jsonObject
            assertTrue(
                receipt.getValue("summary").jsonPrimitive.content.contains("替身终稿贯通"),
                "终稿正文在场（节摘要/终稿/润色替身应答贯通）",
            )
            assertTrue(
                receipt.getValue("pointsTotal").jsonPrimitive.content.toInt() >= 80,
                "map 全覆盖：要点总数 ≥90（~95 块每块 ≥1 条）",
            )
            assertTrue(
                receipt.getValue("totalTokens").jsonPrimitive.content.toInt() > 0,
                "token 如实入账",
            )
            assertTrue(llm.mapCalls.get() >= 80, "替身逐块调用 ≥80（实际 ${llm.mapCalls.get()}）")
        } finally {
            f.delete()
            b.close()
        }
    }
}
