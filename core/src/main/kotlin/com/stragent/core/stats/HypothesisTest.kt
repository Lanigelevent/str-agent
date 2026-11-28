package com.stragent.core.stats

import com.stragent.core.csv.Csv
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset

/**
 * 假设检验 —— D10 阶段 5 统计方法扩充。
 *
 * 纯 Kotlin 零依赖、确定性。支持：
 *  - 独立样本 t 检验（两组独立数值的均值差异显著性）
 *  - 卡方检验（分类列的关联性检验）
 *
 * 诚实边界：
 *  - t 检验假设方差齐性（Student's t，不做 Welch 修正）
 *  - p 值用查表近似（不是精确的 t/卡方分布 CDF）
 *  - 不做配对 t 检验（需要行级配对，纯统计工具不支持行号映射）
 *  - 不做 ANOVA（多组比较需要 F 分布）
 *  - 不做非参数检验（Mann-Whitney/Wilcoxon）
 */

/** 独立样本 t 检验结果 */
data class TTestResult(
    val groupA: String,
    val groupB: String,
    val meanA: Double,
    val meanB: Double,
    val tStatistic: Double,
    val pValue: Double,
    val sampleA: Int,
    val sampleB: Int,
    val significant: Boolean,
    val interpretation: String,
)

/** 卡方检验结果 */
data class ChiSquareResult(
    val chiSquare: Double,
    val pValue: Double,
    val degreesOfFreedom: Int,
    val significant: Boolean,
    val interpretation: String,
    val observed: List<List<Int>>,
    val expected: List<List<Double>>,
    val rowLabels: List<String>,
    val colLabels: List<String>,
)

/**
 * 独立样本 t 检验（Student's t-test）。
 *
 * 比较两个分组的数值列均值是否有显著差异。
 *
 * @param dataset 数据集
 * @param groupIndex 分组列索引（文本列）
 * @param valueIndex 数值列索引
 * @param groupA 分组 A 的值（缺省取第一个分组值）
 * @param groupB 分组 B 的值（缺省取第二个分组值）
 */
fun independentTTest(
    dataset: Dataset,
    groupIndex: Int,
    valueIndex: Int,
    groupA: String? = null,
    groupB: String? = null,
): TTestResult {
    val groups = mutableMapOf<String, MutableList<Double>>()
    for (row in dataset.rows) {
        val groupVal = (row.getOrNull(groupIndex) ?: "").trim().ifEmpty { "(空白)" }
        val numVal = Csv.parseNumber(row.getOrNull(valueIndex) ?: "")
        if (numVal != null && numVal.isFinite()) {
            groups.getOrPut(groupVal) { mutableListOf() }.add(numVal)
        }
    }
    if (groups.size < 2) {
        throw IllegalArgumentException("分组列「${dataset.columns[groupIndex]}」只有 ${groups.size} 个分组，t 检验需要至少 2 个分组。")
    }
    val gk = groups.keys.toList()
    val aKey = groupA ?: gk[0]
    val bKey = groupB ?: gk[1]
    if (aKey !in groups) throw IllegalArgumentException("分组「$aKey」在数据中不存在。可用分组：${gk.joinToString("、")}")
    if (bKey !in groups) throw IllegalArgumentException("分组「$bKey」在数据中不存在。可用分组：${gk.joinToString("、")}")

    val aValues = groups[aKey]!!
    val bValues = groups[bKey]!!
    return computeTTest(aKey, bKey, aValues, bValues)
}

fun computeTTest(
    groupA: String, groupB: String,
    aValues: List<Double>, bValues: List<Double>,
): TTestResult {
    val na = aValues.size
    val nb = bValues.size
    if (na < 2 || nb < 2) {
        throw IllegalArgumentException("每组至少需要 2 个数据点。当前：$groupA=$na 个，$groupB=$nb 个。")
    }

    val meanA = aValues.sum() / na
    val meanB = bValues.sum() / nb
    val varA = aValues.map { (it - meanA).let { x -> x * x } }.sum() / (na - 1)
    val varB = bValues.map { (it - meanB).let { x -> x * x } }.sum() / (nb - 1)

    // 合并方差（假设方差齐性）
    val pooledVar = ((na - 1) * varA + (nb - 1) * varB) / (na + nb - 2)
    if (pooledVar <= 0) {
        throw IllegalArgumentException("合并方差为零，可能两组数据完全相同。")
    }
    val se = Math.sqrt(pooledVar * (1.0 / na + 1.0 / nb))
    val tStat = (meanA - meanB) / se
    val df = na + nb - 2
    val pValue = tDistTwoSided(Math.abs(tStat), df)
    val significant = pValue < 0.05

    val interp = if (significant) {
        "p=${formatP(pValue)} < 0.05，拒绝原假设：两组均值有显著差异（$groupA 的均值 $meanA vs $groupB 的均值 $meanB）。"
    } else {
        "p=${formatP(pValue)} >= 0.05，不拒绝原假设：两组均值无显著差异。"
    }

    return TTestResult(
        groupA = groupA,
        groupB = groupB,
        meanA = Math.round(meanA * 10000) / 10000.0,
        meanB = Math.round(meanB * 10000) / 10000.0,
        tStatistic = Math.round(tStat * 10000) / 10000.0,
        pValue = Math.round(pValue * 10000) / 10000.0,
        sampleA = na,
        sampleB = nb,
        significant = significant,
        interpretation = interp,
    )
}

/**
 * 卡方独立性检验。
 *
 * 检验两个分类列之间是否有关联。
 *
 * @param dataset 数据集
 * @param rowColIndex 行维度列索引（文本列）
 * @param colColIndex 列维度列索引（文本列）
 * @param maxCategories 每个维度最多保留多少类别（超出合并为「其他」）
 */
fun chiSquareTest(
    dataset: Dataset,
    rowColIndex: Int,
    colColIndex: Int,
    maxCategories: Int = 8,
): ChiSquareResult {
    // 构建列联表
    val rowCounts = mutableMapOf<String, Int>()
    val colCounts = mutableMapOf<String, Int>()
    for (row in dataset.rows) {
        val rv = (row.getOrNull(rowColIndex) ?: "").trim().ifEmpty { "(空白)" }
        val cv = (row.getOrNull(colColIndex) ?: "").trim().ifEmpty { "(空白)" }
        rowCounts[rv] = (rowCounts[rv] ?: 0) + 1
        colCounts[cv] = (colCounts[cv] ?: 0) + 1
    }

    // 只保留 top N 类别，其余合并为「其他」
    val topRows = rowCounts.entries.sortedByDescending { it.value }.take(maxCategories).map { it.key }
    val topCols = colCounts.entries.sortedByDescending { it.value }.take(maxCategories).map { it.key }

    val rowLabels = topRows + (if (rowCounts.size > maxCategories) listOf("其他") else emptyList())
    val colLabels = topCols + (if (colCounts.size > maxCategories) listOf("其他") else emptyList())

    val observed = Array(rowLabels.size) { IntArray(colLabels.size) }
    for (row in dataset.rows) {
        val rv = (row.getOrNull(rowColIndex) ?: "").trim().ifEmpty { "(空白)" }
        val cv = (row.getOrNull(colColIndex) ?: "").trim().ifEmpty { "(空白)" }
        val ri = rowLabels.indexOf(rv).let { if (it >= 0) it else rowLabels.indexOf("其他") }
        val ci = colLabels.indexOf(cv).let { if (it >= 0) it else colLabels.indexOf("其他") }
        if (ri >= 0 && ci >= 0) observed[ri][ci]++
    }

    // 期望频数
    val rowSums = observed.map { it.sum() }
    val colSums = (0 until colLabels.size).map { c -> (0 until rowLabels.size).sumOf { observed[it][c] } }
    val total = rowSums.sum()
    if (total == 0) throw IllegalArgumentException("列联表为空。")

    val expected = Array(rowLabels.size) { DoubleArray(colLabels.size) }
    for (i in rowLabels.indices) {
        for (j in colLabels.indices) {
            expected[i][j] = rowSums[i].toDouble() * colSums[j] / total
        }
    }

    // 卡方统计量
    var chiSq = 0.0
    for (i in rowLabels.indices) {
        for (j in colLabels.indices) {
            val exp = expected[i][j]
            if (exp > 0) {
                chiSq += (observed[i][j] - exp).let { it * it } / exp
            }
        }
    }

    val df = (rowLabels.size - 1) * (colLabels.size - 1)
    val pValue = chiSquarePValue(chiSq, df)
    val significant = pValue < 0.05

    val interp = if (significant) {
        "p=${formatP(pValue)} < 0.05，拒绝原假设：两列「${dataset.columns[rowColIndex]}」与「${dataset.columns[colColIndex]}」之间存在显著关联。"
    } else {
        "p=${formatP(pValue)} >= 0.05，不拒绝原假设：两列之间无显著关联（可视为独立）。"
    }

    return ChiSquareResult(
        chiSquare = Math.round(chiSq * 10000) / 10000.0,
        pValue = Math.round(pValue * 10000) / 10000.0,
        degreesOfFreedom = df,
        significant = significant,
        interpretation = interp,
        observed = observed.map { it.toList() },
        expected = expected.map { it.toList() },
        rowLabels = rowLabels,
        colLabels = colLabels,
    )
}

/** t 分布双侧 p 值近似 */
private fun tDistTwoSided(t: Double, df: Int): Double {
    if (df <= 0) return 1.0
    if (t <= 0) return 1.0
    if (df > 200) {
        // 正态近似
        return when {
            t >= 3.29 -> 0.001
            t >= 2.58 -> 0.01
            t >= 2.33 -> 0.02
            t >= 1.96 -> 0.05
            t >= 1.64 -> 0.10
            t >= 1.28 -> 0.20
            t >= 1.0 -> 0.317
            t >= 0.67 -> 0.50
            else -> 1.0
        }
    }
    // 查表近似
    val table = mapOf(
        1 to listOf(12.706, 4.303, 2.0, 1.0, 0.5),
        2 to listOf(4.303, 2.920, 1.886, 1.0, 0.5),
        5 to listOf(2.571, 2.015, 1.476, 0.927, 0.4),
        10 to listOf(2.228, 1.812, 1.372, 0.879, 0.4),
        20 to listOf(2.086, 1.725, 1.325, 0.858, 0.4),
        30 to listOf(2.042, 1.697, 1.310, 0.854, 0.4),
        60 to listOf(2.000, 1.671, 1.296, 0.848, 0.4),
        120 to listOf(1.980, 1.658, 1.289, 0.846, 0.4),
    )
    val closestDf = table.keys.minByOrNull { Math.abs(it - df) } ?: 120
    val tValues = table[closestDf]!!
    val pValues = listOf(0.01, 0.05, 0.10, 0.20, 0.50)
    if (t >= tValues[0]) return pValues[0]
    if (t <= tValues.last()) return 1.0
    for (i in 0 until tValues.size - 1) {
        if (t <= tValues[i] && t >= tValues[i + 1]) {
            val ratio = (t - tValues[i + 1]) / (tValues[i] - tValues[i + 1])
            return pValues[i + 1] + ratio * (pValues[i] - pValues[i + 1])
        }
    }
    return 1.0
}

/** 卡方分布 p 值近似 */
private fun chiSquarePValue(chiSq: Double, df: Int): Double {
    if (df <= 0) return 1.0
    if (chiSq <= 0) return 1.0
    // 卡方临界值表（α=0.05, α=0.01, α=0.001）
    // df → (chi2_0.05, chi2_0.01, chi2_0.001)
    val criticalValues = mapOf(
        1 to listOf(3.841, 6.635, 10.828),
        2 to listOf(5.991, 9.210, 13.816),
        3 to listOf(7.815, 11.345, 16.266),
        4 to listOf(9.488, 13.277, 18.467),
        5 to listOf(11.070, 15.086, 20.515),
        6 to listOf(12.592, 16.812, 22.458),
        7 to listOf(14.067, 18.475, 24.322),
        8 to listOf(15.507, 20.090, 26.125),
        9 to listOf(16.919, 21.666, 27.877),
        10 to listOf(18.307, 23.209, 29.588),
        12 to listOf(21.026, 26.217, 32.909),
        15 to listOf(24.996, 30.578, 37.697),
        20 to listOf(31.410, 37.566, 45.315),
        25 to listOf(37.652, 44.314, 52.620),
        30 to listOf(43.773, 50.892, 59.703),
        40 to listOf(55.758, 63.691, 73.402),
        50 to listOf(67.505, 76.154, 86.661),
    )
    if (df > 50) {
        // 大自由度：正态近似 (chi2 - df) / sqrt(2*df) ~ N(0,1)
        val z = (chiSq - df) / Math.sqrt(2.0 * df)
        return when {
            z >= 3.29 -> 0.001
            z >= 2.58 -> 0.01
            z >= 2.33 -> 0.02
            z >= 1.96 -> 0.05
            z >= 1.64 -> 0.10
            z >= 1.28 -> 0.20
            else -> 1.0
        }
    }
    val closestDf = criticalValues.keys.minByOrNull { Math.abs(it - df) } ?: 50
    val cv = criticalValues[closestDf]!!
    return when {
        chiSq >= cv[2] -> 0.001
        chiSq >= cv[1] -> 0.01
        chiSq >= cv[0] -> 0.05
        else -> 0.10
    }
}

private fun formatP(p: Double): String =
    if (p < 0.001) "<0.001" else String.format("%.3f", p)
