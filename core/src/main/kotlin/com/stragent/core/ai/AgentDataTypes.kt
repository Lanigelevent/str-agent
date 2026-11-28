package com.stragent.core.ai

/**
 * Agent 产出的数据卡（从 str-agent 模块回传给 core 的 DTO）。
 * 放在 core 而不是 str-agent——因为 InsightResult 引用它们，
 * 而 InsightResult 是 core 的类型（str-agent 依赖 core，不能反向）。
 */

data class AgentFindingCard(val title: String, val detail: String, val evidence: String)

data class AgentChartCard(
    val type: String,
    val title: String,
    val data: List<Pair<String, Double>>,
    val description: String = "",
)
