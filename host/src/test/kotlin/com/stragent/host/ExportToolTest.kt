package com.stragent.host

import com.stragent.data.parseXlsx
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 报告导出工具测试（D11 v3 S3；T-054 起 Excel 是真 XLSX、HTML 去 CDN）。
 *
 * 验证维度：
 *  1. HTML 仪表盘：单文件离线、内联 SVG、无 CDN/JS；
 *  2. HTML 降级：静态渲染不了的 option 明示未渲染并附原始配置；
 *  3. Excel：真 .xlsx（ZIP/OOXML）、可被自家 XlsxParser 读回、多工作表；
 *  4. 工具 outputSchema 与 format enum 契约。
 */
class ExportToolTest {

    private fun section(title: String, content: String, charts: JsonArray? = null) = buildJsonObject {
        put("title", title)
        put("content", content)
        if (charts != null) put("chartOptions", charts)
    }

    private fun barChartOption(): JsonArray = JsonArray(listOf(
        buildJsonObject {
            put("title", buildJsonObject { put("text", "区域销售") })
            put("xAxis", buildJsonObject {
                put("type", "category")
                put("data", JsonArray(listOf(JsonPrimitive("华东"), JsonPrimitive("华北"))))
            })
            put("series", JsonArray(listOf(
                buildJsonObject {
                    put("type", "bar")
                    put("name", "销售额")
                    put("data", JsonArray(listOf(JsonPrimitive("1000"), JsonPrimitive("300"))))
                },
            )))
        },
    ))

    @Test
    fun `HTML 导出离线可读——含内联 SVG、无 CDN 与脚本`() {
        val tool = exportReportTool()
        val args = buildJsonObject {
            put("format", "html")
            put("title", "销售分析报告")
            put("sections", JsonArray(listOf(
                section(
                    "区域销售",
                    "华东地区销售额最高。\n\n|地区|销售额|\n|---|---|\n|华东|1000|",
                    barChartOption(),
                ),
            )))
        }
        val result = tool.invoke(args)
        assertEquals("html", result["format"]!!.jsonPrimitive.content)
        val html = result["html"]!!.jsonPrimitive.content
        assertTrue(html.contains("<!DOCTYPE html>"), "HTML 应以 DOCTYPE 开头")
        assertTrue(html.contains("<svg"), "图表应渲染为内联 SVG")
        assertTrue(html.contains("华东"), "图表数据应出现在 SVG 中")
        assertTrue(html.contains("销售分析报告"), "应含报告标题")
        assertTrue(html.contains("区域销售"), "应含章节标题")
        assertTrue(html.contains("<table>"), "Markdown 表格应转 HTML")
        assertFalse(html.contains("echarts"), "不应再出现 echarts（库或 setOption 调用）")
        // T-168：交互层内嵌自有脚本——离线纪律从「无脚本」升级为「无外部引用」
        assertTrue(html.contains("chart-tip"), "交互 tooltip 应就位")
        assertFalse(html.contains("src=\"http"), "不得引用外部资源")
        assertFalse(html.contains("href=\"http"), "不得引用外部资源")
        assertFalse(html.contains("cdn."), "不应引用 CDN")
    }

    @Test
    fun `HTML 对不支持静态渲染的图表诚实降级——附原始 option`() {
        val tool = exportReportTool()
        val heatmap = JsonArray(listOf(
            buildJsonObject {
                put("series", JsonArray(listOf(buildJsonObject {
                    put("type", "heatmap")
                    put("data", JsonArray(listOf(JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0), JsonPrimitive(5))))))
                })))
            },
        ))
        val args = buildJsonObject {
            put("format", "html")
            put("title", "热力图测试")
            put("sections", JsonArray(listOf(section("热力区", "以下为热力图", heatmap))))
        }
        val html = tool.invoke(args)["html"]!!.jsonPrimitive.content
        assertTrue(html.contains("暂不支持离线静态渲染"), "应说明未渲染：$html")
        assertTrue(html.contains("heatmap"), "应附原始 option")
        assertFalse(html.contains("<svg"), "不支持的图表不应伪造 SVG")
        assertFalse(html.contains("src=\"http"), "不得引用外部资源")
        assertFalse(html.contains("href=\"http"), "不得引用外部资源")
    }

    @Test
    fun `Excel 导出是真 XLSX——OOXML 条目齐备且可被自家解析器读回`() {
        val tool = exportReportTool()
        val args = buildJsonObject {
            put("format", "excel")
            put("title", "销售数据")
            put("sections", JsonArray(listOf(
                section("区域销售", "|地区|销售额|\n|---|---|\n|华东|1000|\n|华北|300|"),
            )))
        }
        val result = tool.invoke(args)
        assertEquals("excel", result["format"]!!.jsonPrimitive.content)
        val filePath = result["filePath"]!!.jsonPrimitive.content
        assertTrue(filePath.endsWith(".xlsx"), "文件应以 .xlsx 结尾：$filePath")
        val file = File(filePath)
        assertTrue(file.exists(), "文件应存在")
        assertTrue(file.length() > 0, "文件不应为空")

        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue("[Content_Types].xml" in names)
            assertTrue("_rels/.rels" in names)
            assertTrue("xl/workbook.xml" in names)
            assertTrue("xl/styles.xml" in names)
            assertTrue(names.any { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) })
            // 每个 XML 条目都必须是良构 XML（拼错一个标签 Excel 就打不开）
            val factory = DocumentBuilderFactory.newInstance()
            for (name in names.filter { it.endsWith(".xml") || it.endsWith(".rels") }) {
                val stream = zip.getInputStream(zip.getEntry(name))
                factory.newDocumentBuilder().parse(stream)
            }
        }

        // 首表是「报告概要」；数据表按章节名取
        val sheet = parseXlsx(file.inputStream()).first { it.name == "区域销售" }.table
        assertEquals(listOf("地区", "销售额"), sheet.columns)
        assertEquals(listOf(listOf("华东", "1000"), listOf("华北", "300")), sheet.rows)
    }

    @Test
    fun `Excel 多章节——概要页 + 每章一张工作表，纯文本章节也保留`() {
        val tool = exportReportTool()
        val args = buildJsonObject {
            put("format", "excel")
            put("title", "季度复盘")
            put("sections", JsonArray(listOf(
                section("区域销售", "|地区|销售额|\n|---|---|\n|华东|1000|"),
                section("毛利分析", "|品类|毛利|\n|---|---|\n|电器|200|"),
                section("结论", "整体毛利偏低。\n建议优化品类结构。"),
            )))
        }
        val file = File(tool.invoke(args)["filePath"]!!.jsonPrimitive.content)
        val sheets = parseXlsx(file.inputStream())
        assertEquals(listOf("报告概要", "区域销售", "毛利分析", "结论"), sheets.map { it.name })
        val conclusion = sheets.last().table
        // 纯文本页逐行成行；用表格解析器读回时首行落在表头位——值必须原样保留
        assertEquals(listOf("整体毛利偏低。"), conclusion.columns)
        assertEquals(listOf(listOf("建议优化品类结构。")), conclusion.rows)
        // 概要页列出每章列数/行数
        val overview = sheets.first().table
        assertTrue(overview.rows.any { it.getOrNull(0) == "区域销售" && it.getOrNull(2) == "1" }, "概要应含区域销售 1 行：${overview.rows}")
    }

    @Test
    fun `工作表名去重并截断到 31 字符——同名章节不覆盖`() {
        val tool = exportReportTool()
        val longTitle = "销售明细与区域对比分析报告附表一二三四五六七八九十补充材料与图表索引说明第二部分"
        val args = buildJsonObject {
            put("format", "excel")
            put("title", "命名测试")
            put("sections", JsonArray(listOf(
                section("销售/明细", "|a|\n|---|\n|1|"),
                section("销售:明细", "|a|\n|---|\n|2|"),
                section(longTitle, "|a|\n|---|\n|3|"),
            )))
        }
        val file = File(tool.invoke(args)["filePath"]!!.jsonPrimitive.content)
        val names = parseXlsx(file.inputStream()).map { it.name }
        assertEquals(4, names.size, "概要 + 3 章：$names")
        assertEquals("销售_明细", names[1])
        assertEquals("销售_明细(2)", names[2], "同名（忽略大小写）应编号去重：$names")
        assertTrue(names[3].length <= 31, "工作表名不能超过 31 字符：${names[3]}")
    }

    @Test
    fun `同一输入产出同一份字节——导出确定性`() {
        val request = XlsxExporter.ExportRequest(
            title = "确定性",
            sections = listOf(XlsxExporter.ExportSection("表", "|a|b|\n|---|---|\n|1|2|")),
        )
        val first = XlsxExporter.exportToBytes(request)
        val second = XlsxExporter.exportToBytes(request)
        assertTrue(first.contentEquals(second), "两次导出应逐字节一致")
    }

    @Test
    fun `exportReportTool 有 outputSchema 契约`() {
        val tool = exportReportTool()
        assertNotNull(tool.outputSchema, "应有 outputSchema")
        val props = tool.outputSchema!!["properties"]!!.jsonObject
        assertTrue(props.containsKey("filePath"), "outputSchema 应含 filePath")
        assertTrue(props.containsKey("html"), "outputSchema 应含 html")
    }

    @Test
    fun `format 参数有 enum 约束`() {
        val tool = exportReportTool()
        val formatParam = tool.parametersSchema["properties"]!!.jsonObject["format"]!!.jsonObject
        assertNotNull(formatParam["enum"], "format 应有 enum")
        val enums = formatParam["enum"]!!.jsonArray
        assertEquals(6, enums.size, "format enum 应有 6 个值（excel/docx/pdf/pptx/landing/html）")
        val values = enums.map { it.jsonPrimitive.content }
        listOf("excel", "docx", "pdf", "pptx", "landing", "html").forEach { expected ->
            assertTrue(values.contains(expected), "format enum 应含 $expected：$values")
        }
    }

    @Test
    fun `不支持的格式报错`() {
        val tool = exportReportTool()
        val args = buildJsonObject {
            put("format", "word")
            put("title", "测试")
        }
        try {
            tool.invoke(args)
            throw AssertionError("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不支持"), "应报不支持: ${e.message}")
        }
    }
}
