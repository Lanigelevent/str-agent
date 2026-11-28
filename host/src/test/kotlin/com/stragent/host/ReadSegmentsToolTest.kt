package com.stragent.host

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
 * stragent_read_segments 集成测试（T-098）：段号游标区间读取、页预算截断+nextFrom 续读、
 * 混合数据集（含投影段）、纯表格负控、越界钳制。
 */
class ReadSegmentsToolTest {

    private fun tempHome() =
        File(System.getProperty("java.io.tmpdir"), "str-agent-rs-" + System.nanoTime()).also { it.mkdirs() }

    private fun bootstrap() = StrAgentHostBootstrap.create(
        tempHome(),
        llm = object : com.stragent.core.ports.LlmClient {
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int) =
                com.stragent.core.ports.ChatResult("（不该被调用）", 1)
        },
    )

    private fun call(b: StrAgentHostBootstrap, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_read_segments")
                put("arguments", args)
            },
        )

    private fun structured(result: kotlinx.serialization.json.JsonObject) =
        result["structuredContent"]!!.jsonObject

    private fun importText(b: StrAgentHostBootstrap, name: String, content: String): String {
        val f = File(b.home, name)
        f.writeText(content, Charsets.UTF_8)
        val r = b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_import_text")
                put("arguments", buildJsonObject { put("filePath", f.absolutePath) })
            },
        )
        return r["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content
    }

    private fun mixedDocx(b: StrAgentHostBootstrap): String {
        val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""
        fun p(t: String) = "<w:p><w:r><w:t>$t</w:t></w:r></w:p>"
        val doc = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document $ns><w:body>
                ${p("正文段落甲。")}
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>门店</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>额</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>华东</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>1200</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:tbl><w:tr><w:tc><w:p><w:r><w:t>区域</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>华南</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                ${p("正文段落乙。")}
            </w:body></w:document>"""
        val f = File(b.home, "mixed-rs.docx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write(doc.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", "stragent_import_text")
                put("arguments", buildJsonObject { put("filePath", f.absolutePath) })
            },
        )["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content
    }

    @Test
    fun `区间读取——from 到 to 段号即游标，nextFrom 供续读`() {
        val b = bootstrap()
        try {
            val dsId = importText(b, "seg.txt", (1..25).joinToString("\n\n") { "第${it}段：内容 $it。" })
            val r = structured(
                call(b, buildJsonObject { put("datasetId", dsId); put("from", 1); put("to", 10) }),
            )
            assertEquals(25, r.getValue("totalSegments").jsonPrimitive.content.toInt())
            assertEquals(10, r.getValue("delivered").jsonPrimitive.content.toInt())
            val first = r.getValue("segments").jsonArray.first().jsonObject
            assertEquals(1, first.getValue("segment").jsonPrimitive.content.toInt())
            assertTrue(first.getValue("text").jsonPrimitive.content.contains("内容 1"))
            assertEquals(11, r.getValue("nextFrom").jsonPrimitive.content.toInt(), "续读游标")
            assertTrue(r.getValue("notice").jsonPrimitive.content.contains("from=11"))
        } finally {
            b.close()
        }
    }

    @Test
    fun `页预算——缺省页大小 20 段且字符上限先到即断`() {
        val b = bootstrap()
        try {
            // 每段 ~600 字符 → 8000 字符预算在第 14 段左右触顶
            val dsId = importText(b, "big.txt", (1..25).joinToString("\n\n") { "第${it}段：" + "字".repeat(600) + "。" })
            val r = structured(call(b, buildJsonObject { put("datasetId", dsId) }))
            val delivered = r.getValue("delivered").jsonPrimitive.content.toInt()
            assertTrue(delivered in 11..20, "字符预算内截断的段数应小于 20：$delivered")
            assertEquals(delivered + 1, r.getValue("nextFrom").jsonPrimitive.content.toInt())
            assertTrue(r.getValue("notice").jsonPrimitive.content.contains("字符预算内截断"))
        } finally {
            b.close()
        }
    }

    @Test
    fun `混合数据集——投影段可读且 kind 标注`() {
        val b = bootstrap()
        try {
            val dsId = mixedDocx(b)
            val r = structured(call(b, buildJsonObject { put("datasetId", dsId) }))
            val segs = r.getValue("segments").jsonArray
            assertEquals("mixed", r.getValue("kind").jsonPrimitive.content)
            val kinds = segs.map { it.jsonObject.getValue("kind").jsonPrimitive.content }
            assertTrue(kinds.contains("paragraph") && kinds.contains("table_projection"), "段落与投影都可读: $kinds")
        } finally {
            b.close()
        }
    }

    @Test
    fun `越界钳制——from 超 total 收尾段，空区间如实`() {
        val b = bootstrap()
        try {
            val dsId = importText(b, "tiny.txt", "只有一段。")
            val r = structured(call(b, buildJsonObject { put("datasetId", dsId); put("from", 99) }))
            assertEquals(1, r.getValue("from").jsonPrimitive.content.toInt(), "from 钳到 total")
            assertEquals(1, r.getValue("delivered").jsonPrimitive.content.toInt())
        } finally {
            b.close()
        }
    }

    @Test
    fun `纯表格数据集报中文错误`() {
        val b = bootstrap()
        try {
            val csv = "月份,销售额 NL 1月,100 NL 2月,150".replace(" NL ", System.lineSeparator())
            val dsId = b.mcpBridge.dispatch(
                "tools/call",
                buildJsonObject {
                    put("name", "stragent_import_table")
                    put("arguments", buildJsonObject { put("csv", csv) })
                },
            )["structuredContent"]!!.jsonObject["datasetId"]!!.jsonPrimitive.content
            val r = b.mcpBridge.dispatch(
                "tools/call",
                buildJsonObject {
                    put("name", "stragent_read_segments")
                    put("arguments", buildJsonObject { put("datasetId", dsId) })
                },
            )
            assertTrue(r.toString().contains("没有文本部分"), "纯表格指向文本工具面")
        } finally {
            b.close()
        }
    }

    @Test
    fun `工具面含 read_segments 且标注只读`() {
        val b = bootstrap()
        try {
            val tools = b.mcpBridge.dispatch("tools/list", kotlinx.serialization.json.JsonObject(emptyMap()))["tools"]!!.jsonArray
            assertEquals(44, tools.size, "T-155/T-190 后默认形态工具面 44 条（沙箱未开裁受控计算 2 条 + 视觉未就绪裁 analyze_image + db 未授权裁 db_* 6 条）")
            val me = tools.first { it.jsonObject.getValue("name").jsonPrimitive.content == "stragent_read_segments" }.jsonObject
            val annotations = me.getValue("annotations").jsonObject
            assertEquals("true", annotations.getValue("readOnlyHint").jsonPrimitive.content, "只读提示")
        } finally {
            b.close()
        }
    }
}
