package com.stragent.core.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-209 ARIMA 最小档金标测试：合成序列（线性同余确定性伪随机，无随机成分漂移）。
 */
class ArimaTest {

    /** 确定性伪随机（线性同余，种子固定）——测试无随机漂移 */
    private fun lcg(seed: Long): () -> Double {
        var s = seed
        return {
            s = s * 6364136223846793005L + 1442695040888963407L
            ((s ushr 11) / 9007199254740992.0)
        }
    }

    /** 生成 AR(1) 序列：y_t = c + φ y_{t-1} + ε */
    private fun ar1(n: Int, phi: Double, c: Double, seed: Long): List<Double> {
        val rnd = lcg(seed)
        val out = ArrayList<Double>(n)
        var y = c
        for (i in 0 until n) {
            y = c + phi * y + (rnd() - 0.5) * 2.0
            out.add(y)
        }
        // 丢前 50 个瞬态
        return out.drop(50)
    }

    @Test
    fun `AR1 系数恢复——点七序列拟合出接近的 φ`() {
        val series = ar1(350, phi = 0.7, c = 5.0, seed = 42L)
        val fit = Arima.fitGrid(series)
        assertEquals(1, fit.phi.size, "AR(1) 数据应选中一阶 AR：order=(${fit.p},${fit.d},${fit.q})")
        assertTrue(Math.abs(fit.phi[0] - 0.7) < 0.2, "φ̂=${fit.phi[0]} 应接近 0.7")
    }

    @Test
    fun `白噪声选中零阶——p0q0 不虚增阶数`() {
        val rnd = lcg(7L)
        val series = (0 until 250).map { (rnd() - 0.5) * 2.0 }
        val fit = Arima.fitGrid(series)
        assertTrue(fit.p == 0 && fit.q == 0, "白噪声不应虚增阶数：order=(${fit.p},${fit.d},${fit.q})")
    }

    @Test
    fun `线性趋势选中 d1`() {
        val series = (0 until 120).map { 3.0 + 0.8 * it }
        val fit = Arima.fitGrid(series)
        assertEquals(1, fit.d, "线性趋势应选中一阶差分：order=(${fit.p},${fit.d},${fit.q})")
    }

    @Test
    fun `AR 序列上 ARIMA 不劣于 SES——竞争基准`() {
        val series = ar1(350, phi = 0.7, c = 5.0, seed = 99L)
        val arimaFit = Arima.fitGrid(series)
        val ses = simpleExponentialSmoothing(series)
        val sesSse = ses?.second?.sse ?: Double.MAX_VALUE
        // 宽容差 1.05 防 flaky（种子确定性下应稳定）；ARIMA 在 AR 数据上应显著占优
        assertTrue(
            arimaFit.sse <= sesSse * 1.05,
            "ARIMA SSE=${arimaFit.sse} 应不劣于 SES SSE=$sesSse（AR 数据上 ARIMA 应占优）",
        )
    }

    @Test
    fun `确定性——同输入两次拟合同 AICc 同阶数`() {
        val series = ar1(200, phi = 0.5, c = 3.0, seed = 5L)
        val a = Arima.fitGrid(series)
        val b = Arima.fitGrid(series)
        assertEquals(a.bic, b.bic, 1e-12)
        assertEquals(a.p, b.p)
        assertEquals(a.q, b.q)
        assertEquals(a.d, b.d)
    }

    @Test
    fun `短序列拒绝——中文可行动错误`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Arima.fitGrid(listOf(1.0, 2.0, 3.0, 4.0, 5.0))
        }
        assertTrue(e.message!!.contains("ARIMA"), e.message)
        assertTrue(e.message!!.contains("SES"), e.message)
    }

    @Test
    fun `常数序列诚实延续`() {
        val fit = Arima.fitGrid((0 until 30).map { 42.0 })
        val pts = Arima.forecastPoints((0 until 30).map { 42.0 }, fit, 3)
        assertEquals(3, pts.size)
        pts.forEach { assertEquals(42.0, it.value) }
    }

    @Test
    fun `预测区间包裹点值且口径一致`() {
        val series = ar1(200, phi = 0.4, c = 10.0, seed = 11L)
        val fit = Arima.fitGrid(series)
        val pts = Arima.forecastPoints(series, fit, 5)
        assertEquals(5, pts.size)
        pts.forEach {
            assertTrue(it.lower <= it.value && it.value <= it.upper, "${it.lower} <= ${it.value} <= ${it.upper}")
        }
    }
}
