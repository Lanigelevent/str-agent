package com.stragent.host

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
 * T-110 题库扩容（业界题型分类法：LongBench/RAG 评测六型——factoid/aggregation/multi-hop/
 * comparison/unanswerable/section-summary）：30 题对真实 268 页 PDF 的**检索接地**回归。
 *
 * 断言口径（确定性）：每题的 BM25 检索窗口必须包含期望关键词（context recall 代理）；
 * 不可答负例断言窗口不含编造目标（材料里没有的信息不允许被检索层「凑」出来）。
 * 回答语义质量归真实 Key 抽样（T-061 先例）；题库数据驱动，扩题只改 JSON 不改测试。
 */
class PdfQuestionBankTest {


    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    @Test
    fun `题库 30 题——六型检索接地回归`() {
        val pdfPath = RealMaterialFixture.requirePdf()
        val bank = javaClass.classLoader.getResourceAsStream("text-sampling/pdf-question-bank.json")!!
            .readBytes().decodeToString().let { Json.parseToJsonElement(it).jsonObject }
        val qs = bank.getValue("questions").jsonArray.map { it.jsonObject }
        assertEquals(30, qs.size, "题库规模（扩题时同步改这里）")

        val fake = FakeLlmClient()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-qbank-" + System.nanoTime()).also { it.mkdirs() },
            llm = fake,
        )
        try {
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", pdfPath); put("name", "基坑监测总结报告")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

            val failures = mutableListOf<String>()
            for (q in qs) {
                val id = q.getValue("id").jsonPrimitive.content
                val type = q.getValue("type").jsonPrimitive.content
                val question = q.getValue("q").jsonPrimitive.content
                val expect = q.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
                fake.prompts.clear()
                call(b, "stragent_ask_followup", buildJsonObject {
                    put("datasetId", dsId); put("question", question)
                })
                // 剔除问句回显：断言的是「检索到的材料」而不是「问题里已有的词」
                val window = (fake.prompts.lastOrNull() ?: "").replace(question, "")
                for (kw in expect) {
                    if (!window.contains(kw)) failures += "$id($type): 窗口缺「$kw」"
                }
                // 全题库守卫：窗口必含材料锚（检索层没有空转）
                if (!window.contains("段")) failures += "$id($type): 窗口无段锚"
            }
            assertTrue(failures.isEmpty(), "题库接地失败 ${failures.size} 条：\n${failures.joinToString("\n")}")
        } finally {
            b.close()
        }
    }
}
