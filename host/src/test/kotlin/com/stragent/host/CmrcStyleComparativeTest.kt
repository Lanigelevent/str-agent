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
 * T-110b 对比测试：CMRC 2018 抽取式题型规范的领域对照集（20 题，金标=原文连续片段）
 * vs 自建六型题库（30 题）——同一检索接地口径下的跨题型对比。
 *
 * 出处与诚实边界：CMRC 2018（ymcui/cmrc2018，EMNLP 2019）官方全量因沙箱网络
 * 不可达（github/hf/modelscope 连接重置）阻塞；本集按其题型规范构造，非官方数据。
 * 对比维度：抽取式金标片段的窗口命中率（context recall 代理）。
 */
class CmrcStyleComparativeTest {


    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    @Test
    fun `CMRC 样式抽取题对比——金标片段窗口命中率`() {
        val pdfPath = RealMaterialFixture.requirePdf()
        val bank = javaClass.classLoader.getResourceAsStream("text-sampling/pdf-cmrc-style-bank.json")!!
            .readBytes().decodeToString().let { Json.parseToJsonElement(it).jsonObject }
        val qs = bank.getValue("questions").jsonArray.map { it.jsonObject }
        assertEquals(20, qs.size, "对照集规模")

        val fake = FakeLlmClient()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-cmrc-" + System.nanoTime()).also { it.mkdirs() },
            llm = fake,
        )
        try {
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", pdfPath); put("name", "基坑监测总结报告")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

            val hits = mutableListOf<String>()
            val misses = mutableListOf<String>()
            for (q in qs) {
                val question = q.getValue("q").jsonPrimitive.content
                val gold = q.getValue("gold").jsonPrimitive.content
                fake.prompts.clear()
                call(b, "stragent_ask_followup", buildJsonObject {
                    put("datasetId", dsId); put("question", question)
                })
                val window = (fake.prompts.lastOrNull() ?: "").replace(question, "")
                if (window.contains(gold)) hits.add(question) else misses.add("$question ⟶ 金标「$gold」未入窗")
            }
            val rate = hits.size.toDouble() / qs.size
            println("[cmrc-compare] 抽取式金标命中率: ${hits.size}/${qs.size} = %.2f".format(rate))
            // 对比锚点：六型题库 30/30（关键词口径）；抽取式=金标全片段逐字（更严：长句易被窗口截断）
            // 首轮实测 0.85 → 回归线钉 0.8（低于 = 检索层回归；逐题清单随失败输出）
            assertTrue(rate >= 0.8, "抽取式命中率 %.2f 低于 0.8 回归线：\n${misses.joinToString("\n")}".format(rate))
        } finally {
            b.close()
        }
    }
}
