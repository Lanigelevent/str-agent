package com.stragent.agent.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Str Agent 自描述清单（对齐 DeepSeek Harness 插件模型）。
 *
 * 关键对齐点（官方：https://deepseekdocs.com/en/docs/learn/core/plugin-anatomy）：
 * - [pluginType]：归属 the reference harness 插件类型分类（工具与能力 / 工作流与自动化）。
 * - [inject]：静态声明本插件从宿主消费的「能力缝」（先声明后用，未声明即被拒绝 —— the reference harness capability safety）。
 * - [contributes]：本插件向宿主贡献的能力缝（能力面）。
 * - [toDshManifestJson]：导出严格 the reference harness 形态的清单（dsh 字段），供 the reference harness 风格装载器直接消费。
 *
 * 与 [com.stragent.agent.PluginManifest]（描述「一个 agent 插件」）不同，本类描述
 * 「Str Agent 这个引擎本身作为可被宿主装载的插件」——即插件之插件形态的对外自描述。
 *
 * 本项目是**纯插件类 Agent**，本身不具备独立运行条件：它只声明要消费的宿主缝与贡献的能力缝，
 * 由任意宿主（Kotlin 宿主 [StrAgentHost] / the reference harness 经由 MCP 桥 / 任意 MCP 客户端）装载即插即用。
 */
data class StrAgentPluginManifest(
    val id: String = "str-agent",
    val name: String = "Str Agent",
    val kind: String = "agent-engine",
    val version: String = "1.0.0",
    val schemaVersion: Int = 2,
    val description: String = "数据分析 Agent 引擎：报告/追问/审查/编辑部多 Agent，自带 STEPX 能力桥与 localhost:7787 MCP 服务（任何 MCP 宿主即插即用）。",
    // —— the reference harness 对齐字段（事实来源）——
    val pluginType: List<DshPluginType> = listOf(DshPluginType.TOOLS, DshPluginType.WORKFLOWS),
    val inject: List<Seam> = STR_AGENT_INJECT_SEAMS,
    val contributes: List<Seam> = STR_AGENT_CONTRIBUTE_SEAMS,
    // —— 传统字段（从 seam 派生，保留向后兼容：外部 harness 仍可读 requiredHost/provides）——
    val requiredHost: List<HostRequirement> = inject.map { HostRequirement(it.id, it.contract, it.description) },
    val provides: List<PluginCapability> = contributes.map { PluginCapability(it.id, it.contract, it.description) },
) {
    /** 全量清单（含 the reference harness 字段 + 传统字段）。 */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("type", JsonArray(pluginType.map { JsonPrimitive(it.id) }))
        put("kind", kind)
        put("version", version)
        put("schemaVersion", schemaVersion)
        put("description", description)
        put("inject", inject.toJsonArray())
        put("contributes", contributes.toJsonArray())
        // 传统字段（向后兼容）
        put("requiredHost", JsonArray(requiredHost.map { req ->
            buildJsonObject { put("name", req.name); put("type", req.type); put("description", req.description) }
        }))
        put("provides", JsonArray(provides.map { cap ->
            buildJsonObject {
                put("id", cap.id)
                put("kind", cap.kind)
                put("description", cap.description)
                if (cap.inputSchemaJson != null) put("inputSchema", cap.inputSchemaJson!!)
            }
        }))
    }

    /**
     * 严格 the reference harness 形态清单（dsh 字段），供 the reference harness 风格装载器直接消费。
     * 字段对齐 the reference harness：name / version / description / type / inject / contributes。
     */
    fun toDshManifestJson(): JsonObject = buildJsonObject {
        put("name", id)
        put("version", version)
        put("description", description)
        put("type", JsonArray(pluginType.map { JsonPrimitive(it.id) }))
        put("inject", JsonArray(inject.filter { it.required }.map { JsonPrimitive(it.id) }))
        put("contributes", buildJsonObject {
            put("tools", JsonArray(contributes
                .filter { it.role == SeamRole.PROVIDER && it.contract.startsWith("agent-") }
                .map { buildJsonObject { put("id", it.id); put("description", it.description) } }))
            put("services", JsonArray(contributes
                .filter { it.id == "mcp-bridge" }
                .map { buildJsonObject { put("id", it.id); put("description", it.description) } }))
        })
    }
}
