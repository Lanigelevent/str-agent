package com.stragent.core.observability

/**
 * T-164 · OTel GenAI 语义约定属性名的**单一映射层**（D26 ⑦ 跟进行动）。
 *
 * 行业背景：`gen_ai.*` 属性族属 OTel GenAI semantic conventions，当前仍处
 * Development 状态——会有**无弃用窗口的改名**（社区已拆入专用仓库推进）。
 * 把属性字符串收敛在这一个对象里，约定改名时只动这里、span 埋点代码不动；
 * 这是行业对 Development 期约定的标准防御姿态（pin 当前拼写 + 单点映射）。
 *
 * 取值口径：按 conventions 当前草案的常用属性拼写；`str_agent.*` 私有属性
 * 不在此列——它们不在 GenAI 约定的改名风险面里。
 */
object GenAiAttrs {
    /** span 记录的 GenAI 操作名（本服务只用两个值：`generate` / `execute_tool`） */
    const val OPERATION_NAME = "gen_ai.operation.name"
    const val SYSTEM = "gen_ai.system"
    const val TOOL_NAME = "gen_ai.tool.name"
    const val USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens"
}
