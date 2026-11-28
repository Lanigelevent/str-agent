package com.stragent.core.report

import kotlin.math.min

/**
 * 行业匹配引擎 —— industry-match.ts 移植（数据在 IndustryMatchData，脚本生成）。
 *
 * 只依据列名信号（取值特征留给后续迭代），返回按分值降序的候选与最高项置信度。
 * 关键词全部从真实公开数据集的列名归纳，不凭想象编。
 */

data class IndustryMatch(val industryId: String, /** 0–1，越高越有把握 */ val confidence: Double, /** 命中的信号词 */ val hits: List<String>)

data class IndustryRecommendation(
    val industryId: String,
    val confidence: Double,
    /** 为什么推荐（命中的信号词），供 UI 展示 */
    val reason: String,
    val alternatives: List<IndustryMatch>,
)

/** 需要 2 个以上不同信号命中才推荐具体行业（0.6 ≈ 2/3）；低于此回落「通用」——宁可不猜 */
const val MATCH_THRESHOLD = IndustryMatchData.MATCH_THRESHOLD

/**
 * 列名拆词：小写化 → 驼峰与分隔符都切开 → 去空。
 * 'totalBill' → ['total','bill']，'paperlessbilling' → ['paperlessbilling']（整词，不拆），
 * 'bill_length_mm' → ['bill','length','mm']。
 * 用**完整词**匹配而不是子串匹配——子串会让 'ice' 命中 'service'，误判率极高。
 */
fun columnWords(name: String): List<String> =
    name.trim()
        // 先在原串上拆驼峰（'PassengerId' → 'Passenger_Id'），再小写——
        // 顺序反了会让 'PassengerId' 变成整词 'passengerid'
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
        .split(Regex("[_\\s\\-.·/]+"))
        .filter { it.isNotEmpty() }

/** 单复数兼容：'passengers' 要能命中信号 'passenger' */
private fun withPlural(word: String): List<String> =
    if (word.endsWith("s") && word.length > 3) listOf(word, word.dropLast(1)) else listOf(word)

/** 匹配行业，返回按置信度降序的前 limit 个候选 */
fun matchIndustries(columns: List<String>, limit: Int = 3): List<IndustryMatch> {
    val wordSet = columns.flatMap { columnWords(it) }.toSet()

    data class Scored(val id: String, val hits: List<String>, val strong: Int)

    val scored = mutableListOf<Scored>()
    val entries = IndustryMatchData.industrySignals.entries.filter { it.key != "general" }
    for ((id, signals) in entries) {
        if (signals.isEmpty()) continue
        val strongSet = (IndustryMatchData.strongSignals[id] ?: emptyList()).toSet()
        val hits = mutableListOf<String>()
        val strongHits = mutableListOf<String>()
        fun consider(signal: String) {
            // 信号词可以是词组（'life_expectancy'），按词序列匹配；
            // 单词信号则要求某个列名词与它完全相等
            val parts = columnWords(signal)
            val matched = if (parts.size > 1) {
                columns.any { col -> columnWords(col).joinToString("_") == parts.joinToString("_") }
            } else {
                parts[0].isNotEmpty() && wordSet.any { w -> withPlural(w).contains(parts[0]) }
            }
            if (!matched) return
            if (signal in strongSet) strongHits.add(signal) else hits.add(signal)
        }
        for (signal in signals) consider(signal)
        for (signal in IndustryMatchData.strongSignals[id] ?: emptyList()) consider(signal)
        if (hits.size + strongHits.size > 0) {
            scored.add(Scored(id, strongHits + hits, strongHits.size))
        }
    }

    // 打分：强信号命中 1 个即 0.8、2 个满分；普通信号要 2 个以上才够 0.6 的推荐阈值
    return scored
        .map { (id, hits, strong) ->
            IndustryMatch(
                industryId = id,
                confidence = min(1.0, when {
                    strong >= 2 -> 1.0
                    strong == 1 -> 0.8
                    else -> hits.size / 3.0
                }),
                hits = hits,
            )
        }
        .sortedWith(compareByDescending<IndustryMatch> { it.confidence }.thenByDescending { it.hits.size })
        .take(limit)
}

/**
 * 给出最终推荐：置信度达标就给具体行业，否则回落「通用」。
 */
fun recommendIndustry(columns: List<String>): IndustryRecommendation {
    val ranked = matchIndustries(columns, 3)
    val best = ranked.firstOrNull()
    if (best == null || best.confidence < MATCH_THRESHOLD) {
        return IndustryRecommendation(
            industryId = "general",
            confidence = best?.confidence ?: 0.0,
            reason = "列名里没有足够明显的行业特征，先用通用框架",
            alternatives = ranked,
        )
    }
    return IndustryRecommendation(
        industryId = best.industryId,
        confidence = kotlin.math.round(best.confidence * 100) / 100,
        reason = "命中 ${best.hits.take(4).joinToString("、")}",
        alternatives = ranked.drop(1),
    )
}
