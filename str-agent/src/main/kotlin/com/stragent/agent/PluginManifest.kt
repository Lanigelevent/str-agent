package com.stragent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 插件清单（"万物皆插件"的插件单位）——一段 JSON 描述一个 agent：
 *   { "id": "report-deepdive", "name": "深挖报告", "kind": "report",
 *     "systemPrompt": "……", "tools": ["get_column_profile", …] | 省略=全部,
 *     "model": null, "temperature": 0.3, "maxSteps": 14 }
 *
 * kind 决定终结工具的输出契约：
 *   report    → finish.args.report（Markdown 全文）
 *   followup  → finish.args.answer（两三句话）
 *   review    → finish.args.issues（[{kind,quote,why}]）
 *   editorial → 编辑部多层级分工（报社模式）：chief 选题会 → 记者 beat 采访 →
 *               主笔成稿 → 确定性核查 → 主编终审。需要 chiefPrompt/writerPrompt/beats。
 * 不做动态代码加载——插件化落在 manifest 层，安全边界清晰。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val kind: String,
    /** 插件提示词包（追加在 harness 契约与偏好块之后）；editorial 时作为主编（chief）提示词 */
    val systemPrompt: String,
    /** 工具白名单；null = 全部开放 */
    val tools: List<String>? = null,
    /** 模型覆盖（null = 用户当前选择的模型） */
    val model: String? = null,
    val temperature: Double = 0.3,
    /** agent 循环步数预算（防失控；editorial 时为每名员工的步数预算） */
    val maxSteps: Int = 10,
    /** editorial：主笔提示词（成稿人） */
    val writerPrompt: String? = null,
    /** editorial：记者条线（每条一个 beat，各自带提示词与工具子集） */
    val beats: List<EditorialBeat> = emptyList(),
    /**
     * editorial：主笔**分章成稿**（T-032）。开启后长报告不再一次成稿——
     * 用 `planChapters` 定章节骨架（程序定结构、模型只填内容），每章一次调用，
     * 逐章拼接后再进核查；短报告（目标 <2000 字）与短体型类型自动回落单次成稿。
     * 默认关闭：分章增加调用次数，由插件显式选择（内置 deepdive 编辑部开启）。
     */
    val chaptered: Boolean = false,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9\\-_.]{0,63}"))) { "插件 id 只允许小写字母/数字/-_/.（1–64 位）: $id" }
        require(name.isNotBlank()) { "插件名称不能为空" }
        require(kind in PluginKinds.ids()) { "未知插件类型: $kind（允许: ${PluginKinds.ids().joinToString("/")}）" }
        require(systemPrompt.isNotBlank()) { "systemPrompt 不能为空" }
        require(temperature in 0.0..2.0) { "temperature 超出 0–2" }
        require(maxSteps in 1..40) { "maxSteps 需在 1–40" }
        if (kind == "editorial") {
            require(beats.isNotEmpty()) { "editorial 插件需要至少一个记者条线（beats）" }
            require(beats.size <= 6) { "记者条线过多（${beats.size}），上限 6" }
            require(writerPrompt != null && writerPrompt.isNotBlank()) { "editorial 插件需要 writerPrompt（主笔提示词）" }
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("kind", kind)
        put("systemPrompt", systemPrompt)
        if (tools != null) put("tools", JsonArray(tools.map { JsonPrimitive(it) }))
        if (model != null) put("model", model)
        put("temperature", temperature)
        put("maxSteps", maxSteps)
        if (kind == "editorial") {
            put("writerPrompt", writerPrompt)
            if (chaptered) put("chaptered", true)
            put("beats", JsonArray(beats.map { beat ->
                buildJsonObject {
                    put("name", beat.name)
                    put("prompt", beat.prompt)
                    if (beat.tools != null) put("tools", JsonArray(beat.tools.map { JsonPrimitive(it) }))
                }
            }))
        }
    }

    companion object {
        /** 允许的 kind（来自 [PluginKinds] 登记表；新增 kind 只登记，不改本类） */
        val KINDS: List<String> get() = PluginKinds.all().map { it.id }

        /** 解析 + 校验（用户导入/内置资产共用） */
        fun parse(text: String): PluginManifest {
            val root = runCatching { Json.parseToJsonElement(text) }
                .getOrElse { throw IllegalArgumentException("不是合法的 JSON：${it.message}") }
            if (root !is JsonObject) throw IllegalArgumentException("插件清单需要是 JSON 对象")
            val id = root.str("id") ?: throw IllegalArgumentException("缺少 id")
            val kind = root.str("kind") ?: "report"
            return PluginManifest(
                id = id,
                name = root.str("name") ?: id,
                kind = kind,
                systemPrompt = root.str("systemPrompt") ?: "",
                tools = (root["tools"] as? JsonArray)?.map { it.jsonPrimitive.content },
                model = root.str("model"),
                temperature = root["temperature"]?.let { (it as? JsonNull ?: it).toString() }?.toDoubleOrNull() ?: 0.3,
                maxSteps = root["maxSteps"]?.let { (it as? JsonNull ?: it).toString() }?.toIntOrNull() ?: 10,
                writerPrompt = root.str("writerPrompt"),
                chaptered = root.str("chaptered") == "true",
                beats = (root["beats"] as? JsonArray)?.mapNotNull { beatElement ->
                    val beat = beatElement as? JsonObject ?: return@mapNotNull null
                    EditorialBeat(
                        name = beat.str("name") ?: return@mapNotNull null,
                        prompt = beat.str("prompt") ?: "",
                        tools = (beat["tools"] as? JsonArray)?.map { it.jsonPrimitive.content },
                    )
                } ?: emptyList(),
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}

/** 编辑部的一个记者条线：名字（如「分组与结构」）+ 提示词 + 工具子集 */
data class EditorialBeat(val name: String, val prompt: String, val tools: List<String>? = null)
