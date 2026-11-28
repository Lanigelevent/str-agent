package com.stragent.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MCP 对外工具名——内部裸名 → 规范名的唯一映射（T-053 / AD-025；T-068 起别名退役）。
 *
 * 规则：**对外暴露的名字必须 `stragent_` 开头**。16 个会话内分析工具的内部名仍是
 * 裸名（`AgentTool.name` 同时是白名单键、断点签名与提示词引用，原地改名会牵动一整片），
 * 在 MCP 边界通过 [exposed] 统一映射为规范名——`tools/list` 公布的、回执/溯源/span
 * 记录的、客户端必须调用的，都是规范名。
 *
 * **1.1.0 起旧裸名不再可调用**（T-068 兑现 T-053「一版兼容期后删除」的承诺）：
 * `matches` 只认规范名；客户端用旧裸名调用会得到 -32602，且错误信息带规范名指引
 * （见 McpBridge 的 unknown-tool 路径）。
 *
 * 为什么在桥接层改名而不是改工具内部 name：
 *  - `AgentTool.name` 同时是进程内 agent 的白名单键、断点签名的一段、
 *    测试与提示词里的引用；原地改名会牵动一整片（且断点状态会失效）。
 *  - MCP 对外名字是**边界契约**，只该在边界上做一次映射。
 *
 * 映射表不是第二事实源：工具实现、描述、schema 仍只有一份。
 */
object McpToolNames {

    /** 内部裸名 → 对外规范名（边界映射；不是客户端兼容别名——旧裸名调用 1.1.0 起拒绝） */
    val CANONICAL_NAMES: Map<String, String> = linkedMapOf(
        "get_overview" to "stragent_session_overview",
        "get_column_profile" to "stragent_session_column_profile",
        "group_aggregate" to "stragent_session_group_aggregate",
        "get_outliers" to "stragent_session_outliers",
        "scan_anomalies" to "stragent_session_anomalies",
        "get_attribution_chain" to "stragent_session_attribution_chain",
        "get_group_yoy" to "stragent_session_group_yoy",
        "get_cross_tab" to "stragent_session_cross_tab",
        "get_group_trend" to "stragent_session_group_trend",
        "get_correlations" to "stragent_session_correlations",
        "drill_down" to "stragent_session_drill_down",
        "get_series" to "stragent_session_series",
        "get_extremes" to "stragent_session_extremes",
        "get_quality" to "stragent_session_quality",
        "get_report_context" to "stragent_session_report_context",
        "audit_report" to "stragent_session_audit_report",
    )

    /** 内部名 → 对外规范名；已是规范名的原样返回 */
    fun exposed(name: String): String = CANONICAL_NAMES[name] ?: name

    /** requested 是否命中该工具：**只认规范名**（旧裸名 1.1.0 起不再解析） */
    fun matches(toolName: String, requested: String): Boolean = exposed(toolName) == requested

    /**
     * annotations 的 title 换成对外名字。
     * 内部 `AgentTool.name` 是裸名，直接在目录里下发会让人看到 title 与 name 不一致。
     */
    fun withTitle(annotations: JsonObject, title: String): JsonObject =
        JsonObject(annotations + ("title" to JsonPrimitive(title)))
}
