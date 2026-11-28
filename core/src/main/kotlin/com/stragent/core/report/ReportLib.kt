package com.stragent.core.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 报告类型 / 风格 / 行业三库 —— TS 侧三个文件的移植。
 *
 * 数据侧：这三个库的 prompt 全文逐字照搬（不改动任何一个字），
 * 由 scripts/sync-report-libs.mjs 从 TS 源码提取生成 ReportLibData.kt，
 * 保证两侧文案字节一致（门 B 的 brief 里会引用这些 prompt 的标签结构）。
 * 选择状态（get/set）走 PrefsStore 端口（Android 侧为 DataStore），
 * 测试传内存 fake——TS 侧的 storage.get 的 localStorage 在这里没有。
 */
data class ReportType(val id: String, val label: String, val hint: String, val prompt: String)
data class ReportStyle(val id: String, val label: String, val hint: String, val tone: String, val length: String, val prompt: String)
data class IndustryProfile(val id: String, val label: String, val category: String, val hint: String, val focus: String, val format: String, val prompt: String)

object ReportLib {
    val reportTypes: List<ReportType> = ReportLibData.reportTypes
    val reportStyles: List<ReportStyle> = ReportLibData.reportStyles
    val industries: List<IndustryProfile> = ReportLibData.industries

    private const val TYPE_KEY = "analysis.reportType"
    private const val STYLE_KEY = "analysis.style"
    private const val INDUSTRY_KEY = "analysis.industry"
    private const val DEPTH_KEY = "analysis.depth"
    private const val AUDIENCE_KEY = "analysis.audience"
    private const val REQ_KEY = "analysis.requirements"

    /** T-105 覆盖键（host 工具层组装 styleOverrides 用；与上面私有键同值公开别名） */
    const val TYPE_PREF_KEY = TYPE_KEY
    const val STYLE_PREF_KEY = STYLE_KEY
    const val INDUSTRY_PREF_KEY = INDUSTRY_KEY
    const val DEPTH_PREF_KEY = DEPTH_KEY
    const val AUDIENCE_PREF_KEY = AUDIENCE_KEY
    const val REQ_PREF_KEY = REQ_KEY

    /** depth 档位（Tableau verbosity/detail 两轴正交先例：depth=篇幅轴） */
    val depths = listOf("brief", "standard", "deep")

    /** depth→篇幅系数（brief ≥300 字下限由调用方 clamp） */
    fun depthFactor(depth: String): Double = when (depth) {
        "brief" -> 0.5
        "deep" -> 1.75
        else -> 1.0
    }

    /** depth→分章块组粒度（GPT Researcher detailed=子主题分解先例：deep=章更细） */
    fun depthGroupSize(depth: String): Int = when (depth) {
        "brief" -> 10
        "deep" -> 4
        else -> 6
    }

    /** T-106 预设 bundle 合法键 → prefs 覆盖键 */
    private val PROFILE_KEYS = mapOf(
        "reportType" to TYPE_KEY,
        "style" to STYLE_KEY,
        "industry" to INDUSTRY_KEY,
        "depth" to DEPTH_KEY,
        "audience" to AUDIENCE_KEY,
        "requirements" to REQ_KEY,
    )

    /**
     * T-106 内联预设 bundle（Langfuse prompt 组合先例）：JSON 对象
     * {"reportType":..,"style":..,"industry":..,"depth":..,"audience":..,"requirements":..}
     * → prefs 覆盖键值对；未知键忽略，无合法键或非法 JSON 返回 null（调用方中文报错）。
     */
    fun parseProfileBundle(json: String): Map<String, String>? = try {
        val obj = Json.parseToJsonElement(json).jsonObject
        val out = obj.entries.mapNotNull { (k, v) ->
            PROFILE_KEYS[k]?.let { key -> key to v.jsonPrimitive.content }
        }.toMap()
        out.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** T-107 已存名预设键前缀（stragent_save_profile 落库处） */
    private const val PROFILE_PREFIX = "analysis.profile."

    /**
     * T-107 预设展开（Langfuse name+label 完整形态）：先查已存名
     * （analysis.profile.<name>），未命中把参数当内联 JSON bundle（T-106）解析。
     */
    fun expandProfile(prefs: com.stragent.core.ports.PrefsStore, profile: String): Map<String, String>? {
        val saved = prefs.get(PROFILE_PREFIX + profile.trim(), "")
        if (saved.isNotBlank()) return parseProfileBundle(saved)
        return parseProfileBundle(profile)
    }

    /** T-107 bundle 值校验（host 内联路径与 saveProfile 共用；无效即中文报错，不静默回退） */
    fun requireValidProfileBundle(bundle: Map<String, String>) {
        bundle[TYPE_KEY]?.let { require(reportTypes.any { t -> t.id == it }) { "profile.reportType 无效：$it（合法值：${reportTypes.joinToString("/") { x -> x.id }}）" } } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        bundle[STYLE_KEY]?.let { require(reportStyles.any { t -> t.id == it }) { "profile.style 无效：$it（合法值：${reportStyles.joinToString("/") { x -> x.id }}）" } } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        bundle[INDUSTRY_KEY]?.let { require(industries.any { t -> t.id == it }) { "profile.industry 无效：$it（合法值：共 ${industries.size} 项 id，如 general/civil/finance）" } } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        bundle[DEPTH_KEY]?.let { require(depths.contains(it)) { "profile.depth 无效：$it（合法值：${depths.joinToString("/")}）" } } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        bundle[AUDIENCE_KEY]?.let { require(it.length <= 300) { "profile.audience 过长：${it.length} 字（上限 300）" } } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        bundle[REQ_KEY]?.let { require(it.length <= 2000) { "profile.requirements 过长：${it.length} 字（上限 2000）" } } // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
    }

    /** T-107 预设落库：名与 bundle 校验通过才写 prefs（工具面前线之外的第二道兜底） */
    fun saveProfile(prefs: com.stragent.core.ports.PrefsStore, name: String, bundleJson: String) {
        require(Regex("[A-Za-z0-9_\\-一-鿿]{1,64}").matches(name)) {
            "profile 名非法：$name（字母/数字/下划线/连字符/中文，≤64 字符）" // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        }
        val bundle = parseProfileBundle(bundleJson)
            ?: throw IllegalArgumentException("profile bundle 非法：应为 JSON 对象，键 reportType/style/industry/depth/audience/requirements") // lint:allow zh-first（用户面中文输出，D19/D21 语言定位）
        requireValidProfileBundle(bundle)
        prefs.set(PROFILE_PREFIX + name, bundleJson.trim())
        // T-108 索引：PrefsStore 端口无枚举能力，落库同步维护名单（覆盖重存去重）
        val index = parseProfileIndex(prefs.get(PROFILE_INDEX_KEY, "[]"))
        val updated = (listOf(name) + index).distinct()
        prefs.set(PROFILE_INDEX_KEY, Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(),
            kotlinx.serialization.json.JsonArray(updated.map { kotlinx.serialization.json.JsonPrimitive(it) })))
    }

    /** T-108 索引键（名单自维护；PrefsStore 无枚举端口） */
    private const val PROFILE_INDEX_KEY = "analysis.profile._index"

    private fun parseProfileIndex(raw: String): List<String> = try {
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
    } catch (_: Exception) {
        emptyList()
    }

    /** T-108 已存预设清单（名 → 原始 bundle JSON；索引缺失项跳过——手工清理后的残项不骗人） */
    fun listProfiles(prefs: com.stragent.core.ports.PrefsStore): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (name in parseProfileIndex(prefs.get(PROFILE_INDEX_KEY, "[]"))) {
            val raw = prefs.get(PROFILE_PREFIX + name, "")
            if (raw.isNotBlank()) out[name] = raw
        }
        return out
    }

    fun getReportType(prefs: com.stragent.core.ports.PrefsStore): ReportType =
        reportTypes.find { it.id == prefs.get(TYPE_KEY, "") } ?: reportTypes[0]

    fun setReportType(prefs: com.stragent.core.ports.PrefsStore, id: String) = prefs.set(TYPE_KEY, id)

    fun getReportStyle(prefs: com.stragent.core.ports.PrefsStore): ReportStyle =
        reportStyles.find { it.id == prefs.get(STYLE_KEY, "") } ?: reportStyles[0]

    fun setReportStyle(prefs: com.stragent.core.ports.PrefsStore, id: String) = prefs.set(STYLE_KEY, id)

    fun getIndustry(prefs: com.stragent.core.ports.PrefsStore): IndustryProfile =
        industries.find { it.id == prefs.get(INDUSTRY_KEY, "") } ?: industries[0]

    fun setIndustry(prefs: com.stragent.core.ports.PrefsStore, id: String) = prefs.set(INDUSTRY_KEY, id)

    /** depth 档位读取（T-105）：未设置或非法值回退 standard */
    fun getDepth(prefs: com.stragent.core.ports.PrefsStore): String {
        val v = prefs.get(DEPTH_KEY, "standard")
        return if (depths.contains(v)) v else "standard"
    }

    /** 受众画像（SciTeller persona 先例）：写给谁，自由文本 */
    fun getAudience(prefs: com.stragent.core.ports.PrefsStore): String = prefs.get(AUDIENCE_KEY, "")

    /** 调用方附加输出要求：总结口径等自由文本（GPT Researcher custom prompt 透传先例） */
    fun getRequirements(prefs: com.stragent.core.ports.PrefsStore): String = prefs.get(REQ_KEY, "")

    /** 按 category 分组（保持出现顺序） */
    fun groupedIndustries(): List<Pair<String, List<IndustryProfile>>> {
        val groups = mutableListOf<Pair<String, MutableList<IndustryProfile>>>()
        for (item in industries) {
            val last = groups.lastOrNull()
            if (last != null && last.first == item.category) last.second.add(item)
            else groups.add(item.category to mutableListOf(item))
        }
        return groups.map { it.first to it.second.toList() }
    }

    fun industryPromptBlock(industry: IndustryProfile): String =
        "\n<industry_framework name=\"${industry.label}\">\n${industry.prompt}\n</industry_framework>"

    fun reportTypePromptBlock(type: ReportType): String =
        "\n<report_type name=\"${type.label}\">\n${type.prompt}\n</report_type>"

    /** length 显式注入并声明优先级（TS stylePromptBlock 的注释纪律原样保留） */
    fun stylePromptBlock(style: ReportStyle): String =
        "\n<writing_style name=\"${style.label}\">\n" +
            "<length>${style.length}——这是本文风的**默认倾向**，写的时候按它控制松紧；" +
            "当上方给出了目标篇幅时**以那个数为准**，" +
            "它已经计入数据规模与报告类型，本条不再另设上限。</length>\n" +
            "${style.prompt}\n</writing_style>"

    /**
     * 提示词块内容净化（T-106，OWASP LLM01 纵深防御）：受众/要求是不可信输入，
     * 不得携带可破坏块边界的标签序列（如 </output_requirements> 提前闭合后注入裸指令）。
     * OWASP 明言转义不是主控（主控=参数校验与权限分离），这里是构造层单点纵深：
     * 已知四类边界标签大小写不敏感剔除 + 控制字符折叠为空格。
     */
    fun sanitizeForPromptBlock(text: String): String {
        var out = text
        for (tag in listOf("output_requirements", "output_persona")) {
            out = out.replace(Regex("(?i)</?$tag>"), " ")
        }
        return out.replace(Regex("[\\p{Cntrl}]"), " ").trim()
    }

    /** 受众画像块（SciTeller persona 先例；内容经 sanitizeForPromptBlock 净化） */
    fun outputPersonaBlock(audience: String): String =
        "\n<output_persona>受众画像（写给谁）：${sanitizeForPromptBlock(audience)}。术语深度与背景交代按此校准；受众不改变事实与数字纪律。</output_persona>" // lint:allow zh-first（用户面中文提示词，T-095 语言定位）

    /** 自由要求块（GPT Researcher custom prompt 透传先例；内容经 sanitizeForPromptBlock 净化） */
    fun outputRequirementsBlock(requirements: String): String =
        "\n<output_requirements>调用方附加输出要求：\n${sanitizeForPromptBlock(requirements)}\n（优先级：事实与合规硬约束 > 本块 > 报告类型 > 行业框架 > 报告风格；本块不得突破零编造、段锚与数字白名单硬约束。）</output_requirements>" // lint:allow zh-first（用户面中文提示词，T-095 语言定位）
}
