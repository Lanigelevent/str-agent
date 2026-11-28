package com.stragent.host

import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.RerankClient
import com.stragent.core.ports.RerankHit
import kotlinx.serialization.json.Json
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
 * P-150 精排替身真值：语义相关性分数由替身 LLM 逐一判断（冻结资源），回放实证
 * T-101b corrective 链在真实语义分下的两种行为——①有直接回答候选时过窗；②全弱相关时
 * 诚实拒答（不硬答）。嵌入向量无法手替，但精排的语义输入（相关性判断）是本波可替部分。
 */
class RerankStandinTest {

    private class FixedLlm : LlmClient {
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int) =
            ChatResult("（占位应答）", 50)
    }

    /** 确定性嵌入管道件（无语义声明）：哈希向量只为激活 hybrid 通道，语义判断全在精排分 */
    private class HashEmbedding : com.stragent.core.ports.EmbeddingClient {
        override fun embed(texts: List<String>): List<DoubleArray> = texts.map { t ->
            val v = DoubleArray(8)
            t.toByteArray(Charsets.UTF_8).forEachIndexed { i, b -> v[i % 8] += (b.toInt() % 7) / 7.0 }
            v
        }
    }

    /** 冻结语义分回放：按查询找组，把该组的语义分分布按候选序映射（BM25 首位=最相关句组） */
    private class ReplayRerank(private val groups: List<Pair<String, List<Pair<String, Double>>>>) : RerankClient {
        override fun rerank(query: String, candidates: List<String>): List<RerankHit> {
            val g = groups.firstOrNull { query.contains(it.first.take(6)) }
            val scores = g?.second?.map { it.second } ?: List(candidates.size) { 0.02 }
            return candidates.mapIndexed { i, _ -> RerankHit(i, scores.getOrElse(i) { 0.05 }) }
                .sortedByDescending { it.score }
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    private fun bootstrap(llm: LlmClient, rerank: RerankClient?): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-rr-" + System.nanoTime()).also { it.mkdirs() },
            llm = llm,
            embedding = HashEmbedding(),
            rerank = rerank,
        )

    private fun importReal(b: StrAgentHostBootstrap): String =
        call(b, "stragent_import_text", buildJsonObject {
            put(
                "filePath",
                System.getenv("AB_REAL_PDF")
                    ?: "C:/Users/<user>/Desktop/总结报告/一区二段/基坑安全监测总结报告_字处理版.pdf",
            )
            put("name", "基坑监测总结报告")
        })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

    private fun bank(): List<Pair<String, List<Pair<String, Double>>>> {
        val root = javaClass.classLoader.getResourceAsStream("text-sampling/rerank-standin-scores.json")!!
            .readBytes().decodeToString().let { Json.parseToJsonElement(it).jsonObject }
        return root.getValue("groups").jsonArray.map { g ->
            val o = g.jsonObject
            o.getValue("q").jsonPrimitive.content to o.getValue("scores").jsonArray.map { s ->
                val so = s.jsonObject
                so.getValue("c").jsonPrimitive.content to so.getValue("s").jsonPrimitive.content.toDouble()
            }
        }
    }

    @Test
    fun `替身语义分回放——直接回答候选过窗与全弱相关诚实拒答`() {
        RealMaterialFixture.requirePdf()
        val groups = bank()
        val fake = FixedLlm()
        val b = bootstrap(fake, ReplayRerank(groups))
        try {
            val dsId = importReal(b)
            // ① 直接回答组：最高分 0.95 ≥ 阈值 → 正常入窗应答（gateFailed=false）
            val r1 = call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", groups[0].first)
            })["structuredContent"]!!.jsonObject
            assertEquals("false", r1.getValue("gateFailed").jsonPrimitive.content, "直接回答组过闸")
            // ② 全弱相关组（伙食费）：最高分 0.02 < corrective 阈值 → 诚实拒答，不硬答
            val r2 = call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", groups[2].first)
            })["structuredContent"]!!.jsonObject
            val ans = r2.getValue("answer").jsonPrimitive.content
            assertTrue(
                ans.contains("相关性不足") || ans.contains("无法") || ans.contains("不能"),
                "全弱相关组诚实拒答：${ans.take(80)}",
            )
            assertTrue(!ans.contains("伙食"), "拒答不编造材料外内容")
        } finally {
            b.close()
        }
    }
}
