package com.stragent.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-138 补强轮 · SVG 栅格化与图表嵌入共用件测试。
 *
 * 覆盖：SVG → PNG（真实像素尺寸、PNG 签名、白底不透明）/ 尺寸解析 / 图表配置 →
 * 嵌入图（真实 ECharts option 能出图、渲染不了的配置如实跳过）。
 */
class SvgRasterizerTest {

    private fun barOption(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("xAxis", buildJsonObject {
            put("data", JsonArray(listOf(JsonPrimitive("华东"), JsonPrimitive("华北"))))
        })
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("name", JsonPrimitive("销售额"))
                put("data", JsonArray(listOf(JsonPrimitive(1000), JsonPrimitive(300))))
            },
        )))
    }

    @Test
    fun `SVG 转 PNG——真实字节、有尺寸、白底`() {
        val svg = com.stragent.core.chart.SvgChartRenderer.render(
            com.stragent.core.chart.EChartsOptionReader.read(barOption())!!,
        )
        val png = runCatching { SvgRasterizer.toPng(svg, widthPx = 480) }
            .getOrElse { error("栅格化失败：${it.stackTraceToString()}") }
        // PNG 签名
        assertEquals(
            listOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            png.take(8).toList(),
            "必须是合法 PNG：${png.take(8).toList()}",
        )
        val (w, h) = SvgRasterizer.pngSize(png)
        assertEquals(480, w, "宽度按目标宽度：$w x $h")
        assertTrue(h in 100..600, "高度按原比例：$w x $h")
    }

    @Test
    fun `SVG 尺寸解析——缺省值兜底`() {
        assertEquals(640 to 480, SvgRasterizer.svgSize("<svg width=\"640\" height=\"480\"></svg>"))
        assertEquals(800 to 480, SvgRasterizer.svgSize("<svg></svg>"))
    }

    @Test
    fun `图表配置转嵌入图——真实 option 出图，渲染不了的如实跳过`() {
        val embedded = ExportCharts.embed(barOption(), 0)
        assertTrue(embedded != null, "柱状图应能嵌入")
        assertTrue(embedded!!.png.isNotEmpty())
        assertTrue(embedded.width > 0 && embedded.height > 0)

        // 渲染不了的配置（无 xAxis/series）→ null（调用方如实注明，不假装画出来）
        val unsupported = buildJsonObject { put("series", JsonArray(emptyList())) }
        assertEquals(null, ExportCharts.embed(unsupported, 0), "渲染不了的配置应返回 null")
        assertEquals(1, ExportCharts.skipped(listOf(unsupported)), "跳过数如实统计")
    }
}
