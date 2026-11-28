package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttributionChainTest {
    private val ds: Dataset by lazy {
        val text = File("../fixtures/sample/superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        Dataset(
            name = "superstore.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    @Test
    fun `归因链——2015-09 突变的四环与量价分解精确性`() {
        // 金标：2015-09 合计 81,777.35，前一期 27,909.47（+193%，与 anomaly-scan/attention 同源）
        val chain = buildAttributionChain(ds, "2015-09")!!
        assertEquals("2015-09", chain.rootPeriod)
        assertEquals(81777.35, chain.rootAfter, 0.02)
        assertEquals(27909.47, chain.rootBefore, 0.02)
        // ① 指标环 share=1
        assertEquals("metric", chain.links[0].kind)
        assertEquals(1.0, chain.links[0].share, 1e-9)
        // ② 维度环存在。启发式选**第一个**基数 2–24 的非 ID 文本列 = Ship Mode
        //（TS pickDimension 同口径；attention 卡里的 "Consumer" 来自 anomaly-scan
        // 自己的分组选择，两者维度不同是预期行为）
        val dimension = chain.links.find { it.kind == "dimension" }
        assertTrue(dimension != null && dimension.label == "Standard Class", "实际: ${chain.links.map { it.label }}")
        assertEquals("Ship Mode", ds.columns[ds.columns.indexOf("Ship Mode")])
        // ③ 子维度环 + ④ 量价环
        assertTrue(chain.links.any { it.kind == "subdimension" }, "应有子维度环")
        val volume = chain.links.find { it.kind == "decompose" && it.label == "笔数" }!!
        val price = chain.links.find { it.kind == "decompose" && it.label == "笔均" }!!
        // 分解无残差：Δ笔数效应 + Δ笔均效应 == 焦点组合计变化（维度环的 delta）
        assertEquals(dimension.delta, volume.delta + price.delta, 0.05)
        assertTrue(chain.verdict in listOf("volume", "price", "both"))
        assertTrue(chain.verdictText.contains("变化主要来自") || chain.verdictText.contains("没有变化"))
        // detail 可复核（笔数 → 笔均的算式）
        assertTrue(volume.detail.contains("笔数效应"))
        assertTrue(price.detail.contains("按当期笔数"))
    }

    @Test
    fun `归因链——返回 null 的三种情况`() {
        // 找不到那个月
        assertNull(buildAttributionChain(ds, "1999-01"))
        // 起点月没有前一期（首期）
        val firstMonth = ds.rows
            .mapNotNull { com.stragent.core.stats.monthKey(it.getOrNull(ds.columns.indexOf("Order Date")) ?: "") }
            .min()
        assertNull(buildAttributionChain(ds, firstMonth), "首期无环比 → null")
    }

    @Test
    fun `归因链——preferredGroup 生效（按 Category 维度层走）`() {
        val categoryIndex = ds.columns.indexOf("Category")
        val chain = buildAttributionChain(ds, "2015-09", preferredGroup = categoryIndex)!!
        val dimension = chain.links.find { it.kind == "dimension" }
        assertTrue(dimension != null && dimension.label in listOf("Furniture", "Office Supplies", "Technology"))
        assertEquals("Category", ds.columns[categoryIndex])
    }
}
