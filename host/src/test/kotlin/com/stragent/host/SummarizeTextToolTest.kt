package com.stragent.host

import com.stragent.core.ports.ProgressBus
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * stragent_summarize_text 集成测试（T-096 步骤 5）：MCP 全链（导入→总结）+ 回执恒输出字段
 * + 模型流向（白名单/quote-first）+ kv 续传 + 进度帧 + 纯表格拒绝 + 分析工具中文提示。
 * 模型用按 system 标记路由的 RoutingLlm 假模型——不感知调用顺序，步数可断言。
 */
class SummarizeTextToolTest {

    private fun tempHome() =
        File(System.getProperty("java.io.tmpdir"), "str-agent-st-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap(llm: LlmClient) =
        StrAgentHostBootstrap.create(tempHome(), llm = llm)

    private class RoutingLlm(private val route: (List<Pair<String, String>>) -> String) : LlmClient {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<Pair<String, String>>>())
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            return ChatResult(route(messages), 123)
        }
    }

    private fun systemOf(call: List<Pair<String, String>>): String = call.first { it.first == "system" }.second
    private fun userOf(call: List<Pair<String, String>>): String = call.last { it.first == "user" }.second

    private fun jsonPoints(vararg items: Triple<String, String, Int>, keyNumbers: List<String> = emptyList(), summary: String = "本块概述"): String {
        val points = items.joinToString(",") { (t, q, s) -> """{"text":"$t","quote":"$q","segment":$s}""" }
        val nums = keyNumbers.joinToString(",") { "\"$it\"" }
        return """{"points":[$points],"keyNumbers":[$nums],"summary":"$summary"}"""
    }

    private fun summaryLlm(): RoutingLlm = RoutingLlm { messages ->
        when (systemOf(messages).contains("文本分析员")) {
            true -> jsonPoints(
                Triple("销售额增长 128", "销售额增长 128", 1),
                keyNumbers = listOf("128"),
                summary = "材料围绕销售额增长",
            )
            else -> "终稿：销售额增长 128（段 1）"
        }
    }

    private fun listTools(b: StrAgentHostBootstrap): JsonArray =
        b.mcpBridge.dispatch("tools/list", JsonObject(emptyMap()))["tools"]!!.jsonArray

    private fun call(b: StrAgentHostBootstrap, name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        )

    private fun structured(result: JsonObject): JsonObject? = result["structuredContent"]?.jsonObject
    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun importText(b: StrAgentHostBootstrap, fileName: String, content: String, name: String? = null): String {
        val f = File(b.home, fileName)
        f.writeText(content, Charsets.UTF_8)
        val r = call(
            b, "stragent_import_text",
            buildJsonObject {
                put("filePath", f.absolutePath)
                if (name != null) put("name", name)
            },
        )
        return structured(r)!!["datasetId"]!!.jsonPrimitive.content
    }

    private fun buildMixedDocxBytes(): ByteArray {
        val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""
        fun p(t: String) = "<w:p><w:r><w:t>$t</w:t></w:r></w:p>"
        fun table(rows: List<List<String>>): String =
            "<w:tbl>" + rows.joinToString("") { row ->
                "<w:tr>" + row.joinToString("") { cell ->
                    "<w:tc><w:p><w:r><w:t>$cell</w:t></w:r></w:p></w:tc>"
                } + "</w:tr>"
            } + "</w:tbl>"
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document $ns>
              <w:body>
                ${p("开头段落：销售额增长 128，成本稳中有降。")}
                ${table(listOf(listOf("门店", "销售额"), listOf("华东", "1200"), listOf("华北", "800")))}
                ${p("结尾段落：风险是成本波动。")}
              </w:body>
            </w:document>"""
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(doc.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    // ---------- 端到端 ----------

    @Test
    fun `MCP 全链——import_text 后 summarize_text 回执字段齐全且为 stuff 路由`() {
        val llm = summaryLlm()
        val b = bootstrap(llm)
        try {
            val id = importText(b, "销售报告.txt", "销售额增长 128，利润率 45.6%。\n\n第二段：成本结构稳定。\n\n第三段：风险可控。")
            val r = call(
                b, "stragent_summarize_text",
                buildJsonObject {
                    put("datasetId", id)
                    put("maxWords", 300) // <400，跳过 CoD——终稿即 stuff 摘要
                },
            )
            val s = structured(r)!!
            assertEquals(id, s["datasetId"]!!.jsonPrimitive.content)
            assertEquals("text", s["kind"]!!.jsonPrimitive.content)
            assertEquals("stuff", s["route"]!!.jsonPrimitive.content)
            assertEquals(1, s["chunkCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("材料围绕销售额增长", s["summary"]!!.jsonPrimitive.content)
            // 恒输出：list 字段空也输出
            assertEquals(0, s["failedChunks"]!!.jsonArray.size)
            assertTrue(s["points"]!!.jsonArray.isNotEmpty())
            assertEquals("128", s["numbersWithAnchors"]!!.jsonArray.first().jsonObject["number"]!!.jsonPrimitive.content)
            assertEquals("段 1-3", s["numbersWithAnchors"]!!.jsonArray.first().jsonObject["anchor"]!!.jsonPrimitive.content)
            assertTrue(s["durationMs"]!!.jsonPrimitive.content.toLong() >= 0)
            assertTrue(s["totalTokens"]!!.jsonPrimitive.content.toInt() > 0)
            assertEquals(3, s["paragraphs"]!!.jsonPrimitive.content.toInt())
            assertEquals(300, s["maxWords"]!!.jsonPrimitive.content.toInt())
            // map 提示词带 quote-first 纪律
            val mapCall = llm.calls.first { systemOf(it).contains("文本分析员") }
            assertTrue(systemOf(mapCall).contains("quote"))
        } finally {
            b.close()
        }
    }

    @Test
    fun `纯表格数据集被拒绝且回执带中文错误`() {
        val b = bootstrap(summaryLlm())
        try {
            val importResult = call(
                b, "stragent_import_table",
                buildJsonObject {
                    put("csv", "地区,销售额\n华东,1200\n华北,800")
                    put("name", "纯表")
                },
            )
            val r = call(
                b, "stragent_summarize_text",
                buildJsonObject {
                    put("datasetId", structured(importResult)!!["datasetId"]!!.jsonPrimitive.content)
                },
            )
            assertTrue(textOf(r).contains("文本部分") || textOf(r).contains("纯表格"), "中文错误：${textOf(r)}")
        } finally {
            b.close()
        }
    }

    @Test
    fun `混合数据集只总结正文——kind=mixed`() {
        val llm = summaryLlm()
        val b = bootstrap(llm)
        try {
            val f = File(b.home, "混合.docx")
            f.writeBytes(buildMixedDocxBytes())
            val importResult = call(
                b, "stragent_import_text",
                buildJsonObject { put("filePath", f.absolutePath) },
            )
            val r = call(
                b, "stragent_summarize_text",
                buildJsonObject {
                    put("datasetId", structured(importResult)!!["datasetId"]!!.jsonPrimitive.content)
                    put("maxWords", 300) // <400，跳过 CoD
                },
            )
            val s = structured(r)!!
            assertEquals("mixed", s["kind"]!!.jsonPrimitive.content)
            assertTrue(s["totalTokens"]!!.jsonPrimitive.content.toInt() > 0)
            assertEquals("材料围绕销售额增长", s["summary"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun `显式 datasetId 优先于会话指针；缺省用会话指针（default 回退最近导入）`() {
        val b = bootstrap(summaryLlm())
        try {
            val idA = importText(b, "甲.txt", "甲材料：销售额增长 128。\n\n甲第二段。")
            val idB = importText(b, "乙.txt", "乙材料：成本稳中有降。\n\n乙第二段。")
            // 显式 A：用 A，不被最近导入的 B 盖掉
            val ra = call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", idA); put("maxWords", 300) })
            assertEquals(idA, structured(ra)!!["datasetId"]!!.jsonPrimitive.content)
            // 缺省：default 会话回退全局最近导入（= B）
            val rd = call(b, "stragent_summarize_text", buildJsonObject { put("maxWords", 300) })
            assertEquals(idB, structured(rd)!!["datasetId"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun `focus 非空——要点按 BM25 相关度重排`() {
        val llm = RoutingLlm { messages ->
            if (systemOf(messages).contains("文本分析员")) {
                jsonPoints(
                    Triple("天气晴朗适合出行", "天气晴朗", 1),
                    Triple("营收 营收 营收 增长 128", "营收 营收", 2),
                    Triple("营收 增长", "营收 增长", 3),
                    keyNumbers = listOf("128"),
                    summary = "围绕营收",
                )
            } else {
                "终稿"
            }
        }
        val b = bootstrap(llm)
        try {
            val id = importText(b, "视角.txt", "短段落。\n\n第二段。")
            val r = call(
                b, "stragent_summarize_text",
                buildJsonObject {
                    put("datasetId", id)
                    put("focus", "营收")
                    put("maxWords", 300)
                },
            )
            val s = structured(r)!!
            assertEquals("营收", s["focus"]!!.jsonPrimitive.content, "focus 字段应回显")
            val points = s["points"]!!.jsonArray
            assertTrue(points.first().jsonObject["text"]!!.jsonPrimitive.content.startsWith("营收"), "最相关置首：${points.first()}")
        } finally {
            b.close()
        }
    }

    @Test
    fun `maxWords 触发 CoD——800 字两轮密度优化，100 字不加（Kv 续传不重复计费）`() {
        val llm = summaryLlm()
        val b = bootstrap(llm)
        try {
            val idA = importText(b, "密度甲.txt", "短段落。\n\n第二段。\n\n第三段。")
            call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", idA); put("maxWords", 100) })
            assertEquals(1, llm.calls.size, "stuff + maxWords<400：只有 1 次模型调用")
            // 另一份材料（缓存键不同）：stuff + maxWords>=400 → map + CoD×2 = 3 次
            val idB = importText(b, "密度乙.txt", "另一段。\n\n乙第二段。\n\n乙第三段。")
            call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", idB); put("maxWords", 800) })
            assertEquals(4, llm.calls.size, "新材料 map + CoD×2 = 3 次（共 4 次）")
        } finally {
            b.close()
        }
    }

    @Test
    fun `maxWords 越界钳制到 2000 且回执如实`() {
        val b = bootstrap(summaryLlm())
        try {
            val id = importText(b, "钳制.txt", "短段落。\n\n第二段。")
            val r = call(
                b, "stragent_summarize_text",
                buildJsonObject { put("datasetId", id); put("maxWords", 99999) },
            )
            assertEquals(2000, structured(r)!!["maxWords"]!!.jsonPrimitive.content.toInt())
        } finally {
            b.close()
        }
    }

    @Test
    fun `kv 续传——同材料第二次调用命中块缓存不再发 map 调用`() {
        val recording = summaryLlm()
        val b = bootstrap(recording)
        try {
            val id = importText(b, "续传.txt", "销售额增长 128。\n\n第二段成本。\n\n第三段利润。")
            val first = call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", id); put("maxWords", 300) })
            assertEquals(0, structured(first)!!["failedChunks"]!!.jsonArray.size)
            assertTrue(b.kv.keys().any { it.startsWith("textpoints.") }, "块要点应写入 kv")

            // 第二次：map 全部返回垃圾——缓存命中则不产生 map 调用、不整体失败
            val poison = RoutingLlm { messages ->
                when {
                    systemOf(messages).contains("文本分析员") -> "###坏"
                    systemOf(messages).contains("总结撰写人") -> "终稿：缓存命中"
                    else -> "终稿：缓存命中"
                }
            }
            val tool = summarizeTextTool(poison, b.abilityDatasets, b.sessionRegistry, b.kv, "test-model")
            val r = tool.invoke(
                buildJsonObject {
                    put("datasetId", id)
                    put("maxWords", 300)
                },
            )
            assertEquals(0, r["failedChunks"]!!.jsonArray.size, "块缓存命中：不该有失败块")
            assertEquals(0, poison.calls.size, "stuff 路由的要点与 summary 全部命中缓存：不发模型调用")
            // stuff 的摘要直接从块缓存恢复（map 不重付费，CoD 无调用——与 T-050 签名续传同纪律）
            assertEquals("材料围绕销售额增长", r["summary"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun `进度帧——工具直接调用时按块推进`() {
        val b = bootstrap(summaryLlm())
        try {
            val ds = com.stragent.core.dataset.Dataset(
                name = "进度.txt", importedAt = 1,
                columns = emptyList(), rows = emptyList(), columnTypes = emptyList(),
                text = com.stragent.core.dataset.TextContent(
                    listOf(com.stragent.core.dataset.TextSegment(1, "销售额增长 128，成本稳中有降。")),
                ),
            )
            b.abilityDatasets.put("ds_progress_test", ds)
            val tool = summarizeTextTool(summaryLlm(), b.abilityDatasets, b.sessionRegistry, null, "test-model")
            val stages = mutableListOf<String>()
            ProgressBus.with({ message -> stages += message }) {
                tool.invoke(buildJsonObject { put("datasetId", "ds_progress_test"); put("maxWords", 300) })
            }
            assertTrue(stages.any { it.startsWith("分段要点") }, "进度应含分段阶段：$stages")
        } finally {
            b.close()
        }
    }

    @Test
    fun `分析工具对纯文本数据集给中文提示`() {
        val b = bootstrap(summaryLlm())
        try {
            importText(b, "纯文本.txt", "正文材料一段。\n\n第二段。")
            val r = call(b, "stragent_session_overview", buildJsonObject {})
            val text = textOf(r)
            assertTrue(text.contains("文本型数据集"), "应提示文本型数据集：$text")
            assertTrue(text.contains("summarize_text"), "应指引总结工具：$text")
        } finally {
            b.close()
        }
    }

    @Test
    fun `工具面含 stragent_summarize_text 且 annotations 注明非只读非封闭`() {
        val b = bootstrap(summaryLlm())
        try {
            val tools = listTools(b)
            val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("stragent_summarize_text" in names)
            val tool = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "stragent_summarize_text" }.jsonObject
            val annotations = tool["annotations"]!!.jsonObject
            assertEquals("false", annotations["readOnlyHint"]!!.jsonPrimitive.content)
            assertEquals("true", annotations["openWorldHint"]!!.jsonPrimitive.content)
        } finally {
            b.close()
        }
    }

    @Test
    fun `未知 datasetId 报中文错误`() {
        val b = bootstrap(summaryLlm())
        try {
            val r = call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", "ds_not_exist") })
            assertTrue(textOf(r).contains("数据集不存在"), "中文错误：${textOf(r)}")
        } finally {
            b.close()
        }
    }

    // ---------- T-098：要点分级回传（verbosity）与 nextStep ----------

    @Test
    fun `maxPoints 截断语义——3 条上限对 4 个要点触发 pointsTruncated 与取全路径`() {
        val b = bootstrap(
            RoutingLlm { messages ->
                when (systemOf(messages).contains("文本分析员")) {
                    true -> jsonPoints(
                        Triple("要点甲 128", "要点甲", 1), Triple("要点乙 129", "要点乙", 1),
                        Triple("要点丙 130", "要点丙", 1), Triple("要点丁 131", "要点丁", 1),
                        keyNumbers = listOf("128"), summary = "概述",
                    )
                    else -> "终稿：要点甲乙丙丁"
                }
            },
        )
        try {
            val dsId = importText(b, "many.txt", "第一段：内容一。NN第二段：内容二。".replace("NN", System.lineSeparator().plus(System.lineSeparator())))
            val r = structured(
                call(
                    b, "stragent_summarize_text",
                    buildJsonObject { put("datasetId", dsId); put("maxPoints", 3) },
                ),
            )!!
            assertEquals(3, r.getValue("points").jsonArray.size, "页大小=3")
            assertEquals(4, r.getValue("pointsTotal").jsonPrimitive.content.toInt(), "总数如实")
            assertTrue(r.getValue("pointsTruncated").jsonPrimitive.content.toBoolean(), "应标记截断")
            val next = r.getValue("nextStep").jsonPrimitive.content
            assertTrue(next.contains("maxPoints=9999"), "截断必须带取全路径: DOLLAR-next".replace("DOLLAR-next", next))
            assertTrue(next.contains("stragent_read_segments"), "核对路径指路")
        } finally {
            b.close()
        }
    }

    @Test
    fun `maxPoints=9999 取全量要点且 pointsTruncated=false`() {
        val b = bootstrap(
            RoutingLlm { messages ->
                when (systemOf(messages).contains("文本分析员")) {
                    true -> jsonPoints(Triple("要点甲 128", "要点甲", 1), Triple("要点乙", "要点乙", 1), summary = "概述")
                    else -> "终稿"
                }
            },
        )
        try {
            val dsId = importText(b, "full.txt", (1..30).joinToString("\n\n") { "第${it}段：内容 $it。" })
            val r = structured(
                call(
                    b, "stragent_summarize_text",
                    buildJsonObject { put("datasetId", dsId); put("maxPoints", 9999) },
                ),
            )!!
            assertTrue(!r.getValue("pointsTruncated").jsonPrimitive.content.toBoolean(), "不应标记截断")
            assertTrue(r.getValue("points").jsonArray.size >= r.getValue("pointsTotal").jsonPrimitive.content.toInt().coerceAtMost(60))
            assertTrue(!r.getValue("nextStep").jsonPrimitive.content.contains("maxPoints"), "未截断不给取全路径")
        } finally {
            b.close()
        }
    }

    @Test
    fun `混合数据集 nextStep 指路表格工具链`() {
        val b = bootstrap(summaryLlm())
        try {
            // 用 docx 混合材料（正文+表格）
            val f = File(b.home, "mixed.docx")
            f.writeBytes(buildMixedDocxBytes())
            val dsId = structured(
                call(b, "stragent_import_text", buildJsonObject { put("filePath", f.absolutePath) }),
            )!!["datasetId"]!!.jsonPrimitive.content
            val r = structured(call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", dsId) }))!!
            val next = r.getValue("nextStep").jsonPrimitive.content
            assertTrue(next.contains("stragent_session_overview"), "混合数据集指路表格工具: $next")
            assertTrue(next.contains("stragent_read_segments"), "核对路径恒在")
        } finally {
            b.close()
        }
    }

    @Test
    fun `summary 位于回执首位（字段序即注意力序）`() {
        val b = bootstrap(summaryLlm())
        try {
            val dsId = importText(b, "order.txt", "只有一段：销售额 128。")
            val r = structured(call(b, "stragent_summarize_text", buildJsonObject { put("datasetId", dsId) }))!!
            val firstField = r.keys.first()
            val summaryIdx = r.keys.toList().indexOf("summary")
            assertTrue(summaryIdx <= 3, "summary 应在前排（实际第 ${summaryIdx + 1} 个字段）")
            assertTrue(firstField in setOf("datasetId", "name", "summary"), "首个字段是身份/正文")
        } finally {
            b.close()
        }
    }

}