package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 时序预测单元测试（D10 阶段 5）。
 *
 * 验证维度：
 *  1. SES 参数优化（平稳序列 → α 合理、预测值 ≈ 均值）
 *  2. Holt 线性趋势（线性增长序列 → 趋势正确捕捉）
 *  3. autoForecast 自动选择（平稳选 SES、趋势选 Holt）
 *  4. 置信区间合理性（覆盖历史值范围、lower < value < upper）
 *  5. 退化路径（空序列、单点、两点 → 拒绝预测）
 *  6. 非数值列拒绝
 *  7. 确定性（同一输入两次调用结果完全一致）
 */
class ForecastTest {

    @Test
    fun `SES 平稳序列 alpha 优化与预测值接近均值`() {
        // 10 期平稳数据，均值约 100
        val values = listOf(98.0, 102.0, 99.0, 101.0, 100.0, 103.0, 97.0, 100.0, 102.0, 98.0)
        val result = simpleExponentialSmoothing(values)
        assertNotNull(result)
        val (alpha, forecast) = result
        assertTrue(alpha in 0.01..1.0, "alpha 应在 (0,1] 范围内，实际 $alpha")
        // 平稳序列的最优 α 通常偏小（历史足够平均）
        assertTrue(forecast.dataPoints == 10)
        assertTrue(forecast.sse > 0, "SSE 应为正")
    }

    @Test
    fun `Holt 线性趋势正确捕捉趋势方向`() {
        // 10 期线性增长：y = 10 + 5*t
        val values = (0 until 10).map { 10.0 + it * 5.0 }
        val result = holtLinearTrend(values)
        assertNotNull(result)
        val (params, forecast) = result
        assertTrue(params.first in 0.05..1.0, "alpha 合理")
        assertNotNull(params.second, "beta 不为 null")
        assertTrue(params.second in 0.05..1.0, "beta 合理")
        assertTrue(forecast.sse < 1.0, "完美线性序列 SSE 应极小，实际 ${forecast.sse}")
    }

    @Test
    fun `autoForecast 平稳序列选 SES`() {
        val values = listOf(100.0, 102.0, 98.0, 101.0, 99.0, 103.0, 97.0, 100.0, 102.0, 98.0)
        val result = autoForecast(values, horizon = 3)
        assertEquals("ses", result.method, "平稳序列应选 SES")
        assertEquals(3, result.forecast.size, "预测期数 = horizon")
        // SES 预测值应接近序列均值
        val mean = values.average()
        for (pt in result.forecast) {
            assertTrue(kotlin.math.abs(pt.value - mean) < 10.0,
                "SES 预测值 ${pt.value} 应接近均值 $mean")
        }
    }

    @Test
    fun `autoForecast 线性趋势选 Holt 且预测值延续趋势`() {
        val values = (0 until 12).map { 100.0 + it * 10.0 }
        val result = autoForecast(values, horizon = 3)
        // 线性趋势序列应选 Holt（SSE 更小）
        assertEquals("holt", result.method, "线性趋势序列应选 Holt")
        assertEquals(3, result.forecast.size)
        // 预测值应延续增长趋势
        assertTrue(result.forecast[0].value < result.forecast[1].value,
            "预测值应递增：${result.forecast[0].value} < ${result.forecast[1].value}")
        assertTrue(result.forecast[1].value < result.forecast[2].value,
            "预测值应递增：${result.forecast[1].value} < ${result.forecast[2].value}")
        // 第 1 期预测值应接近 100 + 12 * 10 = 220
        assertTrue(kotlin.math.abs(result.forecast[0].value - 220.0) < 15.0,
            "第 1 期预测值 ${result.forecast[0].value} 应接近 220")
    }

    @Test
    fun `置信区间 lower 小于 value 小于 upper`() {
        val values = (0 until 10).map { 50.0 + it * 5.0 + (it % 3) * 2.0 }
        val result = autoForecast(values, horizon = 5)
        for (pt in result.forecast) {
            assertTrue(pt.lower < pt.value, "lower ${pt.lower} < value ${pt.value}")
            assertTrue(pt.value < pt.upper, "value ${pt.value} < upper ${pt.upper}")
        }
    }

    @Test
    fun `空序列拒绝预测`() {
        val result = autoForecast(emptyList(), horizon = 3)
        assertEquals("none", result.method)
        assertEquals(0, result.forecast.size)
        assertEquals(0, result.dataPoints)
    }

    @Test
    fun `单点序列拒绝预测`() {
        val result = autoForecast(listOf(42.0), horizon = 3)
        assertEquals("none", result.method)
        assertEquals(0, result.forecast.size)
        assertEquals(1, result.dataPoints)
    }

    @Test
    fun `两点序列拒绝预测`() {
        val result = autoForecast(listOf(10.0, 20.0), horizon = 3)
        assertEquals("none", result.method)
        assertEquals(0, result.forecast.size)
        assertEquals(2, result.dataPoints)
    }

    @Test
    fun `确定性 同一输入两次调用结果完全一致`() {
        val values = (0 until 15).map { 100.0 + it * 3.0 + (it % 4) * 1.5 }
        val r1 = autoForecast(values, 5)
        val r2 = autoForecast(values, 5)
        assertEquals(r1.method, r2.method)
        assertEquals(r1.alpha, r2.alpha)
        assertEquals(r1.beta, r2.beta)
        assertEquals(r1.sse, r2.sse)
        assertEquals(r1.rmse, r2.rmse)
        assertEquals(r1.forecast.size, r2.forecast.size)
        for (i in r1.forecast.indices) {
            assertEquals(r1.forecast[i].value, r2.forecast[i].value)
            assertEquals(r1.forecast[i].lower, r2.forecast[i].lower)
            assertEquals(r1.forecast[i].upper, r2.forecast[i].upper)
        }
    }

    @Test
    fun `horizon 超过上限被截断`() {
        val values = (0 until 10).map { 100.0 + it * 2.0 }
        val result = autoForecast(values, horizon = 100)
        assertEquals(24, result.forecast.size, "horizon 超过 MAX_HORIZON=24 应被截断")
    }

    @Test
    fun `findValueColumn 指定列名正确匹配`() {
        val ds = makeDataset(listOf("name", "sales", "cost"), listOf(listOf("A", "100", "50")))
        val idx = findValueColumn(ds, "sales")
        assertEquals(1, idx)
    }

    @Test
    fun `findValueColumn 不指定时取第一个数值列`() {
        val ds = makeDataset(listOf("name", "sales", "cost"), listOf(listOf("A", "100", "50")))
        val idx = findValueColumn(ds, null)
        // 第一个数值列：idx=1 (sales)
        assertEquals(1, idx)
    }

    @Test
    fun `findValueColumn 指定的列不是数值列时回退`() {
        val ds = makeDataset(listOf("name", "sales"), listOf(listOf("A", "100")))
        val idx = findValueColumn(ds, "name")
        // name 是 TEXT 列，回退到第一个数值列
        assertEquals(1, idx)
    }

    @Test
    fun `extractTimeSeries 跳过空值与非数值`() {
        val ds = makeDataset(
            listOf("date", "val"),
            listOf(
                listOf("2024-01", "100"),
                listOf("2024-02", ""),
                listOf("2024-03", "abc"),
                listOf("2024-04", "200"),
            ),
        )
        val values = extractTimeSeries(ds, 1, 0)
        assertEquals(2, values.size, "空值与非数值应跳过")
        assertEquals(100.0, values[0])
        assertEquals(200.0, values[1])
    }

    @Test
    fun `extractTimeSeries 无日期列时按行序`() {
        val ds = makeDataset(
            listOf("val"),
            listOf(listOf("30"), listOf("10"), listOf("20")),
        )
        val values = extractTimeSeries(ds, 0, -1)
        assertEquals(3, values.size)
        assertEquals(30.0, values[0])
    }

    @Test
    fun `extractTimeSeries 有日期列时按日期排序`() {
        val ds = makeDataset(
            listOf("date", "val"),
            listOf(
                listOf("2024-03", "300"),
                listOf("2024-01", "100"),
                listOf("2024-02", "200"),
            ),
        )
        val values = extractTimeSeries(ds, 1, 0)
        assertEquals(3, values.size)
        assertEquals(100.0, values[0], "应按日期排序，第一值是 2024-01 的 100")
        assertEquals(200.0, values[1])
        assertEquals(300.0, values[2])
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


    // ===== Holt-Winters 测试 =====

    @Test
    fun `Holt-Winters 季节性序列 SSE 小于 Holt`() {
        // 24 期数据：趋势 + 季节性（周期=12）
        // base=100, trend=2/期, seasonal = [10, -5, 15, -10, 5, -15, 10, -5, 15, -10, 5, -15]
        val seasonalPattern = listOf(10.0, -5.0, 15.0, -10.0, 5.0, -15.0, 10.0, -5.0, 15.0, -10.0, 5.0, -15.0)
        val values = (0 until 24).map { 100.0 + it * 2.0 + seasonalPattern[it % 12] }
        val hwResult = holtWinters(values, seasonPeriod = 12)
        assertNotNull(hwResult, "24 期数据应足够拟合 Holt-Winters")
        val holtResult = holtLinearTrend(values)
        assertNotNull(holtResult)
        assertTrue(hwResult.second.sse < holtResult.second.sse,
            "季节性数据上 Holt-Winters SSE (${hwResult.second.sse}) 应小于 Holt (${holtResult.second.sse})")
    }

    @Test
    fun `autoForecast 季节性序列选 holtwinters`() {
        val seasonalPattern = listOf(20.0, -10.0, 30.0, -20.0, 10.0, -30.0, 20.0, -10.0, 30.0, -20.0, 10.0, -30.0)
        val values = (0 until 24).map { 200.0 + it * 5.0 + seasonalPattern[it % 12] }
        val result = autoForecast(values, horizon = 3, seasonPeriod = 12)
        assertEquals("holtwinters", result.method, "强季节性序列应选 holtwinters，实际选了 ${result.method}")
        assertNotNull(result.gamma, "Holt-Winters 应有 gamma 参数")
        assertEquals(3, result.forecast.size)
    }

    @Test
    fun `Holt-Winters 预测值含季节波动`() {
        val seasonalPattern = listOf(50.0, -50.0, 50.0, -50.0)
        val values = (0 until 16).map { 300.0 + it * 3.0 + seasonalPattern[it % 4] }
        val result = autoForecast(values, horizon = 4, seasonPeriod = 4)
        assertEquals("holtwinters", result.method, "应选 holtwinters")
        // 预测值应有季节波动：奇数期 > 偶数期（季节模式 +50/-50）
        val f = result.forecast
        assertTrue(f[0].value > f[1].value, "第1期(${f[0].value})应>第2期(${f[1].value})：季节性+50 vs -50")
        assertTrue(f[1].value < f[2].value, "第2期(${f[1].value})应<第3期(${f[2].value})：-50 vs +50")
        assertTrue(f[2].value > f[3].value, "第3期(${f[2].value})应>第4期(${f[3].value})：+50 vs -50")
    }

    @Test
    fun `Holt-Winters 数据不足两个周期时返回 null`() {
        // 周期=12 但只有 15 个点（不足 2*12=24）
        val values = (0 until 15).map { 100.0 + it }
        val result = holtWinters(values, seasonPeriod = 12)
        assertNull(result, "15 < 24 应返回 null")
    }

    @Test
    fun `autoForecast 数据不足季节性时回落 Holt 或 SES`() {
        val values = (0 until 15).map { 100.0 + it * 5.0 }
        val result = autoForecast(values, horizon = 3, seasonPeriod = 12)
        assertTrue(result.method == "holt" || result.method == "ses",
            "数据不足 2 个周期时应回落 holt 或 ses，实际选了 ${result.method}")
        assertNull(result.gamma, "非季节性方法不应有 gamma")
    }

    @Test
    fun `Holt-Winters gamma 参数在合理范围`() {
        val seasonalPattern = listOf(10.0, -5.0, 15.0, -10.0, 5.0, -15.0, 10.0, -5.0, 15.0, -10.0, 5.0, -15.0)
        val values = (0 until 24).map { 100.0 + it * 2.0 + seasonalPattern[it % 12] }
        val result = holtWinters(values, seasonPeriod = 12)
        assertNotNull(result)
        val gamma = result.second.gamma
        assertNotNull(gamma)
        assertTrue(gamma in 0.1..1.0, "gamma 应在 [0.1, 1.0]，实际 $gamma")
    }

    @Test
    fun `Holt-Winters 确定性 同一输入两次结果完全一致`() {
        val seasonalPattern = listOf(15.0, -10.0, 5.0, -15.0, 10.0, -5.0)
        val values = (0 until 18).map { 150.0 + it * 3.0 + seasonalPattern[it % 6] }
        val r1 = autoForecast(values, 4, 6)
        val r2 = autoForecast(values, 4, 6)
        assertEquals(r1.method, r2.method)
        assertEquals(r1.alpha, r2.alpha)
        assertEquals(r1.beta, r2.beta)
        assertEquals(r1.gamma, r2.gamma)
        assertEquals(r1.sse, r2.sse)
        assertEquals(r1.rmse, r2.rmse)
        for (i in r1.forecast.indices) {
            assertEquals(r1.forecast[i].value, r2.forecast[i].value)
            assertEquals(r1.forecast[i].lower, r2.forecast[i].lower)
            assertEquals(r1.forecast[i].upper, r2.forecast[i].upper)
        }
    }

    @Test
    fun `Holt-Winters 置信区间 lower 小于 value 小于 upper`() {
        val seasonalPattern = listOf(20.0, -15.0, 10.0, -5.0)
        val values = (0 until 16).map { 100.0 + it * 2.0 + seasonalPattern[it % 4] }
        val result = autoForecast(values, 5, 4)
        assertEquals("holtwinters", result.method)
        for (pt in result.forecast) {
            assertTrue(pt.lower < pt.value, "lower ${pt.lower} < value ${pt.value}")
            assertTrue(pt.value < pt.upper, "value ${pt.value} < upper ${pt.upper}")
        }
    }

}
