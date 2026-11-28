package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

/**
 * 假设检验单元测试（D10 阶段 5 统计方法扩充）。
 *
 * 验证维度：
 *  1. computeTTest：显著差异的两组数据 → significant=true
 *  2. computeTTest：无差异的两组数据 → significant=false
 *  3. computeTTest：均值与样本量正确
 *  4. computeTTest：确定性（同一输入两次一致）
 *  5. chiSquareTest：完全独立的列 → not significant
 *  6. chiSquareTest：完全关联的列 → significant
 *  7. chiSquareTest：期望频数计算正确
 *  8. 退化路径（分组不足、每组不足 2 个）
 */
class HypothesisTestTest {

    @Test
    fun `t 检验 显著差异的两组数据`() {
        // A 组均值≈100，B 组均值≈200，差异巨大
        val a = listOf(95.0, 100.0, 105.0, 98.0, 102.0, 103.0, 97.0, 100.0, 102.0, 98.0)
        val b = listOf(195.0, 200.0, 205.0, 198.0, 202.0, 203.0, 197.0, 200.0, 202.0, 198.0)
        val result = computeTTest("A", "B", a, b)
        assertTrue(result.significant, "差异巨大的两组应显著")
        assertTrue(result.pValue < 0.05, "p 值应 < 0.05")
        assertEquals(10, result.sampleA)
        assertEquals(10, result.sampleB)
    }

    @Test
    fun `t 检验 无显著差异的两组数据`() {
        // 两组均值非常接近
        val a = listOf(100.0, 101.0, 99.0, 100.0, 102.0, 98.0, 101.0, 99.0)
        val b = listOf(100.5, 100.0, 99.5, 101.0, 99.0, 100.0, 101.0, 99.0)
        val result = computeTTest("A", "B", a, b)
        assertFalse(result.significant, "均值接近的两组不应显著")
        assertTrue(result.pValue >= 0.05, "p 值应 >= 0.05")
    }

    @Test
    fun `t 检验 均值计算正确`() {
        val a = listOf(10.0, 20.0, 30.0)
        val b = listOf(40.0, 50.0, 60.0)
        val result = computeTTest("A", "B", a, b)
        assertEquals(20.0, result.meanA, 0.0001, "A 组均值应为 20")
        assertEquals(50.0, result.meanB, 0.0001, "B 组均值应为 50")
    }

    @Test
    fun `t 检验 确定性 同一输入两次一致`() {
        val a = listOf(100.0, 105.0, 98.0, 103.0, 101.0)
        val b = listOf(200.0, 195.0, 205.0, 198.0, 202.0)
        val r1 = computeTTest("A", "B", a, b)
        val r2 = computeTTest("A", "B", a, b)
        assertEquals(r1.tStatistic, r2.tStatistic)
        assertEquals(r1.pValue, r2.pValue)
        assertEquals(r1.meanA, r2.meanA)
        assertEquals(r1.meanB, r2.meanB)
        assertEquals(r1.significant, r2.significant)
    }

    @Test
    fun `t 检验 每组不足 2 个抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            computeTTest("A", "B", listOf(1.0), listOf(2.0, 3.0))
        }
        assertFailsWith<IllegalArgumentException> {
            computeTTest("A", "B", listOf(1.0, 2.0), listOf(3.0))
        }
    }

    @Test
    fun `t 检验 两组完全相同抛异常（方差为零）`() {
        assertFailsWith<IllegalArgumentException> {
            computeTTest("A", "B", listOf(5.0, 5.0, 5.0), listOf(5.0, 5.0, 5.0))
        }
    }

    @Test
    fun `independentTTest 从数据集提取分组`() {
        val ds = makeDataset(
            listOf("group", "value"),
            listOf(
                listOf("A", "100"),
                listOf("A", "102"),
                listOf("A", "98"),
                listOf("B", "200"),
                listOf("B", "198"),
                listOf("B", "202"),
            ),
        )
        val result = independentTTest(ds, 0, 1)
        assertEquals("A", result.groupA)
        assertEquals("B", result.groupB)
        assertEquals(3, result.sampleA)
        assertEquals(3, result.sampleB)
        assertTrue(result.significant, "差异大应显著")
    }

    @Test
    fun `independentTTest 空白值归入空白组`() {
        val ds = makeDataset(
            listOf("group", "value"),
            listOf(
                listOf("A", "100"),
                listOf("", "200"),
                listOf("A", "102"),
                listOf("", "198"),
            ),
        )
        val result = independentTTest(ds, 0, 1)
        // 空白值应被归为 "(空白)" 分组
        assertTrue(result.groupA == "A" || result.groupA == "(空白)")
        assertTrue(result.groupB == "A" || result.groupB == "(空白)")
    }

    @Test
    fun `independentTTest 分组不足抛异常`() {
        val ds = makeDataset(
            listOf("group", "value"),
            listOf(
                listOf("A", "100"),
                listOf("A", "102"),
                listOf("A", "98"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            independentTTest(ds, 0, 1)
        }
    }

    @Test
    fun `卡方检验 完全关联的列应显著`() {
        // 行列完全对应：A→X, B→Y
        val rows = mutableListOf<List<String>>()
        for (i in 1..20) rows.add(listOf("A", "X"))
        for (i in 1..20) rows.add(listOf("B", "Y"))
        val ds = makeDataset(
            listOf("row", "col"),
            rows,
        )
        val result = chiSquareTest(ds, 0, 1)
        assertTrue(result.significant, "完全关联的列应显著")
        assertTrue(result.pValue < 0.05)
    }

    @Test
    fun `卡方检验 均匀分布的列应不显著`() {
        // 每行每列频数完全均匀 → 期望=观察 → chiSq≈0
        val rows = mutableListOf<List<String>>()
        for (i in 1..10) rows.add(listOf("A", "X"))
        for (i in 1..10) rows.add(listOf("A", "Y"))
        for (i in 1..10) rows.add(listOf("B", "X"))
        for (i in 1..10) rows.add(listOf("B", "Y"))
        val ds = makeDataset(
            listOf("row", "col"),
            rows,
        )
        val result = chiSquareTest(ds, 0, 1)
        assertFalse(result.significant, "均匀分布应不显著")
        assertTrue(result.chiSquare < 1.0, "均匀分布的 chiSq 应≈0，实际 ${result.chiSquare}")
    }

    @Test
    fun `卡方检验 自由度正确`() {
        // 2行 2列 → df = (2-1)*(2-1) = 1
        val rows = mutableListOf<List<String>>()
        for (i in 1..10) rows.add(listOf("A", "X"))
        for (i in 1..10) rows.add(listOf("B", "Y"))
        val ds = makeDataset(listOf("row", "col"), rows)
        val result = chiSquareTest(ds, 0, 1)
        assertEquals(1, result.degreesOfFreedom)
    }

    @Test
    fun `卡方检验 期望频数正确`() {
        // 2x2 列联表，每格 10，总 40
        // 期望 = rowSum * colSum / total = 20 * 20 / 40 = 10
        val rows = mutableListOf<List<String>>()
        for (i in 1..10) rows.add(listOf("A", "X"))
        for (i in 1..10) rows.add(listOf("A", "Y"))
        for (i in 1..10) rows.add(listOf("B", "X"))
        for (i in 1..10) rows.add(listOf("B", "Y"))
        val ds = makeDataset(listOf("row", "col"), rows)
        val result = chiSquareTest(ds, 0, 1)
        assertEquals(2, result.expected.size)
        assertEquals(2, result.expected[0].size)
        assertEquals(10.0, result.expected[0][0], 0.01, "期望频数应为 10")
        assertEquals(10.0, result.expected[0][1], 0.01)
        assertEquals(10.0, result.expected[1][0], 0.01)
        assertEquals(10.0, result.expected[1][1], 0.01)
    }

    @Test
    fun `卡方检验 确定性 同一输入两次一致`() {
        val rows = mutableListOf<List<String>>()
        for (i in 1..15) rows.add(listOf("A", "X"))
        for (i in 1..5) rows.add(listOf("A", "Y"))
        for (i in 1..5) rows.add(listOf("B", "X"))
        for (i in 1..15) rows.add(listOf("B", "Y"))
        val ds = makeDataset(listOf("row", "col"), rows)
        val r1 = chiSquareTest(ds, 0, 1)
        val r2 = chiSquareTest(ds, 0, 1)
        assertEquals(r1.chiSquare, r2.chiSquare)
        assertEquals(r1.pValue, r2.pValue)
        assertEquals(r1.degreesOfFreedom, r2.degreesOfFreedom)
        assertEquals(r1.significant, r2.significant)
    }

    @Test
    fun `卡方检验 标签含行和列`() {
        val rows = listOf(
            listOf("Alpha", "Red"),
            listOf("Beta", "Blue"),
            listOf("Alpha", "Blue"),
            listOf("Beta", "Red"),
        )
        val ds = makeDataset(listOf("row", "col"), rows)
        val result = chiSquareTest(ds, 0, 1)
        assertTrue("Alpha" in result.rowLabels)
        assertTrue("Beta" in result.rowLabels)
        assertTrue("Red" in result.colLabels)
        assertTrue("Blue" in result.colLabels)
    }

    @Test
    fun `卡方检验 observed 与 expected 维度一致`() {
        val rows = mutableListOf<List<String>>()
        for (i in 1..10) rows.add(listOf("A", "X"))
        for (i in 1..5) rows.add(listOf("A", "Y"))
        for (i in 1..5) rows.add(listOf("B", "X"))
        for (i in 1..10) rows.add(listOf("B", "Y"))
        for (i in 1..3) rows.add(listOf("C", "Z"))
        val ds = makeDataset(listOf("row", "col"), rows)
        val result = chiSquareTest(ds, 0, 1)
        assertEquals(result.observed.size, result.expected.size)
        for (i in result.observed.indices) {
            assertEquals(result.observed[i].size, result.expected[i].size)
        }
    }

    private fun makeDataset(columns: List<String>, rows: List<List<String>>): Dataset {
        val types = columns.mapIndexed { i, _ ->
            val numericCount = rows.count { Csv.parseNumber(it[i]) != null }
            if (numericCount > 0 && numericCount.toDouble() / rows.size >= 0.85) ColumnType.NUMBER
            else ColumnType.TEXT
        }
        return Dataset(
            name = "test",
            importedAt = 1,
            columns = columns,
            rows = rows,
            columnTypes = types,
        )
    }
}
