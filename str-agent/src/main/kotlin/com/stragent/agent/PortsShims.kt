package com.stragent.agent

/**
 * T-148 架构归位垫片（2026-11-13）：工具契约符号的真身在 **core/ports**
 * （AgentTool / ToolException / ToolRegistry / ProgressBus / UiEventBroadcaster /
 * NotebookSerializer / ErrorActions / AgentCancelled + schema 工具函数），str-agent
 * 包内同包引用（AgentLoop / EditorialDept / AgentReportDelegate / AgentIntegratedOrchestrator /
 * AgentTools——枚举仅供快速定位，事实面以 grep com.stragent.agent 的实际引用为准）
 * 经本垫片保编译；host / bench 的既有 import 也由此零改动（其别名残留归一见 D7）。
 *
 * 纪律：
 *  - data **禁止** import com.stragent.agent（T-148 验收线：data→str-agent 主依赖归零），
 *    data 侧一律改指 com.stragent.core.ports；
 *  - 垫片是**别名不是副本**：行为与 core/ports 真身逐字一致，不产生第二事实源；
 *  - 本包内**不得新增**对垫片符号的新实现（新代码直接用 core.ports）——AgentTools.kt
 *    曾在 T-148 轮以别名实现 `object : AgentTool`（D25 审查发现的破窗），已改为显式
 *    import core.ports.AgentTool 收口。
 */
import kotlinx.serialization.json.JsonObject

typealias AgentTool = com.stragent.core.ports.AgentTool
typealias ToolException = com.stragent.core.ports.ToolException
typealias ToolRegistry = com.stragent.core.ports.ToolRegistry
typealias ProgressBus = com.stragent.core.ports.ProgressBus
typealias UiEventBroadcaster = com.stragent.core.ports.UiEventBroadcaster
typealias NotebookSerializer = com.stragent.core.ports.NotebookSerializer
typealias ErrorActions = com.stragent.core.ports.ErrorActions
typealias AgentCancelled = com.stragent.core.ports.AgentCancelled

/** schema 组装工具函数（T-104 参数规格）：真身 core/ports/ToolSchema.kt，转发保同包编译 */
fun schemaObject(
    vararg props: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = com.stragent.core.ports.schemaObject(*props, required = required)

fun schemaField(type: String, description: String? = null): JsonObject =
    com.stragent.core.ports.schemaField(type, description)

fun schemaArrayField(items: JsonObject, description: String? = null): JsonObject =
    com.stragent.core.ports.schemaArrayField(items, description)

fun schemaAnyField(description: String? = null): JsonObject =
    com.stragent.core.ports.schemaAnyField(description)
