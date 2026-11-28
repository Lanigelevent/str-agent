package com.stragent.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-175 · 报告落地页（首页产出）测试。
 *
 * 覆盖：离线单文件（无外部引用）/ hero + 分章 + 图表 / 正文转义（防注入）/ 落款元数据 /
 * 导出工具的 landing 分支接线。
 */
class LandingPageExporterTest {

    private val sections = listOf(
        LandingPageExporter.ExportSection(
            title = "区域销售",
            content = "华东地区销售额最高。\n\n|地区|销售额|\n|---|---|\n|华东|1000|",
        ),
        LandingPageExporter.ExportSection(
            title = "结论",
            content = "整体向好了。<script>alert(1)</script>",
        ),
    )

    @Test
    fun `落地页——hero + 分章 + 图表 + 落款`() {
        val html = LandingPageExporter.export(
            LandingPageExporter.ExportRequest("季度销售分析", sections, "10 月门店数据"),
        )
        assertTrue(html.startsWith("<!DOCTYPE html>"), "单文件 HTML：${html.take(60)}")
        assertTrue(html.contains("季度销售分析"), "标题：")
        assertTrue(html.contains("10 月门店数据"), "副标题：")
        assertTrue(html.contains("2 个章节"), "事实条：")
        assertTrue(html.contains("区域销售"), "章节标题：")
        assertTrue(html.contains("<table>"), "Markdown 表格转 HTML：")
        assertTrue(html.contains("华东"), "表格单元：")
        assertTrue(html.contains("离线单文件落地页"), "落款：")
    }

    @Test
    fun `落地页——正文先转义（脚本不会执行）且无外部引用`() {
        val html = LandingPageExporter.export(LandingPageExporter.ExportRequest("t", sections))
        assertTrue(!html.contains("<script>alert(1)</script>"), "正文里的脚本必须被转义")
        assertTrue(html.contains("&lt;script&gt;"), "转义后的文本可见：")
        // 离线纪律：任何外部引用都不允许
        listOf("src=\"http", "href=\"http", "cdn.").forEach { ref ->
            assertTrue(!html.contains(ref), "不得出现外部引用 $ref")
        }
    }

    @Test
    fun `落地页——图表用同一渲染器内联 SVG，渲染不了的如实注明`() {
        val html = LandingPageExporter.export(
            LandingPageExporter.ExportRequest(
                "带图",
                listOf(
                    LandingPageExporter.ExportSection(
                        "数据",
                        "正文",
                        listOf(
                            buildJsonObject {
                                put("xAxis", buildJsonObject {
                                    put("data", JsonArray(listOf(JsonPrimitive("华东"))))
                                })
                                put("series", JsonArray(listOf(
                                    buildJsonObject {
                                        put("name", JsonPrimitive("销售额"))
                                        put("data", JsonArray(listOf(JsonPrimitive(1000))))
                                    },
                                )))
                            },
                        ),
                    ),
                ),
            ),
        )
        assertTrue(html.contains("<svg"), "图表内联 SVG：")
        assertTrue(html.contains("figcaption"), "图注：")
    }

    @Test
    fun `导出工具 landing 分支——回执带 HTML 与落款元数据`() {
        val tool = exportReportTool()
        val result = tool.invoke(
            buildJsonObject {
                put("format", "landing")
                put("title", "季度销售分析")
                put("subtitle", "10 月门店数据")
                put(
                    "sections",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("title", "区域销售")
                                put("content", "华东地区销售额最高。")
                            },
                        ),
                    ),
                )
            },
        )
        assertEquals("landing", (result["format"] as JsonPrimitive).content)
        val html = (result["html"] as JsonPrimitive).content
        assertTrue(html.contains("季度销售分析"), "落地页正文：")
        val meta = result["meta"]!!
        assertTrue(meta.toString().contains("sections"), "落款元数据：$meta")
    }
}
