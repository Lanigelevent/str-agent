package com.stragent.host.eval

import com.stragent.core.report.BANNED_WRITING_RULES
import com.stragent.core.report.isStructuredReportType

/**
 * T-052 · 确定性报告评分器（evaluation-side rubric，不是生产门禁）。
 *
 * 为什么是确定性 rubric：环境里没有可用的 judge 模型 Key，LLM-as-judge 无法真实执行；
 * 退一步用可复现的规则评分，把「评测机制」先跑通——结构、证据、可读性、结论四项，
 * judge **只接收报告文本与报告类型**，看不到 id / 质量档 / 轮次，保证盲评。
 *
 * 边界（写进评分卡）：rubric 由替身自己设计，30 个锚点也由替身模型评审产生，
 * κ 只能证明「规则与自评一致」，不能证明与人类评审一致；真实模型与人类 κ 仍是外部阻塞。
 */
data class JudgeScore(
    val structure: Double,
    val evidence: Double,
    val readability: Double,
    val conclusion: Double,
    val evidenceTokens: Int,
    val bannedHits: Int,
) {
    val total: Double get() = structure + evidence + readability + conclusion

    /** 三档：高 ≥7.0，中 ≥5.0，低 <5.0（与评审标签 4–5 / 3 / 1–2 对齐） */
    val grade: String get() = when {
        total >= 7.0 -> "高"
        total >= 5.0 -> "中"
        else -> "低"
    }
}

object ReportQualityJudge {

    fun score(report: String, typeId: String): JudgeScore {
        val structure = structureScore(report, typeId)
        val tokens = dataTokens(report)
        val evidence = when {
            tokens.size >= 10 -> 2.0
            tokens.size >= 6 -> 1.5
            tokens.size >= 4 -> 1.0
            tokens.size >= 2 -> 0.5
            else -> 0.0
        }
        val readability = readabilityScore(report)
        val conclusion = conclusionScore(report)
        val banned = report.lines().count { line -> BANNED_WRITING_RULES.any { it.pattern.containsMatchIn(line) } }
        return JudgeScore(structure, evidence, readability, conclusion, tokens.size, banned)
    }

    /** 成对比较：总分为主，证据条数、结构分依次破平；完全相等返回 0（反对称） */
    fun compare(a: String, b: String, typeId: String): Int {
        val sa = score(a, typeId)
        val sb = score(b, typeId)
        val byTotal = sa.total.compareTo(sb.total)
        if (byTotal != 0) return byTotal
        val byEvidence = sa.evidenceTokens.compareTo(sb.evidenceTokens)
        if (byEvidence != 0) return byEvidence
        return sa.structure.compareTo(sb.structure)
    }

    private fun structureScore(report: String, typeId: String): Double {
        if (isStructuredReportType(typeId)) {
            var score = 0.0
            if (report.contains("全期值") && report.contains("最值定位")) score += 0.5
            if (report.contains("适用范围")) score += 0.5
            if (report.contains("数据未能回答的问题")) score += 0.5
            if (report.contains("依据：") || report.contains("依据:")) score += 0.5
            return score
        }
        var score = 0.0
        val opening = report.take(120)
        if (dataTokens(opening).isNotEmpty() && CONCLUSION_MARKERS.containsMatchIn(opening)) score += 1.0
        if (ACTION_MARKERS.containsMatchIn(report)) score += 1.0
        return score
    }

    private fun readabilityScore(report: String): Double {
        val prose = report.lines()
            .filter { !it.trimStart().startsWith("|") && !it.trimStart().startsWith("#") }
            .joinToString("\n")
        val sentences = prose.split(Regex("[。！？!?\\n]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return 0.0
        val lengths = sentences.map { it.replace(Regex("\\s"), "").length }
        val average = lengths.average()
        val longest = lengths.maxOrNull() ?: 0
        return when {
            average in 12.0..50.0 && longest <= 160 -> 2.0
            average in 8.0..70.0 -> 1.5
            else -> 1.0
        }
    }

    private fun conclusionScore(report: String): Double {
        val prose = report.lines()
            .filter { !it.trimStart().startsWith("|") && !it.trimStart().startsWith("#") }
            .joinToString("\n")
        val qualified = prose.split(Regex("[。！？!?\\n]"))
            .count { sentence ->
                dataTokens(sentence).size >= 2 && CONCLUSION_MARKERS.containsMatchIn(sentence)
            }
        return when {
            qualified >= 2 -> 2.0
            qualified == 1 -> 1.0
            else -> 0.0
        }
    }

    fun dataTokens(text: String): List<String> {
        var cleaned = text
        cleaned = DATE_LIKE.replace(cleaned, " ")
        cleaned = SHORT_DATE.replace(cleaned, " ")
        cleaned = ROW_REFERENCE.replace(cleaned, " ")
        cleaned = PERIOD.replace(cleaned, " ")
        cleaned = STRUCTURE_COUNT.replace(cleaned, " ")
        cleaned = ROW_COLUMN_COUNT.replace(cleaned, " ")
        return NUMBER.findAll(cleaned)
            .map { it.value.replace(",", "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private val DATE_LIKE = Regex("""\d{4}-\d{2}(?:-\d{2})?""")
    private val SHORT_DATE = Regex("""\d{2}-\d{2}""")
    private val ROW_REFERENCE = Regex("""第\s*\d+\s*[行列]""")
    private val PERIOD = Regex("""\d+(?:\.\d+)?\s*(?:个月|月|年|天|日|周|季度)""")
    private val STRUCTURE_COUNT = Regex("""\d+\s*(?:条|个)\s*(?:渠道|品类|校区|班型|科室|产线|收入科目|值)""")
    private val ROW_COLUMN_COUNT = Regex("""\d+\s*(?:行|列)""")
    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")

    private val CONCLUSION_MARKERS = Regex(
        "最高|最低|峰值|谷值|高点|低点|居首|高于|低于|回落|增长|下降|占|异常|最大|最小|最值",
    )

    private val ACTION_MARKERS = Regex("建议|下一步|需要关注|需要重点|不含|只覆盖|口径")
}
