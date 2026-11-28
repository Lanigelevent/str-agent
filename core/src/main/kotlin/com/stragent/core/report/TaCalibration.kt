package com.stragent.core.report

import kotlin.math.round

/**
 * TA 校准数学（T-109，Retkowski & Waibel arXiv:2501.00233 的 Target Adjustment 反演）：
 * 真实模型在字数目标下系统性欠产时，把「要的」上调为「目标/实测比」倍。
 * 本文件只装数学与判据，不含任何模型调用——真值采集在 host 的 calibrateTA 任务。
 */
data class TaSample(val targetChars: Int, val actualChars: Int)

/** 中位实测比（actual/target）；target≤0 的样本剔除；空集返回 1.0（无证据 = 不上调） */
fun medianRatio(samples: List<TaSample>): Double {
    val ratios = samples.mapNotNull { s ->
        if (s.targetChars > 0 && s.actualChars >= 0) s.actualChars.toDouble() / s.targetChars else null
    }.sorted()
    if (ratios.isEmpty()) return 1.0
    return if (ratios.size % 2 == 1) ratios[ratios.size / 2]
    else (ratios[ratios.size / 2 - 1] + ratios[ratios.size / 2]) / 2.0
}

/** 因子上限：欠产到 <0.67 时先查提示词与口径（上限 1.5 防发散，Retkowski TA 区间一致） */
const val TA_FACTOR_CAP = 1.5

/** 中位比 ≥0.8 = 依从足够（Retkowski 论文理想带），无需 TA */
const val TA_RATIO_OK = 0.8

/** 推荐因子：1/ratio 夹在 [1.0, 1.5]；ratio≥0.8 判依从足够返回 1.0（=关） */
fun recommendFactor(ratio: Double): Double {
    val r = round(ratio * 100) / 100
    if (r >= TA_RATIO_OK) return 1.0
    return (1.0 / r).coerceAtMost(TA_FACTOR_CAP)
}

fun recommendFactor(samples: List<TaSample>): Double = recommendFactor(medianRatio(samples))

/**
 * STRAGENT_LENGTH_TA 解析：缺省/空 = 关（null）；"1" = 旧档 1.25（T-105 兼容）；
 * 小数 = 显式因子（1.0–1.5 之外视为误配，按关闭处理——env 旋钮防呆，与 envInt 下限同哲学）。
 */
fun parseTaFactor(raw: String?): Double? = when {
    raw.isNullOrBlank() -> null
    raw.trim() == "1" -> 1.25
    else -> raw.trim().toDoubleOrNull()?.takeIf { it >= 1.0 && it <= TA_FACTOR_CAP }
}

/**
 * T-109b 分档 TA：欠产随档位放大时固定因子失效（实测 300 字档 0.85 vs 3500 字档 0.43），
 * 按档位分段线性反演——profile 由真值采集生成（替身轮或 :host:calibrateTA）。
 * 格式 "300:1.18,500:1.43,800:1.5"（档位升序；因子夹 [1.0, 1.5]）；非法/空 = null（关）。
 */
fun parseTaProfile(raw: String?): List<Pair<Int, Double>>? {
    if (raw.isNullOrBlank()) return null
    val pts = raw.split(',').mapNotNull {
        val p = it.trim().split(':')
        val chars = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val f = p.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
        if (chars <= 0 || f < 1.0 || f > TA_FACTOR_CAP) null else chars to f
    }.sortedBy { it.first }
    return pts.ifEmpty { null }
}

/** 分档因子：低于最小档取首点、高于最大档取末点，档间线性插值 */
fun factorFor(chars: Int, profile: List<Pair<Int, Double>>): Double {
    if (profile.isEmpty()) return 1.0
    if (chars <= profile.first().first) return profile.first().second
    if (chars >= profile.last().first) return profile.last().second
    for (i in 0 until profile.size - 1) {
        val (c1, f1) = profile[i]
        val (c2, f2) = profile[i + 1]
        if (chars in c1..c2) {
            val t = (chars - c1).toDouble() / (c2 - c1)
            return f1 + (f2 - f1) * t
        }
    }
    return 1.0
}
