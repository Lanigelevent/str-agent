package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 分析模块的行为测试（对照 TS 侧既有契约值）。
 * superstore 样本走完整 analyze 路径；group-trend 的关键行为用内联小样本验。
 */
class StatsAlignmentTest {
    private val sampleDir = File("../fixtures/sample")

    private fun superstore(): Dataset {
        val text = File(sampleDir, "superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "superstore.csv",
            importedAt = 1,
            columns = limited.columns,
            rows = limited.rows,
            columnTypes = types,
            delimiter = limited.delimiter,
            truncated = limited.truncated,
            sourceRows = limited.totalRows,
        )
    }

    @Test
    fun `superstore 列选择契约`() {
        val result = analyze(superstore())
        // industry-entry / superstore-entry 的 pickDefaultColumns 契约
        assertEquals("Sales", superstore().columns[result.columns.value])
        assertEquals("Order Date", superstore().columns[result.columns.x])
        assertTrue(result.columns.month >= 0) // 有日期列
        assertTrue(result.columns.second >= 0) // 有第二维度
    }

    @Test
    fun `superstore 同比 yoy 关键月份`() {
        val d = superstore()
        val yoy = yearOverYear(d)!!
        // yoy-entry: 2015-09 环比 +193%（±0.02）
        val sep = yoy.points.find { it.month == "2015-09" }!!
        assertEquals(1.93, sep.mom!!, 0.02)
        // 第一年没有同比（数据从 2015-01 起）
        assertEquals(null, sep.change)
        // 2018-08 环比 +39%、同比 +103%
        val aug = yoy.points.find { it.month == "2018-08" }!!
        assertEquals(0.39, aug.mom!!, 0.02)
        assertEquals(1.03, aug.change!!, 0.02)
    }

    @Test
    fun `superstore 分组同比（group-yoy-entry 的 2018-12 期间）`() {
        val d = superstore()
        val r = groupYearOverYear(d, 8, 17, 3)!! // Segment=8, Sales=17, Order Date=3
        assertEquals("2018-12", r.period)
        assertEquals("2017-12", r.previousPeriod)
        // 变化是数（不讨论方向），只验结构
        assertTrue(r.overall != null)
        assertTrue(r.points.isNotEmpty())
        assertTrue(r.points[0].current > 0)
    }

    @Test
    fun `superstore 交叉表——基准行均与全体行数`() {
        val d = superstore()
        val r = crossTabulateWithYield(d, 12, 8, 17)!! // Region × Segment × Sales
        // cross-tab-entry: 全体行均 > 0、统计行数 9994
        assertTrue(r.baseline > 0)
        assertEquals(9994, r.rows)
        assertTrue(r.cells.isNotEmpty())
        assertTrue(r.busiest != null)
    }

    @Test
    fun `group-trend 边界：列越界返回 null`() {
        val d = superstore()
        assertEquals(null, groupTrend(d, 99, 2, 1))
    }

    @Test
    fun `group-trend 内联小样本（group-trend-entry 的连续期）`() {
        // A: 2015 全年 100/月 + 2016 全年 120/月 + 2017-01 140 + 2017-02 缺 + 2017-03 90（跌回）
        val rows = mutableListOf<List<String>>()
        for (m in 1..12) rows.add(listOf("A", "2015-${m.toString().padStart(2, '0')}", "100"))
        for (m in 1..12) rows.add(listOf("A", "2016-${m.toString().padStart(2, '0')}", "120"))
        rows.add(listOf("A", "2017-01", "140"))
        rows.add(listOf("A", "2017-03", "90"))
        val ds = Dataset(
            name = "test", importedAt = 1, columns = listOf("组", "月份", "值"),
            rows = rows, columnTypes = listOf(ColumnType.TEXT, ColumnType.TEXT, ColumnType.NUMBER),
        )
        val r = groupTrend(ds, 0, 2, 1)!!
        val a = r.points.find { it.label == "A" }!!
        assertEquals(26, a.periods) // 2015(12) + 2016(12) + 2017-01 + 2017-03，缺 2017-02
        assertEquals("2017-03", a.lastMonth)
    }

    @Test
    fun `buildSeries 合并重复 X 取均值`() {
        val ds = Dataset(
            name = "t", importedAt = 1,
            columns = listOf("date", "val"),
            rows = listOf(listOf("1/1/2018", "100"), listOf("1/1/2018", "200"), listOf("2/1/2018", "300")),
            columnTypes = listOf(ColumnType.TEXT, ColumnType.NUMBER),
        )
        val series = buildSeries(ds, 0, 1)
        assertEquals(2, series.size)
        assertEquals(150.0, series[0].y, 1e-9) // (100+200)/2
        assertEquals(300.0, series[1].y, 1e-9)
    }

    @Test
    fun `极值定位带行号与分组标签`() {
        val ds = Dataset(
            name = "t", importedAt = 1,
            columns = listOf("组", "日期", "值"),
            rows = listOf(
                listOf("甲", "2018-01", "20"),
                listOf("乙", "2018-02", "90"),
                listOf("丙", "2018-03", "50"),
            ),
            columnTypes = listOf(ColumnType.TEXT, ColumnType.TEXT, ColumnType.NUMBER),
        )
        val pair = locateExtremes(ds, 2, 0, 1)
        assertEquals(90.0, pair.max!!.value)
        assertEquals(2, pair.max!!.row)
        assertEquals("乙", pair.max!!.group)
        assertEquals("2018-02", pair.max!!.date)
        assertEquals(20.0, pair.min!!.value)
    }
}
