package com.stragent.core.ports

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent 工具 —— harness 的最小单元。一个工具 = 名称 + 描述 + JSON Schema 参数
 * + 无副作用的执行体。三类来源共用这一个接口：
 *   1. 分析纯函数适配（BuiltInTools：列名解析 + data class → JSON）
 *   2. 能力层适配（data/ 的四条 stragent_* 契约）
 *   3. 环境工具（web_search / report_context / finish）
 *
 * 纪律与能力层一致：错误抛 IllegalArgumentException（中文、面向用户/模型都能读），
 * 返回 JsonObject——"每个数字能回到原始行/出处"的口径在各自实现里保持。
 */
interface AgentTool {
    val name: String
    val description: String
    /** JSON Schema（properties/required），供工具目录与校验 */
    val parametersSchema: JsonObject

    /**
     * 输出 Schema（MCP 2026 规范 outputSchema 契约）。
     *
     * T-056：**所有工具都声明输出契约**。默认是宽松对象结构
     * `{"type":"object","additionalProperties":true}`——只承诺「返回一个 JSON 对象」，
     * 不把临时字段固化成契约；结构稳定的工具用 [com.stragent.core.ports.schemaObject]
     * 收紧成有名字段的 schema（`tools/call` 一律双发 `structuredContent`）。
     */
    val outputSchema: JsonObject get() = PERMISSIVE_OUTPUT_SCHEMA

    /**
     * 是否可缓存：相同 (name, args) 的重复调用直接返回缓存结果（生产纪律：
     * agent 偶发重复查询不该重复付费/耗时）。有状态工具（计划/发现日志）
     * 必须覆写为 false。
     */
    val cacheable: Boolean get() = true

    /**
     * MCP annotations（行为提示，不是安全保证）：
     *  - [readOnly] 不改变任何状态；导入/导出/写盘/执行代码必须显式覆写为 false
     *  - [destructive] 可能覆盖或删除已有数据、执行任意代码；默认 false
     *  - [openWorld] 与外部实体交互（模型、网络、任意代码）；默认 false
     *  - `idempotentHint` 取 [cacheable]（相同参数重复调用不产生额外效果）
     *
     * 逐工具覆写而不是在桥接层统一贴标签：工具知道自己的副作用，桥接层不知道。
     * 客户端按 MCP 规范必须把 annotations 当**不可信提示**，不能当权限边界。
     */
    val readOnly: Boolean get() = true
    val destructive: Boolean get() = false
    val openWorld: Boolean get() = false

    val annotations: JsonObject
        get() = buildJsonObject {
            put("title", name)
            put("readOnlyHint", readOnly)
            put("destructiveHint", destructive)
            put("idempotentHint", cacheable)
            put("openWorldHint", openWorld)
        }

    fun invoke(args: JsonObject): JsonObject

    companion object {
        /**
         * 宽松对象契约（T-056 默认）：只承诺「返回一个 JSON 对象」，字段不固化。
         * 客户端可据此使用 `structuredContent`；要逐字段校验的工具另收紧 schema。
         */
        val PERMISSIVE_OUTPUT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", true)
            put("description", "宽松对象契约：字段随工具结果变化，不逐字段固化")
        }
    }
}

/** 工具执行异常的统一出口（loop 把 message 回填给模型） */
class ToolException(message: String) : IllegalArgumentException(message)

/**
 * 工具注册表：注册 + 白名单过滤 + 目录序列化。
 * 白名单为 null = 全部开放；名字不在表中的白名单项忽略（插件写错不崩）。
 */
class ToolRegistry private constructor(private val tools: Map<String, AgentTool>) {

    fun get(name: String): AgentTool? = tools[name]

    fun all(): List<AgentTool> = tools.values.sortedBy { it.name }

    /** 按白名单解析（保序），未命中项忽略 */
    fun resolve(allowlist: List<String>?): List<AgentTool> {
        if (allowlist == null) return all()
        return allowlist.mapNotNull { tools[it] }
    }

    /** 工具目录（进 system prompt 的 JSON 数组），含 outputSchema 契约（MCP 2026 规范） */
    fun catalogJson(allowlist: List<String>?): String =
        resolve(allowlist).joinToString("\n") { tool ->
            val base = """{"name":"${tool.name}","description":"${tool.description}","parameters":${tool.parametersSchema}"""
            val outSchema = tool.outputSchema
            if (outSchema != null) base + ""","outputSchema":${outSchema}}"""
            else base + "}"
        }

    companion object {
        fun of(tools: List<AgentTool>): ToolRegistry {
            val map = LinkedHashMap<String, AgentTool>()
            for (tool in tools) {
                require(tool.name.isNotBlank()) { "工具名不能为空" }
                check(!map.containsKey(tool.name)) { "工具重名: ${tool.name}" }
                map[tool.name] = tool
            }
            return ToolRegistry(map)
        }
    }
}
