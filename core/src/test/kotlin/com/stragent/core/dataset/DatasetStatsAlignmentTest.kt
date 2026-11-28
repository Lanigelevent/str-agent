package com.stragent.core.dataset

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * dataset 统计核心的对齐测试。
 * 断言值全部来自 TS 侧 superstore-entry / multi-entry（186/186 全绿）的既有契约，
 * 夹具 = fixtures/sample/ 的同一批文件。
 */
class DatasetStatsAlignmentTest {
    private val sampleDir = File("../fixtures/sample")

    private fun superstore(): Dataset {
        val table = Csv.parseDelimited(File(sampleDir, "superstore.csv").readText())
        return datasetFromParsed("Superstore 销售明细.csv", 1, table)
    }

    // ---------- superstore 主契约（superstore-entry 逐条） ----------

    @Test
    fun `Sales 列统计四要素`() {
        val d = superstore()
        val sales = summarize(d)[17]
        kotlin.test.assertEquals(9994, sales.count)
        kotlin.test.assertEquals(0, sales.missing)
        kotlin.test.assertEquals(229.8580, sales.mean!!, 1e-4)
        kotlin.test.assertEquals(2297201.07, sales.sum!!, 1e-2)
        kotlin.test.assertEquals(54.49, sales.median!!, 1e-6)
        kotlin.test.assertEquals(17.28, sales.p25!!, 1e-6)
        kotlin.test.assertEquals(209.94, sales.p75!!, 1e-6)
        kotlin.test.assertEquals(22638.48, sales.max!!, 1e-6)
    }

    @Test
    fun `Profit 脏值口径（count 是实际参与统计的样本数）`() {
        val d = superstore()
        val profit = summarize(d)[20]
        kotlin.test.assertEquals(9929, profit.count)
        kotlin.test.assertEquals(65, profit.missing)
    }

    @Test
    fun `区域分组 sum 与占比（share 分母 = 全体合计）`() {
        val d = superstore()
        val byRegion = aggregateBy(d, 12, 17, "sum")
        kotlin.test.assertEquals(listOf("West", "East", "Central", "South"), byRegion.map { it.label })
        kotlin.test.assertEquals(725457.93, byRegion[0].value, 1e-2)
        kotlin.test.assertEquals(0.3158, byRegion[0].share, 1e-3)
        kotlin.test.assertEquals(678781.36, byRegion[1].value, 1e-2)
        kotlin.test.assertEquals(501239.88, byRegion[2].value, 1e-2)
        kotlin.test.assertEquals(391721.90, byRegion[3].value, 1e-2)
        kotlin.test.assertEquals(1.0, byRegion.sumOf { it.share }, 1e-9)
    }

    @Test
    fun `品类分组顺序与 Technology 合计`() {
        val d = superstore()
        val byCategory = aggregateBy(d, 14, 17, "sum")
        kotlin.test.assertEquals(listOf("Technology", "Furniture", "Office Supplies"), byCategory.map { it.label })
        kotlin.test.assertEquals(836154.10, byCategory[0].value, 1e-2)
    }

    @Test
    fun `默认列选择（ID 列与索引列跳过）`() {
        val d = superstore()
        val defaults = pickDefaultColumns(d)
        kotlin.test.assertEquals("Sales", d.columns[defaults.value])
        kotlin.test.assertEquals("Order Date", d.columns[defaults.x])
        kotlin.test.assertTrue(d.columns[defaults.category] != "Order ID" && d.columns[defaults.category] != "Customer ID")
    }

    @Test
    fun `ID 列名识别词表`() {
        kotlin.test.assertTrue(looksLikeIdColumn("Row ID"))
        kotlin.test.assertTrue(looksLikeIdColumn("Postal Code"))
        kotlin.test.assertTrue(looksLikeIdColumn("列1"))
        kotlin.test.assertTrue(looksLikeIdColumn("EmployeeID")) // 驼峰切词
        kotlin.test.assertTrue(looksLikeIdColumn("员工编号")) // 中文子串
        kotlin.test.assertTrue(!looksLikeIdColumn("Sales"))
        kotlin.test.assertTrue(!looksLikeIdColumn("Region"))
        kotlin.test.assertTrue(!looksLikeIdColumn("Order Date"))
        kotlin.test.assertTrue(!looksLikeIdColumn("销售额"))
    }

    // ---------- multi-entry 的多样本契约 ----------

    @Test
    fun `titanic 真实缺失口径`() {
        val table = Csv.parseDelimited(File(sampleDir, "titanic.csv").readText())
        val d = datasetFromParsed("titanic.csv", 1, table)
        val s = summarize(d)
        kotlin.test.assertEquals(891, d.rows.size)
        kotlin.test.assertEquals(177, s[5].missing) // Age
        kotlin.test.assertEquals(687, s[10].missing) // Cabin
        kotlin.test.assertEquals(29.699118, s[5].mean!!, 1e-4)
        kotlin.test.assertEquals(32.204208, s[9].mean!!, 1e-4)
    }

    @Test
    fun `iris 三品种按花萼长度降序`() {
        val table = Csv.parseDelimited(File(sampleDir, "iris.csv").readText())
        val d = datasetFromParsed("iris.csv", 1, table)
        val bySpecies = aggregateBy(d, 4, 0, "avg")
        kotlin.test.assertEquals(listOf("virginica", "versicolor", "setosa"), bySpecies.map { it.label })
        kotlin.test.assertEquals(5.006, bySpecies[2].value, 1e-3)
        // avg 模式 share 无意义 → NaN 哨兵
        kotlin.test.assertTrue(bySpecies.all { it.share.isNaN() })
    }

    @Test
    fun `penguins 缺失口径`() {
        val table = Csv.parseDelimited(File(sampleDir, "penguins.csv").readText())
        val d = datasetFromParsed("penguins.csv", 1, table)
        val s = summarize(d)
        kotlin.test.assertEquals(344, d.rows.size)
        kotlin.test.assertEquals(2, s[5].missing) // body_mass_g
        kotlin.test.assertEquals(11, s[6].missing) // sex
        kotlin.test.assertEquals(4201.754386, s[5].mean!!, 1e-4)
    }

    @Test
    fun `tips 引号剥离与合计`() {
        val table = Csv.parseDelimited(File(sampleDir, "tips.csv").readText())
        val d = datasetFromParsed("tips.csv", 1, table)
        val s = summarize(d)
        kotlin.test.assertEquals(244, d.rows.size)
        kotlin.test.assertEquals(19.785943, s[0].mean!!, 1e-4)
        kotlin.test.assertEquals(731.58, s[1].sum!!, 1e-2)
    }

    @Test
    fun `china_gdp 中文列名与统计`() {
        val table = Csv.parseDelimited(File(sampleDir, "china_gdp.csv").readText())
        val d = datasetFromParsed("china_gdp.csv", 1, table)
        val s = summarize(d)
        kotlin.test.assertEquals(31, d.rows.size)
        kotlin.test.assertEquals("省市", d.columns[1])
        kotlin.test.assertEquals("广东", d.rows[0][1])
        kotlin.test.assertEquals(134.0282, s[2].sum!! / 10000, 1e-3)
        kotlin.test.assertEquals(141633.81, s[2].max!!, 1e-6)
        kotlin.test.assertEquals(2764.94, s[2].min!!, 1e-6)
        kotlin.test.assertEquals(4.308065, s[3].mean!!, 1e-4)
        kotlin.test.assertEquals(-2.14, s[3].min!!, 1e-6) // 山西名义增速为负
    }
}
