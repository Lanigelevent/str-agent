package com.stragent.core.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * SvgChartRenderer 单元测试（D10 阶段 4 可视化落地）。
 *
 * 验证维度：
 *  1. 四种图表类型均产出有效 SVG（含 svg 根标签与闭合标签）
 *  2. SVG 包含标题文本
 *  3. 柱状图包含 rect 元素
 *  4. 折线图包含 polyline 元素
 *  5. 饼图包含 path 元素
 *  6. 散点图包含 circle 元素
 *  7. 确定性（同一输入两次渲染完全一致）
 *  8. 空数据返回 "无有效数据"
 *  9. 颜色转义防注入
 */
class SvgChartRendererTest {

    private fun sampleBarSpec(): ChartSpec = barChart(
        title = "季度销售",
        categories = listOf("Q1", "Q2", "Q3", "Q4"),
        series = listOf(ChartSeries("2024", listOf(100.0, 200.0, 150.0, 180.0))),
        xLabel = "季度",
        yLabel = "万元",
    )

    private fun sampleLineSpec(): ChartSpec = lineChart(
        title = "月度趋势",
        categories = listOf("1月", "2月", "3月", "4月", "5月"),
        series = listOf(ChartSeries("用户数", listOf(50.0, 80.0, 120.0, 100.0, 150.0))),
    )

    private fun samplePieSpec(): ChartSpec = pieChart(
        title = "市场份额",
        categories = listOf("A", "B", "C", "D"),
        values = listOf(30.0, 40.0, 15.0, 15.0),
    )

    private fun sampleScatterSpec(): ChartSpec = scatterChart(
        title = "身高体重",
        xValues = listOf(160.0, 170.0, 180.0, 175.0, 165.0),
        yValues = listOf(55.0, 65.0, 75.0, 70.0, 60.0),
        xLabel = "身高",
        yLabel = "体重",
    )

    @Test
    fun `柱状图产出有效 SVG`() {
        val svg = SvgChartRenderer.render(sampleBarSpec())
        assertTrue(svg.startsWith("<svg"), "应以 <svg 开头")
        assertTrue(svg.endsWith("</svg>"), "应以 </svg> 结尾")
        assertTrue(svg.contains("rect "), "应包含 rect 元素")
        assertTrue(svg.contains("季度销售"), "应包含标题")
    }

    @Test
    fun `柱状图含轴标签`() {
        val svg = SvgChartRenderer.render(sampleBarSpec())
        assertTrue(svg.contains("季度"), "应含 X 轴标签")
        assertTrue(svg.contains("万元"), "应含 Y 轴标签")
    }

    @Test
    fun `柱状图多系列含图例`() {
        val spec = barChart(
            title = "对比",
            categories = listOf("A", "B", "C"),
            series = listOf(
                ChartSeries("2023", listOf(10.0, 20.0, 30.0)),
                ChartSeries("2024", listOf(15.0, 25.0, 35.0)),
            ),
        )
        val svg = SvgChartRenderer.render(spec)
        assertTrue(svg.contains("legend"), "多系列应有图例")
        assertTrue(svg.contains("2023"), "图例应含系列名")
        assertTrue(svg.contains("2024"))
    }

    @Test
    fun `折线图产出有效 SVG`() {
        val svg = SvgChartRenderer.render(sampleLineSpec())
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertTrue(svg.contains("polyline"), "应包含 polyline 元素")
        assertTrue(svg.contains("circle"), "折线图应含数据点 circle")
        assertTrue(svg.contains("月度趋势"))
    }

    @Test
    fun `饼图产出有效 SVG`() {
        val svg = SvgChartRenderer.render(samplePieSpec())
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertTrue(svg.contains("path "), "应包含 path 元素")
        assertTrue(svg.contains("市场份额"))
        // 饼图应含百分比标签
        assertTrue(svg.contains("%"), "应含百分比")
    }

    @Test
    fun `散点图产出有效 SVG`() {
        val svg = SvgChartRenderer.render(sampleScatterSpec())
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertTrue(svg.contains("circle"), "应包含 circle 元素")
        assertTrue(svg.contains("身高体重"))
    }

    @Test
    fun `确定性 同一输入两次渲染完全一致`() {
        val spec = sampleBarSpec()
        val svg1 = SvgChartRenderer.render(spec)
        val svg2 = SvgChartRenderer.render(spec)
        assertEquals(svg1, svg2, "同一输入应产出完全一致的 SVG")
    }

    @Test
    fun `空数据返回无有效数据`() {
        val spec = barChart(
            title = "空",
            categories = emptyList(),
            series = listOf(ChartSeries("A", emptyList())),
        )
        val svg = SvgChartRenderer.render(spec)
        assertTrue(svg.contains("无有效数据"), "空数据应显示提示")
    }

    @Test
    fun `全零值返回无有效数据`() {
        val spec = barChart(
            title = "零",
            categories = listOf("A", "B"),
            series = listOf(ChartSeries("A", listOf(0.0, 0.0))),
        )
        val svg = SvgChartRenderer.render(spec)
        // 全零值 range=0 应返回空 SVG
        assertTrue(svg.contains("无有效数据"))
    }

    @Test
    fun `HTML 特殊字符被转义`() {
        val spec = barChart(
            title = "<script>alert(1)</script>",
            categories = listOf("A&B", "C<D"),
            series = listOf(ChartSeries("X\"Y", listOf(10.0, 20.0))),
        )
        val svg = SvgChartRenderer.render(spec)
        assertFalse(svg.contains("<script>"), "不应含未转义的 <script>")
        assertTrue(svg.contains("&lt;script&gt;"), "应转义 < 和 >")
        assertTrue(svg.contains("&amp;"), "应转义 &")
    }

    @Test
    fun `散点图 xValues 与 yValues 长度不一致返回空 SVG`() {
        val spec = ChartSpec(
            type = ChartType.SCATTER,
            title = "不一致",
            series = listOf(ChartSeries("A", listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0))),
            categories = emptyList(),
        )
        val svg = SvgChartRenderer.render(spec)
        assertTrue(svg.contains("无有效数据"))
    }

    @Test
    fun `宽高被钳制到合法范围`() {
        val spec = ChartSpec(
            type = ChartType.BAR,
            title = "极端宽高",
            series = listOf(ChartSeries("A", listOf(10.0, 20.0))),
            categories = listOf("X", "Y"),
            width = 10,
            height = 10,
        )
        val svg = SvgChartRenderer.render(spec)
        // 宽高被 coerce 到 [200,1200] / [150,800]
        assertTrue(svg.contains("width=\"200\""), "宽度应被钳制到 200")
        assertTrue(svg.contains("height=\"150\""), "高度应被钳制到 150")
    }

    @Test
    fun `饼图全零值返回空 SVG`() {
        val spec = pieChart(
            title = "零饼",
            categories = listOf("A", "B"),
            values = listOf(0.0, 0.0),
        )
        val svg = SvgChartRenderer.render(spec)
        assertTrue(svg.contains("无有效数据"))
    }

    @Test
    fun `散点图含网格线`() {
        val spec = sampleScatterSpec()
        val svg = SvgChartRenderer.render(spec)
        assertTrue(svg.contains("grid"), "应含网格组")
        assertTrue(svg.contains("stroke=\"#e0e0e0\""), "应含网格线")
    }
}
