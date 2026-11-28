package com.stragent.core.chart

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EChartsOptionReader（T-054 HTML 去 CDN 的关键一环）：
 * [EChartsOptionBuilder] 的逆变换必须能读回自家产物；读不回的配置宁可返回 null
 * （调用方给「未渲染 + 原始 option」的降级），也不能画错。
 */
class EChartsOptionReaderTest {

    @Test
    fun `柱状图 option 读回——分类、系列与轴标签一致`() {
        val spec = barChart(
            title = "区域销售",
            categories = listOf("华东", "华北"),
            series = listOf(ChartSeries("销售额", listOf(1000.0, 300.0))),
            xLabel = "地区",
            yLabel = "销售额",
        )
        val read = EChartsOptionReader.read(EChartsOptionBuilder.build(spec))
        assertTrue(read != null)
        assertEquals(ChartType.BAR, read!!.type)
        assertEquals("区域销售", read.title)
        assertEquals(listOf("华东", "华北"), read.categories)
        assertEquals(listOf(1000.0, 300.0), read.series[0].values)
        assertEquals("地区", read.xLabel)
        assertEquals("销售额", read.yLabel)
    }

    @Test
    fun `折线图 option 读回——多系列不丢失`() {
        val spec = lineChart(
            title = "趋势",
            categories = listOf("1月", "2月", "3月"),
            series = listOf(
                ChartSeries("甲", listOf(1.0, 2.0, 3.0)),
                ChartSeries("乙", listOf(2.0, 3.0, 4.0)),
            ),
        )
        val read = EChartsOptionReader.read(EChartsOptionBuilder.build(spec))
        assertEquals(ChartType.LINE, read!!.type)
        assertEquals(2, read.series.size)
        assertEquals(listOf(1.0, 2.0, 3.0), read.series[0].values)
        assertEquals(listOf(2.0, 3.0, 4.0), read.series[1].values)
    }

    @Test
    fun `饼图 option 读回——名称与数值一致`() {
        val spec = pieChart(
            title = "占比",
            categories = listOf("A", "B"),
            values = listOf(60.0, 40.0),
        )
        val read = EChartsOptionReader.read(EChartsOptionBuilder.build(spec))
        assertEquals(ChartType.PIE, read!!.type)
        assertEquals(listOf("A", "B"), read.categories)
        assertEquals(listOf(60.0, 40.0), read.series[0].values)
    }

    @Test
    fun `散点图 option 读回——XY 成对`() {
        val spec = scatterChart(
            title = "相关性",
            xValues = listOf(1.0, 2.0, 3.0),
            yValues = listOf(4.0, 5.0, 6.0),
            xLabel = "X",
            yLabel = "Y",
        )
        val read = EChartsOptionReader.read(EChartsOptionBuilder.build(spec))
        assertEquals(ChartType.SCATTER, read!!.type)
        assertEquals(listOf(1.0, 2.0, 3.0), read.series[0].xValues)
        assertEquals(listOf(4.0, 5.0, 6.0), read.series[0].values)
        assertEquals("X", read.xLabel)
        assertEquals("Y", read.yLabel)
    }

    @Test
    fun `静态渲染不了的类型返回 null——热力图不假装能画`() {
        val spec = ChartSpec(
            type = ChartType.HEATMAP,
            title = "热力",
            series = listOf(ChartSeries("行", listOf(1.0, 2.0))),
            categories = listOf("A", "B"),
        )
        assertNull(EChartsOptionReader.read(EChartsOptionBuilder.build(spec)))
    }

    @Test
    fun `混合图返回 null——不只画一半`() {
        val option = buildJsonObject {
            put("series", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("type", "bar") },
                buildJsonObject { put("type", "line") },
            )))
        }
        assertNull(EChartsOptionReader.read(option))
    }

    @Test
    fun `series 是空对象或缺失时返回 null——降级而不是空图`() {
        assertNull(EChartsOptionReader.read(JsonObject(emptyMap())))
        assertNull(EChartsOptionReader.read(buildJsonObject { put("series", buildJsonObject {
            put("type", "bar")
        }) }))
    }

    @Test
    fun `缺 xAxis 的分类图按数据长度补序号——不丢点`() {
        val option = buildJsonObject {
            put("series", buildJsonObject {
                put("type", "bar")
                put("data", kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive(5),
                    kotlinx.serialization.json.JsonPrimitive(7),
                )))
            })
        }
        val read = EChartsOptionReader.read(option)
        assertEquals(listOf("1", "2"), read!!.categories)
        assertEquals(listOf(5.0, 7.0), read.series[0].values)
    }
}
