package com.stragent.core.brief

import com.stragent.core.dataset.Dataset
import com.stragent.core.semantic.MetricCatalog
import com.stragent.core.semantic.MetricDef
import com.stragent.core.semantic.MetricEngine

/**
 * 指标口径段（T-055）—— 把 [MetricDef] 的确定性结果写进报告摘要。
 *
 * 为什么放在 brief 里、而不并进语义层注入块：
 *   `<allowed_numbers>` 是从 brief 全文正则提取数字的，只有写在这段之前的数字
 *   才能被 `verifyReport` 放行。指标值是**算出来的统计**（不是用户定义里的字符数字），
 *   放进 brief 是让它成为「可引用数字」的唯一正路；语义层/记忆/口径块仍在 brief 之外。
 *
 * 为什么只给「指标名 + 数值 + 单位」，不给表达式：
 *   表达式里的常量（如百分比的 `* 100`、测试边界 `-100 ~ 100`）会随 brief 进入
 *   `<allowed_numbers>`，等于凭空授权一批与数据无关的数字。口径的完整说明在
 *   `stragent_list_metrics` / `stragent_evaluate_metric` 的回包里，报告只需要
 *   「用哪个指标名、引用哪个数」。
 *
 * 诚实边界：
 *  - 字段解析不了、求值失败、自检未通过 → 该指标**不进摘要**，只在末尾汇总原因；
 *  - 一个都用不上时整段不输出（非销售类数据集不该被塞一段空口径提示）；
 *  - 指标值只表示**当前数据集（可能是抽样样本）**上的值，口径随数据集声明。
 */
internal fun metricLayerBlock(dataset: Dataset, metrics: List<MetricDef>): String? {
    if (metrics.isEmpty()) return null
    val usable = mutableListOf<Pair<MetricDef, MetricEngine.MetricResult>>()
    val skipped = mutableListOf<String>()
    var attempted = 0

    for (def in metrics) {
        if (usable.size >= MetricCatalog.MAX_METRICS_IN_BRIEF) break
        if (attempted >= MetricCatalog.MAX_METRICS_IN_BRIEF * 3) break
        attempted += 1

        val resolution = MetricEngine.resolve(def, dataset)
        if (resolution is MetricEngine.Resolution.Unresolved) {
            skipped.add("${def.name}（字段未匹配）")
            continue
        }
        val result = try {
            MetricEngine.evaluate(def, dataset)
        } catch (_: IllegalArgumentException) {
            skipped.add("${def.name}（求值失败）")
            continue
        }
        if (!result.passed) {
            skipped.add("${def.name}（自检未通过）")
            continue
        }
        usable.add(def to result)
    }

    // 一个都用不上：不输出任何内容（保持非指标数据集摘要逐字节不变）
    if (usable.isEmpty()) return null

    val lines = mutableListOf<String>()
    lines.add(
        "指标口径（MetricDef 确定性计算，可直接引用；引用时必须写指标名、数值与单位，不得改写口径，" +
            "也不得把两个指标的数混算）：",
    )
    for ((def, result) in usable) {
        lines.add("- ${def.name}：${formatMetricValue(result.value, def.unit)}")
    }
    if (skipped.isNotEmpty()) {
        val shown = skipped.take(3).joinToString("、")
        val rest = if (skipped.size > 3) "等 ${skipped.size} 个" else ""
        lines.add(
            "（另有 ${skipped.size} 个指标未纳入本次口径：$shown$rest；原因与完整定义见 stragent_list_metrics，不要自行补公式。）",
        )
    }
    return lines.joinToString("\n")
}

/** 指标值格式化：百分比固定两位；其余走 brief 同一套千分位（保留两位有效小数） */
private fun formatMetricValue(value: Double, unit: String): String {
    if (unit == "%") return "${toFixed2(value)}%"
    val number = formatNumber(value)
    return if (unit.isEmpty()) number else "$number $unit"
}
