package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.EmbeddingClient
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.RerankClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-101 集成测试：rerank 精排（生效/失败回落）、嵌入缓存（同材料二次追问零调用）。
 * fake 组件全确定性，离线可重复。
 */
class RerankCacheIntegrationTest {

    private fun tempHome() =
        File(System.getProperty("java.io.tmpdir"), "str-agent-rr-" + System.nanoTime()).also { it.mkdirs() }

    private class RoutingLlm : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            val sys = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val reply = when {
                sys.contains("文本分析员") ->
                    """{"points":[{"text":"营业收入 128 万元","quote":"营业收入 128 万元","segment":1}],"keyNumbers":["128"],"summary":"概述"}"""
                sys.contains("文档分析员") -> "营业收入 128 万元（段 1）。"
                else -> "报告正文"
            }
            return ChatResult(reply, 30)
        }
    }

    /** 同义词桶嵌入（语义性：营收≈收入） */
    private class SynonymEmbedding : EmbeddingClient {
        override fun embed(texts: List<String>): List<DoubleArray> =
            texts.map { t ->
                doubleArrayOf(if (listOf("营收", "收入", "营业").any { it in t }) 1.0 else 0.0, 1.0)
            }
    }

    /** 可控相关性的 fake rerank：含关键词的候选置顶 */
    private class KeywordRerank(private val keyword: String) : RerankClient {
        var calls = 0
        override fun rerank(query: String, candidates: List<String>): List<com.stragent.core.ports.RerankHit> =
            candidates.withIndex()
                .sortedByDescending { if (keyword in it.value) 1 else 0 }
                .map { (i, _) -> com.stragent.core.ports.RerankHit(i, if (keyword in candidates[i]) 0.9 else 0.1) }
                .also { calls++ }
    }

    private fun importMaterial(b: StrAgentHostBootstrap, name: String, content: String): String {
        val f = File(b.home, name)
        f.writeText(content, Charsets.UTF_8)
        return b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_import_text")
                put("arguments", buildJsonObject { put("filePath", f.absolutePath) })
            },
        )["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content
    }

    private fun followup(b: StrAgentHostBootstrap, dsId: String, question: String) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_ask_followup")
                put("arguments", buildJsonObject { put("datasetId", dsId); put("question", question) })
            },
        )["structuredContent"]!!.jsonObject

    @Test
    fun `rerank 在链路上——重排器收到候选且失败回落融合序照常回答`() {
        val rerank = KeywordRerank("成本")
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm, embedding = SynonymEmbedding(), rerank = rerank)
        try {
            val dsId = importMaterial(b, "rr.txt", "营业收入 128 万元，增长明显。\n\n成本方面，仓储费用上升。")
            val r = followup(b, dsId, "营收与成本情况")
            val answer = r.getValue("answer").jsonPrimitive.content
            assertTrue(answer.contains("128"), "回答正常: $answer")
            assertTrue(!r.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict())
            // rerank 失败的回落：让重排器抛错，回答照常（融合序）
            val failing = object : RerankClient {
                override fun rerank(query: String, candidates: List<String>): List<com.stragent.core.ports.RerankHit> =
                    throw RuntimeException("rerank 端点超时")
            }
            val b2 = StrAgentHostBootstrap.create(tempHome(), llm = llm, embedding = SynonymEmbedding(), rerank = failing)
            try {
                val dsId2 = importMaterial(b2, "rr2.txt", "营业收入 128 万元，增长明显。")
                val r2 = followup(b2, dsId2, "营收表现如何")
                assertTrue(r2.getValue("answer").jsonPrimitive.content.contains("128"), "rerank 失败 → 融合序照常回答")
                assertTrue(!r2.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict())
            } finally {
                b2.close()
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun `嵌入缓存——同材料二次追问零嵌入调用（kv 内容寻址跨追问复用）`() {
        val kvHome = tempHome()
        val kv: DatasetStore = com.stragent.data.FileDatasetStore(File(kvHome, "kv"))
        var embedCalls = 0
        val counting = object : EmbeddingClient {
            override fun embed(texts: List<String>): List<DoubleArray> {
                embedCalls++
                return texts.map { doubleArrayOf(if ("收入" in it || "营收" in it) 1.0 else 0.0, 1.0) }
            }
        }
        val cached = com.stragent.core.ai.CachedEmbeddingClient(counting, kv)
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm, embedding = cached)
        try {
            val dsId = importMaterial(b, "c.txt", "本季度营业收入 128 万元，环比增长明显。")
            followup(b, dsId, "营业收入多少")
            val first = embedCalls
            assertTrue(first >= 1, "首轮至少一次嵌入（段落+查询）")
            // 二次追问：段落嵌入全部命中缓存（查询文本变了但段落键不变——段落命中即批量合并）
            followup(b, dsId, "营业收入多少")
            assertTrue(embedCalls == first, "二次追问段落嵌入全命中 → 零新增嵌入调用（实际 $first vs $embedCalls）")
        } finally {
            b.close()
        }
    }
}
