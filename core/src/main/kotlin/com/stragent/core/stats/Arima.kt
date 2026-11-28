package com.stragent.core.stats

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ARIMA(p, d, q) 最小档 —— T-209（评审短板 P2：引擎形态开口）。
 *
 * 范围（D7 立项界定的最小诚实版，纯 JDK 零依赖、确定性）：
 *  - 差分阶数 d ∈ {0,1,2}；定阶 = 固定小网格 p∈{0,1,2}, q∈{0,1} 按 **AICc** 选优
 *    （诚实边界：网格搜索不是统计最优定阶）；
 *  - ARMA(p,q) 用 **Hannan-Rissanen 两阶段估计**：先长 AR Durbin-Levinson 拟合残差，
 *    再对 [1, w_{t-1..p}, e_{t-1..q}] 做普通最小二乘；
 *  - 预测区间 = ±1.96σ 的保守近似（与 Forecast.kt 的 SES 档同口径，非精确预测区间）。
 *
 * 诚实边界（不冒称）：
 *  - 不做自动阶数声称（网格是选优不是定阶推断）；不做季节 ARIMA；不做 MLE 完整估计；
 *  - 数据点 < 8 拒绝（参数估不出来）；常数序列诚实延续常数（order=(0,1,0)，σ²=0）；
 *  - 样本内 SSE 与 Forecast.kt 的 SES/Holt 档同口径（一步预测误差平方和），可直接同台比较。
 */
object Arima {

    data class ArimaFit(
        val p: Int,
        val d: Int,
        val q: Int,
        val phi: List<Double>,
        val theta: List<Double>,
        val intercept: Double,
        val sigma2: Double,
        val sse: Double,
        val bic: Double,
        val nObs: Int,
        /** 差分后序列（拟合目标 w） */
        val diffed: List<Double>,
        /** 差分序列上的一步预测残差（与 diffed 对齐的前导空缺记 NaN 槽位省略，起点 = max(p,q)+1） */
        val residuals: List<Double>,
    )

    private const val MIN_POINTS = 8
    private const val AICC_GUARD = 1e10

    fun fitGrid(series: List<Double>): ArimaFit {
        val values = series.filter { !it.isNaN() }
        require(values.size >= MIN_POINTS) {
            "ARIMA 需要至少 $MIN_POINTS 个数据点（当前 ${values.size}）；数据不足请用 SES（>=3 点）"  // lint:allow zh-first（T-209 用户面中文错误，D19/D21 语言定位）
        }
        // 趋势预检：|corr(y, t)| >= 0.9 的强线性趋势强制从 d=1 起网格
        // （φ≈1 的 AR(1) 会吸收趋势导致跨 d 失配——T-209 测试实证）
        var dStart = 0
        run {
            val n = values.size
            val mean = values.average()
            var st = 0.0; var sy = 0.0; var stt = 0.0; var sty = 0.0
            for (i in 0 until n) {
                st += i; sy += values[i]; stt += (i * i).toDouble(); sty += i * values[i]
            }
            val cov = sty - n * (st / n) * mean
            val vt = stt - n * (st / n) * (st / n)
            val vy = values.map { it * it }.sum() - n * mean * mean
            if (vt > 0 && vy > 0 && Math.abs(cov / Math.sqrt(vt * vy)) >= 0.9) dStart = 1
        }
        if (values.all { it == values[0] }) {
            val w = values.drop(1).map { 0.0 }
            return ArimaFit(
                p = 0, d = 1, q = 0, phi = emptyList(), theta = emptyList(), intercept = 0.0,
                sigma2 = 0.0, sse = 0.0, bic = 0.0, nObs = w.size,
                diffed = w, residuals = w.map { 0.0 },
            )
        }
        var best: ArimaFit? = null
        for (d in dStart..2) {
            val w = diffN(values, d)
            if (w.size < MIN_POINTS) continue
            for (p in 0..2) {
                for (q in 0..1) {
                    val fit = fitArmaHr(w, p, q) ?: continue
                    val candidate = ArimaFit(
                        p = p, d = d, q = q, phi = fit.phi, theta = fit.theta,
                        intercept = fit.intercept, sigma2 = fit.sigma2, sse = fit.sse,
                        bic = fit.bic, nObs = w.size, diffed = w, residuals = fit.residuals,
                    )
                    val cur = best
                    if (cur == null || candidate.bic < cur.bic - 1e-12) best = candidate
                }
            }
        }
        return best ?: run {
            // 全网格失败回落 AR(1)（d=0）——确定性保底，理论不应触达
            val w = diffN(values, 0)
            val fit = fitArmaHr(w, 1, 0) ?: error("ARIMA 拟合失败：序列含 NaN 或长度不足")  // lint:allow zh-first（T-209 用户面中文错误，D19/D21 语言定位）
            ArimaFit(1, dStart, 0, fit.phi, fit.theta, fit.intercept, fit.sigma2, fit.sse, fit.bic, w.size, w, fit.residuals)
        }
    }

    /** HR 两阶段拟合；失败（矩阵奇异/长度不足）返回 null 由网格跳过 */
    internal fun fitArmaHr(w: List<Double>, p: Int, q: Int): FittedArma? {
        val n = w.size
        // 零方差差分序列（完美线性趋势差分后为常数）：诚实返回完美拟合
        // （BIC=-1e9 必然胜出；σ²=0 → 预测区间退化为点值——确定性数据诚实口径）
        if (w.all { it == w[0] }) {
            return FittedArma(emptyList(), emptyList(), w[0], 0.0, 0.0, -1e9, List(n) { 0.0 })
        }
        val start = maxOf(p, q) + 1
        if (n - start < p + q + 2) return null
        // 阶段一：长 AR Durbin-Levinson 拟合残差
        val pStar = minOf(16, n / 4)
        if (pStar < 1) return null
        val dl = durbinLevinson(w, pStar) ?: return null
        val (phiStar, _, residStar) = dl
        // 阶段二：OLS 设计矩阵 [1, w_{t-1..t-p}, e_{t-1..t-q}]
        val kCol = 1 + p + q
        val rows = n - start
        // 末列放 y
        val X = Array(rows) { DoubleArray(kCol + 1) }
        var rowIdx = 0
        for (t in start until n) {
            var c = 0
            X[rowIdx][c++] = 1.0
            for (i in 1..p) X[rowIdx][c++] = w[t - i]
            for (j in 1..q) X[rowIdx][c++] = residStar[t - j]
            X[rowIdx][kCol] = w[t]
            rowIdx++
        }
        val beta = olsSolve(X, rows, kCol) ?: return null
        val intercept = beta[0]
        val phi = beta.slice(1..p)
        val theta = beta.slice(p + 1..p + q)
        var sse = 0.0
        val residuals = DoubleArray(n) { Double.NaN }
        rowIdx = 0
        for (t in start until n) {
            var pred = intercept
            for (i in 1..p) pred += phi[i - 1] * w[t - i]
            for (j in 1..q) pred += theta[j - 1] * residStar[t - j]
            val e = w[t] - pred
            residuals[t] = e
            sse += e * e
            rowIdx++
        }
        val sigma2 = sse / rows
        if (!(sigma2 > 0.0) || sigma2.isNaN() || sigma2.isInfinite()) return null
        val k = p + q + 2.0
        // BIC（一致性准则）：比 AICc 更偏向简约模型——白噪声不虚增阶数
        val bic = rows * ln(sigma2) + k * ln(rows.toDouble())
        return FittedArma(phi, theta, intercept, sigma2, sse, bic, residuals.toList())
    }

    internal data class FittedArma(
        val phi: List<Double>,
        val theta: List<Double>,
        val intercept: Double,
        val sigma2: Double,
        val sse: Double,
        val bic: Double,
        val residuals: List<Double>,
    )

    /** d 阶差分 */
    internal fun diffN(x: List<Double>, d: Int): List<Double> {
        var cur = x
        repeat(d) {
            cur = (1 until cur.size).map { cur[it] - cur[it - 1] }
        }
        return cur
    }

    /**
     * Durbin-Levinson 递推：返回 (AR(pStar) 系数, 新息方差 ν, 一步预测残差序列)。
     * 残差前 pStar 个位置不可靠（初始化期），调用方自行取舍。
     */
    internal fun durbinLevinson(x: List<Double>, pStar: Int): Triple<List<Double>, Double, List<Double>>? {
        val n = x.size
        if (n <= pStar + 1) return null
        val mean = x.average()
        val xc = x.map { it - mean }
        val gamma = DoubleArray(pStar + 1)
        for (k in 0..pStar) {
            var s = 0.0
            for (t in 0 until n - k) s += xc[t] * xc[t + k]
            gamma[k] = s / n
        }
        if (gamma[0] <= 0.0) return null
        val phi = Array(pStar + 1) { DoubleArray(pStar + 1) }
        var nu = gamma[0]
        for (k in 1..pStar) {
            var num = gamma[k]
            for (j in 1 until k) num -= phi[k - 1][j] * gamma[k - j]
            val denom = nu
            if (abs(denom) < 1e-12) return null
            val phiK = num / denom
            phi[k][k] = phiK
            for (j in 1 until k) phi[k][j] = phi[k - 1][j] - phiK * phi[k - 1][k - j]
            nu *= (1.0 - phiK * phiK)
        }
        val coefs = (1..pStar).map { phi[pStar][it] }.toList()
        val residuals = DoubleArray(n) { Double.NaN }
        for (t in pStar until n) {
            var pred = 0.0
            for (j in 1..pStar) pred += coefs[j - 1] * xc[t - j]
            residuals[t] = xc[t] - pred
        }
        return Triple(coefs, nu, residuals.toList())
    }

    /** 正规方程 (XᵀX)β = Xᵀy，高斯主元消元；奇异返回 null */
    internal fun olsSolve(X: Array<DoubleArray>, rows: Int, kCol: Int): DoubleArray? {
        val k = kCol
        val a = Array(k) { DoubleArray(k + 1) }
        for (i in 0 until k) {
            for (j in 0 until k) {
                var s = 0.0
                for (r in 0 until rows) s += X[r][i] * X[r][j]
                a[i][j] = s
            }
            var s = 0.0
            for (r in 0 until rows) s += X[r][i] * X[r][k]
            a[i][k] = s
        }
        for (col in 0 until k) {
            var pivot = col
            for (r in col + 1 until k) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-10) return null
            if (pivot != col) {
                val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
            }
            for (r in 0 until k) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (cc in col..k) a[r][cc] -= f * a[col][cc]
            }
        }
        return DoubleArray(k) { i -> a[i][k] / a[i][i] }
    }

    /**
     * 生成原尺度预测点（点值 + 95% 区间，区间=±1.96σ 保守近似，与 SES 档同口径）。
     * 四舍五入口径与 Forecast.kt 一致（1e-4）。
     */
    fun forecastPoints(series: List<Double>, fit: ArimaFit, horizon: Int): List<ForecastPoint> {
        val h = horizon.coerceIn(1, 24)
        val w = fit.diffed
        val start = maxOf(fit.p, fit.q) + 1
        // 尾部上下文：最近 max(p,q) 个 w 与残差（残差空缺以 0 兜底）
        val histW = ArrayList<Double>()
        val histE = ArrayList<Double>()
        var i = w.size - 1
        var taken = 0
        while (i >= 0 && taken < maxOf(fit.p, fit.q)) {
            histW.add(0, w[i])
            val r = fit.residuals.getOrNull(i)
            histE.add(0, if (r != null && !r.isNaN()) r else 0.0)
            i--; taken++
        }
        val out = mutableListOf<ForecastPoint>()
        var prevW = histW.toList()
        var prevE = histE.toList()
        val sigma = if (fit.sigma2 > 0.0) sqrt(fit.sigma2) else 0.0
        // 原尺度还原锚点（标量态；d=1 锚最近值，d=2 锚最近值+最近一阶差分+累计二阶外推）
        var anchor1 = series.lastOrNull() ?: 0.0
        var anchor2Level = series.takeLast(2).let { if (it.size == 2) it[1] else 0.0 }
        var anchor2Diff = series.takeLast(2).let { if (it.size == 2) it[1] - it[0] else 0.0 }
        var cumSecond = 0.0
        for (step in 1..h) {
            var pred = fit.intercept
            for (j in 1..fit.p) {
                val idx = prevW.size - j
                pred += fit.phi[j - 1] * (prevW.getOrNull(idx) ?: 0.0)
            }
            for (j in 1..fit.q) {
                val idx = prevE.size - j
                pred += fit.theta[j - 1] * (prevE.getOrNull(idx) ?: 0.0)
            }
            prevW = prevW + pred
            prevE = prevE + 0.0
            val value = when (fit.d) {
                0 -> pred
                1 -> {
                    anchor1 += pred
                    anchor1
                }
                else -> {
                    cumSecond += pred
                    anchor2Level + anchor2Diff + cumSecond
                }
            }
            out.add(
                ForecastPoint(
                    period = step,
                    value = round4(value),
                    lower = round4(value - Z95 * sigma),
                    upper = round4(value + Z95 * sigma),
                ),
            )
        }
        return out
    }

    private const val Z95 = 1.96

    private fun round4(v: Double): Double = Math.round(v * 10000) / 10000.0

    /** 供 autoForecast 使用的样本内 SSE（一步预测误差平方和，与 SES/Holt 同口径） */
    fun inSampleSse(series: List<Double>, fit: ArimaFit): Double = fit.sse
}
