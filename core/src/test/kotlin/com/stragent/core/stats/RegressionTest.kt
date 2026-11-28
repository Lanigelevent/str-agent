package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * 线性回归单元测试（D10 阶段 5 统计方法扩充）。
 *
 * 验证维度：
 *  1. 完美线性序列：slope/intercept 精确、R²=1、SSE≈0
 *  2. 弱相关序列：0 < R² < 1
 *  3. 负相关序列：slope < 0
 *  4. 确定性（同一输入两次调用结果一致）
 *  5. 退化路径（不足 3 点、X 全相同、X/Y 长度不一致）
 *  6. findNumericColumn 查找逻辑
 *  7. linearRegression 从 Dataset 提取
 */
class RegressionTest {

    @Test
    fun `完美线性序列 slope 与 intercept 精确`() {
        // y = 2 + 3*x
        val xs = (1..10).map { it.toDouble() }
        val ys = xs.map { 2.0 + 3.0 * it }
        val result = regress(xs, ys, "x", "y")
        assertEquals(3.0, result.slope, 0.0001, "完美线性序列 slope 应为 3")
        assertEquals(2.0, result.intercept, 0.0001, "完美线性序列 intercept 应为 2")
    }

    @Test
    fun `完美线性序列 R-squared 等于 1`() {
        val xs = (1..10).map { it.toDouble() }
        val ys = xs.map { 5.0 * it }
        val result = regress(xs, ys, "x", "y")
        assertEquals(1.0, result.rSquared, 0.0001, "完美线性序列 R² 应为 1")
        assertEquals(0.0, result.standardError, 0.001, "完美线性序列标准误差应≈0")
    }

    @Test
    fun `弱相关序列 R-squared 在 0 到 1 之间`() {
        val xs = (1..20).map { it.toDouble() }
        val ys = xs.map { it * 2.0 + (it % 5) * 10.0 - 25.0 }
        val result = regress(xs, ys, "x", "y")
        assertTrue(result.rSquared > 0.0, "R² 应 > 0")
        assertTrue(result.rSquared < 1.0, "R² 应 < 1（有噪声）")
    }

    @Test
    fun `负相关序列 slope 小于 0`() {
        val xs = (1..10).map { it.toDouble() }
        val ys = xs.map { 100.0 - 5.0 * it }
        val result = regress(xs, xs, "x", "x")
        // y = x：正相关
        assertEquals(1.0, result.slope, 0.0001)

        val result2 = regress(xs, ys, "x", "y")
        assertEquals(-5.0, result2.slope, 0.0001, "负相关序列 slope 应为 -5")
    }

    @Test
    fun `确定性 同一输入两次调用结果完全一致`() {
        val xs = (1..15).map { it.toDouble() }
        val ys = xs.map { it * 2.5 + 10.0 + (it % 3) * 1.5 }
        val r1 = regress(xs, ys, "x", "y")
        val r2 = regress(xs, ys, "x", "y")
        assertEquals(r1.slope, r2.slope)
        assertEquals(r1.intercept, r2.intercept)
        assertEquals(r1.rSquared, r2.rSquared)
        assertEquals(r1.standardError, r2.standardError)
        assertEquals(r1.tStatistic, r2.tStatistic)
        assertEquals(r1.pValue, r2.pValue)
        assertEquals(r1.predictions.size, r2.predictions.size)
    }

    @Test
    fun `不足 3 个数据点抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            regress(listOf(1.0, 2.0), listOf(3.0, 4.0), "x", "y")
        }
    }

    @Test
    fun `X 全相同抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            regress(
                listOf(5.0, 5.0, 5.0, 5.0, 5.0),
                listOf(1.0, 2.0, 3.0, 4.0, 5.0),
                "x", "y",
            )
        }
    }

    @Test
    fun `X 与 Y 长度不一致抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            regress(
                listOf(1.0, 2.0, 3.0),
                listOf(1.0, 2.0),
                "x", "y",
            )
        }
    }

    @Test
    fun `findNumericColumn 正确找到列`() {
        val ds = makeDataset(
            listOf("name", "age", "score"),
            listOf(
                listOf("Alice", "30", "85"),
                listOf("Bob", "25", "90"),
            ),
        )
        assertEquals(1, findNumericColumn(ds, "age"))
        assertEquals(2, findNumericColumn(ds, "score"))
    }

    @Test
    fun `findNumericColumn 大小写不敏感`() {
        val ds = makeDataset(
            listOf("Name", "Age"),
            listOf(listOf("A", "30"), listOf("B", "40")),
        )
        assertEquals(1, findNumericColumn(ds, "age"))
        assertEquals(1, findNumericColumn(ds, "AGE"))
    }

    @Test
    fun `findNumericColumn 列不存在返回 -1`() {
        val ds = makeDataset(
            listOf("name", "age"),
            listOf(listOf("A", "30")),
        )
        assertEquals(-1, findNumericColumn(ds, "height"))
    }

    @Test
    fun `findNumericColumn 非数值列返回 -2`() {
        val ds = makeDataset(
            listOf("name", "age"),
            listOf(listOf("A", "30"), listOf("B", "40")),
        )
        assertEquals(-2, findNumericColumn(ds, "name"))
    }

    @Test
    fun `linearRegression 从 Dataset 提取并跳过空值`() {
        val ds = makeDataset(
            listOf("x", "y"),
            listOf(
                listOf("1", "3"),
                listOf("2", ""),
                listOf("3", "9"),
                listOf("4", "abc"),
                listOf("5", "15"),
            ),
        )
        val result = linearRegression(ds, 0, 1)
        assertEquals(3, result.sampleSize, "应跳过空值与非数值")
        assertEquals(3.0, result.slope, 0.0001, "y=3x 的 slope 应为 3")
    }

    @Test
    fun `预测值与实际值接近（完美线性）`() {
        val xs = (1..10).map { it.toDouble() }
        val ys = xs.map { 2.0 * it + 1.0 }
        val result = regress(xs, ys, "x", "y")
        for ((x, predicted) in result.predictions) {
            val actual = 2.0 * x + 1.0
            assertEquals(actual, predicted, 0.001, "预测值应与实际值接近")
        }
    }

    @Test
    fun `p 值在 0 到 1 之间`() {
        val xs = (1..15).map { it.toDouble() }
        val ys = xs.map { it * 3.0 + 5.0 + (it % 4) * 2.0 }
        val result = regress(xs, ys, "x", "y")
        assertTrue(result.pValue >= 0.0, "p 值应 >= 0")
        assertTrue(result.pValue <= 1.0, "p 值应 <= 1")
    }

    @Test
    fun `样本量正确记录`() {
        val xs = (1..20).map { it.toDouble() }
        val ys = xs.map { it * 2.0 }
        val result = regress(xs, ys, "x", "y")
        assertEquals(20, result.sampleSize)
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
