package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset

/**
 * 线性回归 —— D10 阶段 5 统计方法扩充。
 *
 * 最小二乘法线性回归：y = a + b*x
 * 包含：斜率/截距/R²/标准误差/t 统计量/p 值。
 *
 * 纯 Kotlin 零依赖、确定性——与溯源清单纪律兼容。
 *
 * 诚实边界：
 *  - 只做一元线性回归（多元线性回归需要矩阵运算，纯 JVM 无线性代数库时实现成本高）
 *  - p 值用 t 分布近似（查表法，自由度 n-2）
 *  - 不做残差分析（正态性/异方差性检验需要额外工具）
 *  - 不做非线性回归（指数/对数/多项式）
 */
data class RegressionResult(
    val slope: Double,
    val intercept: Double,
    val rSquared: Double,
    val standardError: Double,
    val tStatistic: Double,
    val pValue: Double,
    val sampleSize: Int,
    val xColumn: String,
    val yColumn: String,
    val predictions: List<Pair<Double, Double>>,
)

/**
 * 执行一元线性回归。
 *
 * @param dataset 数据集
 * @param xIndex 自变量列索引
 * @param yIndex 因变量列索引
 * @return 回归结果
 */
fun linearRegression(dataset: Dataset, xIndex: Int, yIndex: Int): RegressionResult {
    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    for (row in dataset.rows) {
        val xv = Csv.parseNumber(row.getOrNull(xIndex) ?: "")
        val yv = Csv.parseNumber(row.getOrNull(yIndex) ?: "")
        if (xv != null && yv != null && xv.isFinite() && yv.isFinite()) {
            xs.add(xv)
            ys.add(yv)
        }
    }
    return regress(xs, ys, dataset.columns[xIndex], dataset.columns[yIndex])
}

fun regress(xs: List<Double>, ys: List<Double>, xName: String, yName: String): RegressionResult {
    val n = xs.size
    if (n < 3) throw IllegalArgumentException("回归至少需要 3 个有效数据点，当前只有 $n 个。")
    if (xs.size != ys.size) throw IllegalArgumentException("X 和 Y 的数据量不一致：X=${xs.size}, Y=${ys.size}")

    val sumX = xs.sum()
    val sumY = ys.sum()
    val sumXY = xs.zip(ys).map { it.first * it.second }.sum()
    val sumX2 = xs.map { it * it }.sum()
    val sumY2 = ys.map { it * it }.sum()

    val meanX = sumX / n
    val meanY = sumY / n

    val sXX = sumX2 - n * meanX * meanX
    val sYY = sumY2 - n * meanY * meanY
    val sXY = sumXY - n * meanX * meanY

    if (sXX == 0.0) throw IllegalArgumentException("X 的方差为零（所有 X 值相同），无法回归。")

    val slope = sXY / sXX
    val intercept = meanY - slope * meanX

    val rSquared = if (sYY == 0.0) 1.0 else (sXY * sXY) / (sXX * sYY)

    // 残差标准误差
    val residuals = xs.zip(ys).map { (x, y) -> y - (intercept + slope * x) }
    val sse = residuals.map { it * it }.sum()
    val stdErr = if (n > 2) Math.sqrt(sse / (n - 2)) else 0.0

    // 斜率的 t 统计量
    val seSlope = if (n > 2 && sXX != 0.0) stdErr / Math.sqrt(sXX) else 0.0
    val tStat = when {
        seSlope > 1e-10 -> slope / seSlope
        kotlin.math.abs(slope) > 1e-10 -> 9999.0
        else -> 0.0
    }
    val pValue = if (n > 2) tDistPValue(Math.abs(tStat), n - 2) else 1.0

    val predictions = xs.zip(ys).map { (x, _) -> x to (intercept + slope * x) }

    return RegressionResult(
        slope = Math.round(slope * 10000) / 10000.0,
        intercept = Math.round(intercept * 10000) / 10000.0,
        rSquared = Math.round(rSquared * 10000) / 10000.0,
        standardError = Math.round(stdErr * 10000) / 10000.0,
        tStatistic = Math.round(tStat * 10000) / 10000.0,
        pValue = Math.round(pValue * 10000) / 10000.0,
        sampleSize = n,
        xColumn = xName,
        yColumn = yName,
        predictions = predictions,
    )
}

fun findNumericColumn(dataset: Dataset, name: String): Int {
    val idx = dataset.columns.indexOfFirst { it.equals(name, ignoreCase = true) }
    if (idx < 0) return -1
    if (dataset.columnTypes[idx] != ColumnType.NUMBER) return -2
    return idx
}

/**
 * t 分布双侧 p 值近似（查表法，自由度 df）。
 * 用于回归系数显著性检验。
 */
private fun tDistPValue(t: Double, df: Int): Double {
    if (df <= 0) return 1.0
    if (t <= 0) return 1.0
    // 正态近似：大样本时 t 分布趋近正态
    if (df > 200) return normalPValue(t)
    // 小样本用查表近似（关键分位点）
    val table = mapOf(
        1 to listOf(12.706, 6.314, 3.078, 2.0, 1.0),
        2 to listOf(4.303, 2.920, 1.886, 1.0, 0.5),
        5 to listOf(2.571, 2.015, 1.476, 0.927, 0.4),
        10 to listOf(2.228, 1.812, 1.372, 0.879, 0.4),
        20 to listOf(2.086, 1.725, 1.325, 0.858, 0.4),
        30 to listOf(2.042, 1.697, 1.310, 0.854, 0.4),
        60 to listOf(2.000, 1.671, 1.296, 0.848, 0.4),
        120 to listOf(1.980, 1.658, 1.289, 0.846, 0.4),
        200 to listOf(1.972, 1.653, 1.286, 0.845, 0.4),
    )
    // 找最接近的 df
    val closestDf = table.keys.minByOrNull { Math.abs(it - df) } ?: 200
    val tValues = table[closestDf]!!
    val pValues = listOf(0.01, 0.05, 0.10, 0.20, 0.50)

    // 二分查找 t 值对应的 p 值
    if (t >= tValues[0]) return pValues[0]
    if (t <= tValues.last()) return 1.0
    for (i in 0 until tValues.size - 1) {
        if (t <= tValues[i] && t >= tValues[i + 1]) {
            // 线性插值
            val ratio = (t - tValues[i + 1]) / (tValues[i] - tValues[i + 1])
            return pValues[i + 1] + ratio * (pValues[i] - pValues[i + 1])
        }
    }
    return 1.0
}

/** 标准正态分布双侧 p 值 */
private fun normalPValue(z: Double): Double {
    // 1.96 → 0.05, 2.58 → 0.01, 1.64 → 0.10
    if (z >= 3.29) return 0.001
    if (z >= 2.58) return 0.01
    if (z >= 2.33) return 0.02
    if (z >= 1.96) return 0.05
    if (z >= 1.64) return 0.10
    if (z >= 1.28) return 0.20
    if (z >= 1.0) return 0.317
    if (z >= 0.67) return 0.50
    return 1.0
}
