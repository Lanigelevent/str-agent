package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.EmbeddingClient
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-100 hybrid 检索集成测试：语义路补词法盲区（BM25 miss 的同义问法命中）、
 * 未配置零变化负控、嵌入失败回落 BM25。全离线（fake 嵌入 = 确定性同义桶）。
 */
class HybridRetrievalIntegrationTest {

    private fun tempHome() =
        File(System.getProperty("java.io.tmpdir"), "str-agent-hb-" + System.nanoTime()).also { it.mkdirs() }

    /**
     * fake 嵌入：同义词桶的确定性嵌入（模拟「营收 ≈ 收入」的语义性）。
     * 真实嵌入把语义相近文本映射到相近向量；测试用概念桶达到同样效果且完全确定。
     */
    private class SynonymEmbedding : EmbeddingClient {
        override fun embed(texts: List<String>): List<DoubleArray> =
            texts.map { t ->
                val revenue = listOf("营收", "收入", "营业").any { it in t }
                val cost = listOf("成本", "费用").any { it in t }
                doubleArrayOf(if (revenue) 1.0 else 0.0, if (cost) 1.0 else 0.0)
            }
    }

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

    private fun importMaterial(b: StrAgentHostBootstrap): String {
        val f = File(b.home, "m.txt")
        f.writeText("本季度营业收入 128 万元，环比增长明显。", Charsets.UTF_8)
        return b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_import_text")
                put("arguments", buildJsonObject { put("filePath", f.absolutePath) })
            },
        )["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content
    }

    @Test
    fun `语义路补盲——BM25 未命中的同义问法经嵌入命中并过闸`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm, embedding = SynonymEmbedding())
        try {
            val dsId = importMaterial(b)
            // 「营收」与材料「营业收入」bigram 不相交（营收/收营 vs 营业/业收）→ BM25 单路 0 命中
            val r = b.mcpBridge.dispatch(
                "tools/call",
                buildJsonObject {
                    put("name", "stragent_ask_followup")
                    put("arguments", buildJsonObject { put("datasetId", dsId); put("question", "营收表现如何") })
                },
            )["structuredContent"]!!.jsonObject
            val answer = r.getValue("answer").jsonPrimitive.content
            assertTrue(answer.contains("128"), "语义路命中 → 回答带材料数字: $answer")
            assertTrue(!r.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict(), "数字在命中材料 → 过闸")
        } finally {
            b.close()
        }
    }

    @Test
    fun `未配置嵌入——同义问法 0 命中如实拒答（BM25 既有行为零变化负控）`() {
        val llm = RoutingLlm()
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm) // embedding = null
        try {
            val dsId = importMaterial(b)
            val r = b.mcpBridge.dispatch(
                "tools/call",
                buildJsonObject {
                    put("name", "stragent_ask_followup")
                    put("arguments", buildJsonObject { put("datasetId", dsId); put("question", "营收表现如何") })
                },
            )["structuredContent"]!!.jsonObject
            val answer = r.getValue("answer").jsonPrimitive.content
            assertTrue(answer.contains("未检索到"), "0 命中（含改写重试）→ 如实拒答: " + answer.take(60))
            assertTrue(answer.contains("改写重试"), "拒答回执注明 corrective 已尝试")
            assertTrue(r.getValue("totalTokens").jsonPrimitive.content.toInt() > 0, "改写调用 token 如实入账")
        } finally {
            b.close()
        }
    }

    @Test
    fun `嵌入失败——回落 BM25 单路照常工作（诚实降级）`() {
        val llm = RoutingLlm()
        val failing = object : EmbeddingClient {
            override fun embed(texts: List<String>): List<DoubleArray> =
                throw RuntimeException("嵌入端点 503")
        }
        val b = StrAgentHostBootstrap.create(tempHome(), llm = llm, embedding = failing)
        try {
            val dsId = importMaterial(b)
            // 词法可直接命中的问法：回落 BM25 后照常检索
            val r = b.mcpBridge.dispatch(
                "tools/call",
                buildJsonObject {
                    put("name", "stragent_ask_followup")
                    put("arguments", buildJsonObject { put("datasetId", dsId); put("question", "营业收入多少") })
                },
            )["structuredContent"]!!.jsonObject
            assertTrue(r.getValue("answer").jsonPrimitive.content.contains("128"), "回落 BM25 命中: $r")
            assertTrue(!r.getValue("gateFailed").jsonPrimitive.content.toBooleanStrict())
        } finally {
            b.close()
        }
    }
}