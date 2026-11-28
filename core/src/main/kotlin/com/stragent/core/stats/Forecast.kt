package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset

/**
 * 时序预测 —— D10 阶段 5 第一落地。
 *
 * 两种方法，纯 Kotlin 零依赖，确定性（无随机成分）：
 *  - SES（简单指数平滑）：适合无趋势的平稳序列
 *  - Holt 线性趋势：适合有趋势的序列
 *
 * 自动选择：两种都跑，取 SSE 更小者。
 *
 * 诚实边界（写进工具描述，不冒称）：
 *  - Holt-Winters 为加法模型（乘法模型在数据含零或负值时不稳定）
 *  - 季节周期 m 需调用方指定或默认 12（月度）；数据不足 2 个完整周期时回落 Holt
 *  - 不做 ARIMA（需要差分阶数判断与自相关分析，纯 JVM 无统计库时实现成本高）
 *  - 置信区间基于残差标准误差的正态近似（1.96σ = 95%），不是精确预测区间
 *  - 数据点 < 3 时拒绝预测（参数估不出来）
 */

data class ForecastPoint(
    val period: Int,          // 预测第几期（1-based）
    val value: Double,        // 预测值
    val lower: Double,        // 95% 置信下界
    val upper: Double,        // 95% 置信上界
)

data class ForecastResult(
    val method: String,       // "ses" | "holt" | "holtwinters" | "none"
    val alpha: Double,        // 平滑参数
    val beta: Double?,        // 趋势平滑参数（仅 Holt / Holt-Winters）
    val gamma: Double? = null,// 季节平滑参数（仅 Holt-Winters）
    val sse: Double,          // 样本内 SSE（模型选择依据）
    val rmse: Double,         // 样本内 RMSE
    val dataPoints: Int,      // 输入数据点数
    val horizon: Int,         // 预测期数
    val history: List<Double>,// 输入序列（去空值后）
    val forecast: List<ForecastPoint>,
)

private const val MIN_DATA_POINTS = 3
private const val MAX_HORIZON = 24
private const val DEFAULT_HORIZON = 3
private const val Z_95 = 1.96

/**
 * 从 Dataset 提取时序数值。
 *
 * @param valueIndex 值列索引
 * @param dateIndex 日期列索引（-1 = 按行序）
 * @return 按日期排序后的数值列表（null 值跳过）
 */
fun extractTimeSeries(dataset: Dataset, valueIndex: Int, dateIndex: Int = -1): List<Double> {
    val pairs = mutableListOf<Pair<String?, Double>>()
    for (row in dataset.rows) {
        val raw = row.getOrNull(valueIndex) ?: continue
        val num = Csv.parseNumber(raw) ?: continue
        if (!num.isFinite()) continue
        val dateKey = if (dateIndex >= 0) monthKey(row.getOrNull(dateIndex) ?: "") else null
        pairs.add(dateKey to num)
    }
    // 有日期就按日期排序，否则按原序
    return if (dateIndex >= 0 && pairs.any { it.first != null }) {
        pairs.sortedBy { it.first ?: "" }.map { it.second }
    } else {
        pairs.map { it.second }
    }
}

/**
 * 简单指数平滑（SES）。
 *
 * F(t+1) = α * Y(t) + (1-α) * F(t)
 * α 通过网格搜索（0.01 步长）最小化 SSE。
 */
fun simpleExponentialSmoothing(values: List<Double>): Pair<Double, ForecastResult>? {
    if (values.size < MIN_DATA_POINTS) return null

    var bestAlpha = 0.3
    var bestSse = Double.MAX_VALUE

    // 网格搜索最优 α
    var alpha = 0.01
    while (alpha <= 1.0) {
        val sse = sesSse(values, alpha)
        if (sse < bestSse) {
            bestSse = sse
            bestAlpha = alpha
        }
        alpha += 0.01
    }
    // 浮点累积误差修正
    bestAlpha = (Math.round(bestAlpha * 100) / 100.0)

    val fitted = sesFitted(values, bestAlpha)
    val residuals = values.zip(fitted).map { (a, b) -> a - b }
    val rmse = Math.sqrt(residuals.map { it * it }.sum() / residuals.size)
    return bestAlpha to ForecastResult(
        method = "ses",
        alpha = bestAlpha,
        beta = null,
        sse = bestSse,
        rmse = Math.round(rmse * 10000) / 10000.0,
        dataPoints = values.size,
        horizon = 0,
        history = values,
        forecast = emptyList(),
    )
}

private fun sesSse(values: List<Double>, alpha: Double): Double {
    if (values.isEmpty()) return Double.MAX_VALUE
    var forecast = values[0]
    var sse = 0.0
    for (i in 1 until values.size) {
        val err = values[i] - forecast
        sse += err * err
        forecast = alpha * values[i] + (1 - alpha) * forecast
    }
    return sse
}

private fun sesFitted(values: List<Double>, alpha: Double): List<Double> {
    val fitted = mutableListOf<Double>()
    if (values.isEmpty()) return fitted
    var forecast = values[0]
    fitted.add(forecast)
    for (i in 1 until values.size) {
        forecast = alpha * values[i - 1] + (1 - alpha) * forecast
        fitted.add(forecast)
    }
    return fitted
}

/**
 * Holt 线性趋势法。
 *
 * Level:  L(t) = α*Y(t) + (1-α)*(L(t-1) + T(t-1))
 * Trend:  T(t) = β*(L(t) - L(t-1)) + (1-β)*T(t-1)
 * F(t+h) = L(t) + h*T(t)
 *
 * α, β 通过网格搜索最小化 SSE。
 */
fun holtLinearTrend(values: List<Double>): Pair<Pair<Double, Double>, ForecastResult>? {
    if (values.size < MIN_DATA_POINTS) return null

    var bestAlpha = 0.3
    var bestBeta = 0.1
    var bestSse = Double.MAX_VALUE

    var alpha = 0.05
    while (alpha <= 1.0) {
        var beta = 0.05
        while (beta <= 1.0) {
            val sse = holtSse(values, alpha, beta)
            if (sse < bestSse) {
                bestSse = sse
                bestAlpha = alpha
                bestBeta = beta
            }
            beta += 0.05
        }
        alpha += 0.05
    }
    bestAlpha = (Math.round(bestAlpha * 100) / 100.0)
    bestBeta = (Math.round(bestBeta * 100) / 100.0)

    val fitted = holtFitted(values, bestAlpha, bestBeta)
    val residuals = values.zip(fitted).map { (a, b) -> a - b }
    val rmse = Math.sqrt(residuals.map { it * it }.sum() / residuals.size)

    return (bestAlpha to bestBeta) to ForecastResult(
        method = "holt",
        alpha = bestAlpha,
        beta = bestBeta,
        sse = bestSse,
        rmse = Math.round(rmse * 10000) / 10000.0,
        dataPoints = values.size,
        horizon = 0,
        history = values,
        forecast = emptyList(),
    )
}

private fun holtSse(values: List<Double>, alpha: Double, beta: Double): Double {
    if (values.size < 2) return Double.MAX_VALUE
    var level = values[0]
    var trend = values[1] - values[0]
    var sse = 0.0
    for (i in 1 until values.size) {
        val prevLevel = level
        val forecast = prevLevel + trend
        val err = values[i] - forecast
        sse += err * err
        level = alpha * values[i] + (1 - alpha) * (prevLevel + trend)
        trend = beta * (level - prevLevel) + (1 - beta) * trend
    }
    return sse
}

private fun holtFitted(values: List<Double>, alpha: Double, beta: Double): List<Double> {
    val fitted = mutableListOf<Double>()
    if (values.size < 2) return fitted
    var level = values[0]
    var trend = values[1] - values[0]
    fitted.add(level)
    for (i in 1 until values.size) {
        val prevLevel = level
        fitted.add(prevLevel + trend)
        level = alpha * values[i] + (1 - alpha) * (prevLevel + trend)
        trend = beta * (level - prevLevel) + (1 - beta) * trend
    }
    return fitted
}

/**
 * Holt-Winters 三次指数平滑（加法模型，季节性预测）。
 *
 * Level:   L(t) = α*(Y(t) - S(t-m)) + (1-α)*(L(t-1) + T(t-1))
 * Trend:   T(t) = β*(L(t) - L(t-1)) + (1-β)*T(t-1)
 * Season:  S(t) = γ*(Y(t) - L(t)) + (1-γ)*S(t-m)
 * F(t+h) = L(t) + h*T(t) + S(t - m + ((h-1) % m) + 1)
 *
 * α, β, γ 通过网格搜索最小化 SSE。
 * 需要至少 2 个完整季节周期（values.size >= 2 * seasonPeriod）。
 *
 * @param values 时序数据
 * @param seasonPeriod 季节周期（如月度=12，季度=4）
 * @return ((alpha, beta, gamma), ForecastResult) 或 null（数据不足）
 */
fun holtWinters(
    values: List<Double>,
    seasonPeriod: Int = 12,
): Pair<Triple<Double, Double, Double>, ForecastResult>? {
    val m = seasonPeriod.coerceIn(2, 24)
    if (values.size < 2 * m) return null

    // 初始季节指数：用前几个周期的同期均值与整体均值之差
    val overallMean = values.take(m * (values.size / m)).sum() / (m * (values.size / m))
    val seasonals = DoubleArray(m) { idx ->
        val seasonalValues = (idx until values.size step m).map { values[it] }
        seasonalValues.average() - overallMean
    }

    var bestAlpha = 0.3
    var bestBeta = 0.1
    var bestGamma = 0.1
    var bestSse = Double.MAX_VALUE

    var alpha = 0.1
    while (alpha <= 1.0) {
        var beta = 0.1
        while (beta <= 1.0) {
            var gamma = 0.1
            while (gamma <= 1.0) {
                val sse = hwSse(values, alpha, beta, gamma, m, seasonals)
                if (sse < bestSse) {
                    bestSse = sse
                    bestAlpha = alpha
                    bestBeta = beta
                    bestGamma = gamma
                }
                gamma += 0.1
            }
            beta += 0.1
        }
        alpha += 0.1
    }
    bestAlpha = (Math.round(bestAlpha * 100) / 100.0)
    bestBeta = (Math.round(bestBeta * 100) / 100.0)
    bestGamma = (Math.round(bestGamma * 100) / 100.0)

    val fitted = hwFitted(values, bestAlpha, bestBeta, bestGamma, m, seasonals)
    val residuals = values.zip(fitted).map { (a, b) -> a - b }
    val rmse = Math.sqrt(residuals.map { it * it }.sum() / residuals.size)

    return Triple(bestAlpha, bestBeta, bestGamma) to ForecastResult(
            method = "holtwinters",
            alpha = bestAlpha,
            beta = bestBeta,
            gamma = bestGamma,
            sse = bestSse,
            rmse = Math.round(rmse * 10000) / 10000.0,
            dataPoints = values.size,
            horizon = 0,
            history = values,
            forecast = emptyList(),
        )
}

private fun hwSse(
    values: List<Double>,
    alpha: Double,
    beta: Double,
    gamma: Double,
    m: Int,
    initialSeasonals: DoubleArray,
): Double {
    val n = values.size
    if (n < 2 * m) return Double.MAX_VALUE

    val season = initialSeasonals.copyOf()
    var level = values[0] - season[0]
    var trend = values[1] - values[0]

    var sse = 0.0
    for (t in 1 until n) {
        val seasonalIdx = t % m
        val forecast = level + trend + season[seasonalIdx]
        val err = values[t] - forecast
        sse += err * err
        val prevLevel = level
        level = alpha * (values[t] - season[seasonalIdx]) + (1 - alpha) * (prevLevel + trend)
        trend = beta * (level - prevLevel) + (1 - beta) * trend
        season[seasonalIdx] = gamma * (values[t] - level) + (1 - gamma) * season[seasonalIdx]
    }
    return sse
}

private fun hwFitted(
    values: List<Double>,
    alpha: Double,
    beta: Double,
    gamma: Double,
    m: Int,
    initialSeasonals: DoubleArray,
): List<Double> {
    val n = values.size
    if (n < 2 * m) return emptyList()

    val season = initialSeasonals.copyOf()
    var level = values[0] - season[0]
    var trend = values[1] - values[0]
    val fitted = mutableListOf<Double>()
    fitted.add(level + trend + season[0])

    for (t in 1 until n) {
        val seasonalIdx = t % m
        val prevLevel = level
        val f = prevLevel + trend + season[seasonalIdx]
        fitted.add(f)
        level = alpha * (values[t] - season[seasonalIdx]) + (1 - alpha) * (prevLevel + trend)
        trend = beta * (level - prevLevel) + (1 - beta) * trend
        season[seasonalIdx] = gamma * (values[t] - level) + (1 - gamma) * season[seasonalIdx]
    }
    return fitted
}

private fun hwFinalState(
    values: List<Double>,
    alpha: Double,
    beta: Double,
    gamma: Double,
    m: Int,
    @Suppress("UNUSED_PARAMETER") initialSeasonals: DoubleArray,
): Triple<Double, Double, DoubleArray> {
    val n = values.size
    if (n < 2 * m) return Triple(values.lastOrNull() ?: 0.0, 0.0, initialSeasonals)

    // 从数据计算初始季节指数
    val cyclesAvail = n / m
    val overallMean = values.take(m * cyclesAvail).sum() / (m * cyclesAvail)
    val initSeasons = DoubleArray(m) { idx ->
        (idx until n step m).map { values[it] }.average() - overallMean
    }

    val season = initSeasons.copyOf()
    var level = values[0] - season[0]
    var trend = values[1] - values[0]

    for (t in 1 until n) {
        val seasonalIdx = t % m
        val prevLevel = level
        level = alpha * (values[t] - season[seasonalIdx]) + (1 - alpha) * (prevLevel + trend)
        trend = beta * (level - prevLevel) + (1 - beta) * trend
        season[seasonalIdx] = gamma * (values[t] - level) + (1 - gamma) * season[seasonalIdx]
    }

    return Triple(level, trend, season.copyOf())
}

/**
 * 自动预测：跑三种方法取 SSE 更小者，生成 horizon 期预测 + 置信区间。
 */
fun autoForecast(
    values: List<Double>,
    horizon: Int = DEFAULT_HORIZON,
    seasonPeriod: Int = 12,
): ForecastResult {
    val h = horizon.coerceIn(1, MAX_HORIZON)
    if (values.size < MIN_DATA_POINTS) {
        return ForecastResult(
            method = "none",
            alpha = 0.0,
            beta = null,
            gamma = null,
            sse = Double.NaN,
            rmse = Double.NaN,
            dataPoints = values.size,
            horizon = h,
            history = values,
            forecast = emptyList(),
        )
    }

    val sesResult = simpleExponentialSmoothing(values)
    val holtResult = holtLinearTrend(values)
    val hwResult = holtWinters(values, seasonPeriod)

    var method = "none"
    var alpha = 0.0
    var beta: Double? = null
    var gamma: Double? = null
    var sse = Double.NaN
    var rmse = Double.NaN

    // 三选一：SSE 最小者胜出
    data class Candidate(val name: String, val sse: Double, val alpha: Double, val beta: Double?, val gamma: Double?, val rmse: Double)
    val candidates = listOfNotNull(
        sesResult?.let { Candidate("ses", it.second.sse, it.second.alpha, null, null, it.second.rmse) },
        holtResult?.let { Candidate("holt", it.second.sse, it.second.alpha, it.second.beta, null, it.second.rmse) },
        hwResult?.let { Candidate("holtwinters", it.second.sse, it.second.alpha, it.second.beta, it.second.gamma, it.second.rmse) },
    )
    if (candidates.isNotEmpty()) {
        val best = candidates.minBy { it.sse }
        method = best.name
        alpha = best.alpha
        beta = best.beta
        gamma = best.gamma
        sse = best.sse
        rmse = best.rmse
    }

    if (method == "none") {
        return ForecastResult(method, alpha, beta, null, sse, rmse, values.size, h, values, emptyList())
    }

    // 生成预测
    val forecast = when (method) {
        "ses" -> {
            // SES 最后一期拟合值就是下期预测
            val fitted = sesFitted(values, alpha)
            val lastForecast = fitted.last()
            val sigma = if (rmse.isFinite() && rmse > 0) rmse else 0.01
            (1..h).map { period ->
                val v = lastForecast
                ForecastPoint(
                    period = period,
                    value = Math.round(v * 10000) / 10000.0,
                    lower = Math.round((v - Z_95 * sigma) * 10000) / 10000.0,
                    upper = Math.round((v + Z_95 * sigma) * 10000) / 10000.0,
                )
            }
        }
        "holt" -> {
            val (finalLevel, finalTrend) = holtFinalState(values, alpha, beta ?: 0.0)
            val sigma = if (rmse.isFinite() && rmse > 0) rmse else 0.01
            (1..h).map { period ->
                val v = finalLevel + period * finalTrend
                ForecastPoint(
                    period = period,
                    value = Math.round(v * 10000) / 10000.0,
                    lower = Math.round((v - Z_95 * sigma) * 10000) / 10000.0,
                    upper = Math.round((v + Z_95 * sigma) * 10000) / 10000.0,
                )
            }
        }
        "holtwinters" -> {
            val m = seasonPeriod.coerceIn(2, 24)
            val (finalLevel, finalTrend, finalSeasons) = hwFinalState(values, alpha, beta ?: 0.0, gamma ?: 0.0, m, DoubleArray(m))
            val sigma = if (rmse.isFinite() && rmse > 0) rmse else 0.01
            (1..h).map { period ->
                val seasonIdx = (period - 1) % m
                val v = finalLevel + period * finalTrend + finalSeasons[seasonIdx]
                ForecastPoint(
                    period = period,
                    value = Math.round(v * 10000) / 10000.0,
                    lower = Math.round((v - Z_95 * sigma) * 10000) / 10000.0,
                    upper = Math.round((v + Z_95 * sigma) * 10000) / 10000.0,
                )
            }
        }
        else -> emptyList()
    }

    return ForecastResult(
        method = method,
        alpha = alpha,
        beta = beta,
        gamma = gamma,
        sse = Math.round(sse * 10000) / 10000.0,
        rmse = rmse,
        dataPoints = values.size,
        horizon = h,
        history = values,
        forecast = forecast,
    )
}

private fun holtFinalState(values: List<Double>, alpha: Double, beta: Double): Pair<Double, Double> {
    if (values.size < 2) return (values.firstOrNull() ?: 0.0) to 0.0
    var level = values[0]
    var trend = values[1] - values[0]
    for (i in 1 until values.size) {
        val prevLevel = level
        level = alpha * values[i] + (1 - alpha) * (prevLevel + trend)
        trend = beta * (level - prevLevel) + (1 - beta) * trend
    }
    return level to trend
}

/**
 * 找数值列索引（工具入口用）。
 */
fun findValueColumn(dataset: Dataset, preferred: String? = null): Int {
    if (preferred != null) {
        val idx = dataset.columns.indexOfFirst { it.equals(preferred, ignoreCase = true) }
        if (idx >= 0 && dataset.columnTypes[idx] == ColumnType.NUMBER) return idx
    }
    // 回退：第一个数值列
    return dataset.columns.indices.firstOrNull { dataset.columnTypes[it] == ColumnType.NUMBER } ?: -1
}

/**
 * 找日期列索引（工具入口用）。
 */
fun findDateColumn(dataset: Dataset, preferred: String? = null): Int {
    if (preferred != null) {
        val idx = dataset.columns.indexOfFirst { it.equals(preferred, ignoreCase = true) }
        if (idx >= 0) return idx
    }
    return findMonthIndex(dataset)
}
