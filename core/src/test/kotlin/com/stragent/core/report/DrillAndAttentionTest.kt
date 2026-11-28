package com.stragent.core.report

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.stats.drillDown
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrillAndAttentionTest {
    private fun superstore(): Dataset {
        val text = File("../fixtures/sample/superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "superstore.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private val ds by lazy { superstore() }

    @Test
    fun `下钻 West 求和与金标一致、第二维度、明细行`() {
        val regionIndex = ds.columns.indexOf("Region")
        val salesIndex = ds.columns.indexOf("Sales")
        val result = drillDown(ds, regionIndex, salesIndex, "West")!!
        // 与 brief 金标同源：West 合计 725,457.94
        assertEquals(725457.94, result.total, 0.02)
        assertEquals("sum", result.mode)
        assertTrue(!result.share.isNaN() && result.share in 0.29..0.33, "share=${result.share}")
        // 第二维度：Ship Mode / Segment（基数 2–24、非 ID、非时间/指标/分组列）
        assertEquals(2, result.crosses.size)
        assertEquals("Ship Mode", result.crosses[0].dimension)
        val crossSum = result.crosses[0].items.sumOf { it.value }
        assertEquals(result.total, crossSum, 0.5, "组内占比分母 = 该组合计")
        // 明细行带原始行号，回到原始行能对上
        assertTrue(result.rows.isNotEmpty() && result.rows.size <= 20)
        val first = result.rows.first()
        assertEquals(ds.rows[first.row - 1], first.cells)
        assertEquals(result.rows.map { it.row }, result.evidenceRows)
        // 笔均对照（meanRatio 用未取整的均值计算，与 round2 后的比值容差 1e-3）
        assertEquals(result.mean / result.overallMean, result.meanRatio, 1e-3)
    }

    @Test
    fun `下钻 平均模式 share 无意义为 NaN、不存在的组返回 null`() {
        val regionIndex = ds.columns.indexOf("Region")
        val salesIndex = ds.columns.indexOf("Sales")
        val avg = drillDown(ds, regionIndex, salesIndex, "West", mode = "avg")!!
        assertTrue(avg.share.isNaN(), "平均模式下占比无意义")
        assertEquals(null, drillDown(ds, regionIndex, salesIndex, "不存在的组"))
        assertEquals(null, drillDown(ds, -1, salesIndex, "West"))
    }

    private fun finding(kind: AnomalyKind, detail: String, period: String? = null, dimension: String? = null) =
        AnomalyFinding(
            kind = kind, severity = Severity.HIGH, metric = "Sales", period = period, dimension = dimension,
            before = 100.0, after = 200.0, change = 1.0, contributors = emptyList(),
            evidenceRows = emptyList(), detail = detail,
        )

    private fun scan(vararg findings: AnomalyFinding, monthly: Boolean = true, sparse: Boolean = false, months: Int = 24) =
        AnomalyScan(
            findings = findings.toList(),
            coverage = AnomalyCoverage(monthly = monthly, sparse = sparse, dimension = "Region", metric = "Sales", months = months),
            summary = AnomalyScan.Summary(1, 0, 0, findings.size),
        )

    @Test
    fun `注意力排序——blocking 恒在 insight 之前，顺序由 BLOCKING_ORDER 驱动`() {
        // 故意乱序 push：gap、negative、duplicate——排序必须按污染面重排
        val plan = planAttention(
            ds,
            scan(
                finding(AnomalyKind.GAP, "有缺口"),
                finding(AnomalyKind.NEGATIVE, "有负值"),
                finding(AnomalyKind.DUPLICATE, "有重复"),
                finding(AnomalyKind.SHIFT, "3 月突变", period = "2017-03"),
            ),
        )
        val ids = plan.items.map { it.id }
        assertEquals("blocking-duplicate", ids[0], "重复行第一：唯一让所有结论一起错")
        assertEquals("blocking-gap", ids[1])
        assertEquals("blocking-negative", ids[2], "负值最后：最可能本来就该是这样")
        assertEquals(3, plan.items.size)
        assertEquals(1, plan.hidden, "insight-shift 被截断且计数")
        // reason 原样可解释（纪律 b）
        assertTrue(plan.items[0].reason.contains("重复行会同时抬高"))
    }

    @Test
    fun `注意力——shift 进 insight 档指向归因链，sparse 进 meta`() {
        val plan = planAttention(ds, scan(finding(AnomalyKind.SHIFT, "突变", period = "2018-11"), sparse = false))
        val item = plan.items.single()
        assertEquals(AttentionAction.CHAIN, item.action)
        assertEquals("2018-11", item.period)
        assertTrue(item.title.contains("2018-11"))
        // 稳定 id：同数据两次调用相同
        val again = planAttention(ds, scan(finding(AnomalyKind.SHIFT, "突变", period = "2018-11"), sparse = false))
        assertEquals(plan.items.single().id, again.items.single().id)

        val sparsePlan = planAttention(ds, scan(sparse = true, monthly = true, months = 30))
        assertTrue(sparsePlan.items.any { it.id == "meta-sparse" })
    }

    @Test
    fun `注意力空态——没有要处理的事时给收尾话`() {
        val plan = planAttention(ds, scan())
        assertEquals(0, plan.items.size)
        assertTrue(plan.quiet!!.contains("没有发现需要优先处理的问题"))
        assertTrue(plan.quiet!!.contains("可以直接生成报告"))
    }
}
