package com.stragent.agent.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DeepSeek Harness 插件类型（官方分类，见
 * https://the reference harness-plugin.com/zh-CN/blog/what-is-a-deepseek-plugin ）。
 *
 * Str Agent 自归类为其中两类：
 * - [TOOLS] 工具与能力：report/followup/review/editorial 都是可被宿主调用的 agent 工具；
 * - [WORKFLOWS] 工作流与自动化：editorial 多 Agent 流水线 + report 流水线即多步编排。
 */
enum class DshPluginType(val id: String, val label: String) {
    TOOLS("tools-capabilities", "工具与能力"),
    SKILLS("skills", "技能"),
    MEMORY("memory", "记忆"),
    UI("ui", "界面增强"),
    WORKFLOWS("workflows", "工作流与自动化"),
    MODELS("models-providers", "模型与提供方"),
    NOTIFICATIONS("notifications", "通知与集成"),
    FUN("fun", "趣味");
}

/** the reference harness「能力缝（Seam）」三角色：定义 / 实现 / 消费。 */
enum class SeamRole { DEFINITION, PROVIDER, CONSUMER }

/**
 * 一个能力缝声明。Str Agent 与宿主之间以 seam 解耦，与 the reference harness 的
 * 「Service Definition / Service Provider / Consumer」同构：
 * - 宿主声明并实现 [SeamRole.PROVIDER] 缝（如 model / storage）；
 * - Str Agent 作为 [SeamRole.CONSUMER] 声明它要消费哪些缝（先声明后用，未声明即被拒绝 —— the reference harness capability safety）；
 * - Str Agent 同时作为 [SeamRole.PROVIDER] 贡献自身能力缝（report/followup/.../mcp-bridge）。
 *
 * @param id        缝标识（跨语言/跨进程稳定）
 * @param role      本声明在缝中的角色
 * @param contract  契约类型（端口接口名），宿主与插件据此对接
 * @param description 人类可读说明
 * @param required  是否强制：装载时宿主必须提供；false = 可选增强（缺失则插件用内部实现兜底）
 */
data class Seam(
    val id: String,
    val role: SeamRole,
    val contract: String,
    val description: String,
    val required: Boolean = true,
)

/**
 * Str Agent 作为「纯插件」，从宿主消费的缝（[SeamRole.CONSUMER]）。
 * 这些是装载时静态声明的依赖；宿主不满足 required 缝则装载失败。
 */
val STR_AGENT_INJECT_SEAMS: List<Seam> = listOf(
    Seam("model", SeamRole.CONSUMER, "LlmClient",
        "OpenAI 兼容 /chat/completions 推理服务（提供方：宿主或 the reference harness llm 缝）"),
    Seam("storage", SeamRole.CONSUMER, "DatasetStore",
        "键值文档存储（报告草稿/索引；提供方：宿主 storage 缝）"),
    Seam("prefs", SeamRole.CONSUMER, "PrefsStore",
        "键值偏好存储（提供方：宿主 prefs 缝）"),
    Seam("session", SeamRole.CONSUMER, "TraceSink",
        "可追加的会话/轨迹日志（提供方：宿主 observable 层；对应 the reference harness append-only session log）。可选：宿主不提供时 Str Agent 用内置 InsightTrace 兜底。",
        required = false),
)

/**
 * Str Agent 向宿主贡献的缝（[SeamRole.PROVIDER]）——即它的能力面。
 */
val STR_AGENT_CONTRIBUTE_SEAMS: List<Seam> = listOf(
    Seam("report", SeamRole.PROVIDER, "agent-report",
        "深挖/标准数据分析报告（单 Agent 或编辑部多 Agent）"),
    Seam("followup", SeamRole.PROVIDER, "agent-followup",
        "对已有数据/报告做追问与答疑（先查数再回答）"),
    Seam("review", SeamRole.PROVIDER, "agent-review",
        "对报告做数字归属/口径/因果/越界的确定性审查"),
    Seam("editorial", SeamRole.PROVIDER, "agent-editorial",
        "编辑部多层级分工流水线（选题会→记者条线→主笔→核查→终审）"),
    Seam("mcp-bridge", SeamRole.PROVIDER, "service",
        "localhost:7787 暴露 MCP Streamable HTTP（Bearer 鉴权）——任何 MCP 宿主（the reference harness / Claude Desktop / Cursor 等）即插即用其能力面"),
)

/** 把 seam 列表导出为 the reference harness 风格的 JSON 数组。 */
fun List<Seam>.toJsonArray(): JsonArray = JsonArray(map { s ->
    buildJsonObject {
        put("id", s.id)
        put("role", s.role.name)
        put("contract", s.contract)
        put("description", s.description)
        if (!s.required) put("required", false)
    }
})
