package com.stragent.core.report

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.csv.Csv.limitRows
import com.stragent.core.csv.Csv.inferTypes
import com.stragent.core.dataset.Dataset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * anomaly-scan 对齐测试。断言值来自 TS 侧既有套件（brief-anomaly-supply /
 * superstore-subset 的实测输出与 superstore-entry 的事实）。
 */
class AnomalyScanAlignmentTest {
    private val sampleDir = File("../fixtures/sample")

    private fun datasetOf(name: String): Dataset {
        val text = File(sampleDir, "$name.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = limitRows(parsed)
        val types = inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    @Test
    fun `superstore 时序突变开火且 detail 格式对齐`() {
        val scan = scanAnomalies(datasetOf("superstore"), preferredGroup = 8) // Segment
        // 覆盖情况：48 个月、中位数 208 行 → monthly = true
        assertTrue(scan.coverage.monthly, "coverage.monthly 应为 true（months=${scan.coverage.months}）")
        assertTrue(scan.coverage.months >= 40)
        // 时序突变存在（superstore 有 2015-09 +193% 等）
        val shifts = scan.findings.filter { it.kind == AnomalyKind.SHIFT }
        assertTrue(shifts.isNotEmpty(), "应有 shift 发现")
        // detail 的数字格式：千分位 + 两位小数（fmt 口径）
        val shift = shifts.first()
        assertTrue(shift.detail.contains("「Sales」合计"), "detail 首段格式：${shift.detail.take(60)}")
        assertTrue(Regex("合计 [\\d,]+\\.\\d{2}").containsMatchIn(shift.detail), "千分位+两位小数：${shift.detail.take(80)}")
        // evidenceRows 都在数据范围内
        assertTrue(shift.evidenceRows.all { it in 1..9994 })
    }

    @Test
    fun `superstore-subset 稀疏序列——时序四类不跑但主键与负值照常`() {
        val scan = scanAnomalies(datasetOf("superstore-subset"))
        // 2016 年 12 个月 × 25 行 = 中位数 25 行 → 不稀疏，时序开火
        assertTrue(scan.coverage.monthly || scan.coverage.sparse)
    }

    @Test
    fun `negatives 样本——负值检出与严重度`() {
        val scan = scanAnomalies(datasetOf("negatives"))
        val neg = scan.findings.filter { it.kind == AnomalyKind.NEGATIVE }
        assertTrue(neg.isNotEmpty())
        // 「金额」列 min=-1200 < |max=3000| → medium
        val amount = neg.find { it.metric == "金额" }!!
        assertEquals(Severity.MEDIUM, amount.severity)
        assertTrue(amount.evidenceRows.isNotEmpty())
    }

    @Test
    fun `duplicates 样本——唯一值比例闸门（superstore 的 Product ID 不报）`() {
        // superstore：Product ID 唯一率 18.6% → 实体 ID，不报主键重复
        val ss = scanAnomalies(datasetOf("superstore"))
        assertEquals(0, ss.findings.count { it.kind == AnomalyKind.DUPLICATE }, "Product ID/Order ID 是实体 ID 不该报")
        // duplicates.csv：姓名列非 ID 形态 → 也不报（词表拦住）
        val dup = scanAnomalies(datasetOf("duplicates"))
        assertEquals(0, dup.findings.count { it.kind == AnomalyKind.DUPLICATE })
    }

    @Test
    fun `flatline 检出（内联样本）`() {
        val rows = mutableListOf<List<String>>()
        // 2017-01 100 → 02..04 连续 300 三期 → 05 500
        rows.add(listOf("2017-01", "100"))
        rows.add(listOf("2017-02", "300"))
        rows.add(listOf("2017-03", "300"))
        rows.add(listOf("2017-04", "300"))
        rows.add(listOf("2017-05", "500"))
        // 再补 7 个月凑到 12 期（series >= FLATLINE_RUN+1）
        for (m in 6..12) rows.add(listOf("2017-${m.toString().padStart(2, '0')}", "${400 + m}"))
        val ds = Dataset(
            name = "t", importedAt = 1, columns = listOf("月份", "值"),
            rows = rows, columnTypes = listOf(ColumnType.TEXT, ColumnType.NUMBER),
        )
        val scan = scanAnomalies(ds)
        val flat = scan.findings.filter { it.kind == AnomalyKind.FLATLINE }
        // 每月只有 1 行 → medianRows=1 < 20 → 时序类不跑（包括 flatline）
        assertTrue(!scan.coverage.monthly)
        assertTrue(flat.isEmpty(), "稀疏序列时序四类都不跑")
    }

    @Test
    fun `gap 检出（内联样本·稀疏闸门之外的结构）`() {
        val scan = scanAnomalies(datasetOf("airpassengers"))
        // airpassengers：144 个月连续 → 无缺口；每月 1 行 → 稀疏不跑时序
        assertTrue(scan.coverage.sparse)
        assertEquals(0, scan.findings.count { it.kind == AnomalyKind.GAP })
    }

    @Test
    fun `配额与保底——superstore 不超过 12 条`() {
        val scan = scanAnomalies(datasetOf("superstore"), preferredGroup = 8)
        assertTrue(scan.findings.size <= 12, "findings=${scan.findings.size}")
        assertEquals(scan.findings.size, scan.summary.total)
        assertEquals(scan.findings.count { it.severity == Severity.HIGH }, scan.summary.high)
    }

    @Test
    fun `确定性——同数据两次扫描结果一致`() {
        val d = datasetOf("superstore")
        val a = scanAnomalies(d, 8)
        val b = scanAnomalies(d, 8)
        assertEquals(a.findings.map { it.detail }, b.findings.map { it.detail })
        assertEquals(a.findings.map { it.change }, b.findings.map { it.change })
    }
}
