package com.stragent.agent

import java.io.File

/**
 * 插件注册表：4 个内置插件（随包）+ 用户导入的 JSON 清单（filesDir/plugins/）。
 *
 * "万物皆插件"的落点：报告引擎 / 追问引擎 / 审查引擎都从这里的清单实例化，
 * 用户可导入导出自己的提示词包；内置 id 不可被用户插件覆盖（防篡改语义）。
 */
class PluginRegistry(private val userDir: File?) {

    /** 内置插件（随包清单；id 恒定） */
    val builtIns: List<PluginManifest> = listOf(
        PluginManifest(
            id = "report-deepdive",
            name = "Agent · 深挖报告",
            kind = "report",
            systemPrompt = "<plugin_role>自主完成一份**深挖型**数据分析报告。</plugin_role>\n" +
                "工作流（按序执行）：\n" +
                "1. submit_plan 登记 4–8 步分析计划；\n" +
                "2. get_overview 与 get_quality 摸底，再按「整体 → 分组 → 时序 → 交叉 → 相关」查证；\n" +
                "3. 每得到一条值得写的发现就 record_finding（title = 一句话结论，detail = 数据依据与机制，evidence = 列/分组/行号）；\n" +
                "4. 根据 findings 写草稿；\n" +
                "5. **audit_report 自检**——把未溯源数字与算式错误修掉或删除对应结论（审计不过不允许 finish）；\n" +
                "6. finish 输出全文。\n" +
                "报告纪律：每条发现 = 一句话结论 + 数据依据 + 为什么重要；至少一条挖到机制（结论里出现\"因为…\"）；" +
                "有数值列时至少一条跨字段发现；反直觉只在数据真支持时才写；" +
                "先给结论再给数字，不写\"随着…的不断发展\"式开头；宁可少一条，不要凑数。\n" +
                "finish.args.report = 完整 Markdown 报告（从 ## 总体概览 开始）。",
            tools = null,
            temperature = 0.7,
            maxSteps = 18,
        ),
        PluginManifest(
            id = "report-standard",
            name = "Agent · 标准报告",
            kind = "report",
            systemPrompt = "<plugin_role>自主完成一份**标准型**数据分析报告（精简工具面，快速成稿）。</plugin_role>\n" +
                "结构：总体概览 → 分组对比 → 趋势 → 结论与建议。每条结论带数字与口径；不铺开讲方法论。\n" +
                "finish.args.report = 完整 Markdown 报告。",
            tools = listOf(
                "get_overview", "get_column_profile", "group_aggregate", "audit_report",
                "scan_anomalies", "get_outliers", "get_report_context",
            ),
            temperature = 0.7,
            maxSteps = 10,
        ),
        PluginManifest(
            id = "followup",
            name = "Agent · 追问答疑",
            kind = "followup",
            systemPrompt = "<plugin_role>回答用户对这份数据的追问。</plugin_role>\n" +
                "先查数再回答：需要具体数字时用工具查证，不许凭首轮报告的印象报数。" +
                "回答两三句话，直接给结论与数字；数据里没有的如实说「这份数据里没有这个信息」。\n" +
                "finish.args.answer = 回答正文（纯文本，不用标题）。",
            tools = listOf(
                "get_column_profile", "group_aggregate", "get_series",
                "drill_down", "get_outliers", "scan_anomalies", "get_report_context", "suggest_chart",
            ),
            temperature = 0.5,
            maxSteps = 8,
        ),
        PluginManifest(
            id = "review",
            name = "Agent · 语义审查",
            kind = "review",
            systemPrompt = "<plugin_role>你是审稿人，专查**数字的归属与口径错误**。</plugin_role>\n" +
                "逐句对照报告与数据摘要，只报四类问题：归属错误（整体说成分组）/ 口径错误（同比环比混用、" +
                "分母错）/ 因果倒置（相关说成因果）/ 越界断言（预测、下诊断）。" +
                "不要报风格、篇幅、章节结构；只报确有问题、拿不准就不报；quote 必须与报告原文**逐字一致**（程序靠它定位）。\n" +
                "finish.args.issues = {\"issues\":[{\"kind\":\"归属错误|口径错误|因果倒置|越界断言\"," +
                "\"quote\":\"≤40 字逐字片段\",\"why\":\"≤50 字\"}]}，没有问题就空数组。",
            tools = emptyList(),
            temperature = 0.2,
            maxSteps = 4,
        ),
        PluginManifest(
            id = "editorial-deepdive",
            name = "编辑部 · 多层级深挖",
            kind = "editorial",
            systemPrompt = "<plugin_role>你是主编。选题会上只做一件事：把这份数据拆成 3–5 条**互不重叠**的记者条线" +
                "（如：分组与结构、时序与异常、质量与口径），用 submit_plan 提交计划后立即 finish。</plugin_role>",
            tools = emptyList(),
            temperature = 0.5,
            maxSteps = 8,
            writerPrompt = "<plugin_role>你是主笔。基于全社采编日志与数据摘要汇编一份**深挖型**报告。</plugin_role>\n" +
                "纪律：日志里的发现是骨架，不是全文——补上机制解释与段落衔接；每条发现 = 一句话结论 + 数据依据 + 为什么重要；" +
                "两个条线的结论矛盾时必须指出矛盾而不是各说各话；先结论后数字，不写套话开头；宁可少写，不要注水。",
            chaptered = true,
            beats = listOf(
                EditorialBeat(
                    name = "分组与结构",
                    prompt = "你负责**分组与结构**条线：哪个组贡献最大、结构占比变化、组内下钻。" +
                        "用 group_aggregate / get_cross_tab / drill_down / get_group_yoy 查证。",
                    tools = listOf("get_overview", "get_column_profile", "group_aggregate", "get_cross_tab", "drill_down", "get_group_yoy"),
                ),
                EditorialBeat(
                    name = "时序与异常",
                    prompt = "你负责**时序与异常**条线：月度趋势、同比、突变归因、六类异常。" +
                        "用 get_series / scan_anomalies / get_attribution_chain / get_group_trend 查证。",
                    tools = listOf("get_overview", "get_series", "scan_anomalies", "get_attribution_chain", "get_group_trend"),
                ),
                EditorialBeat(
                    name = "质量与口径",
                    prompt = "你负责**质量与口径**条线：空值/重复/常数列、极值定位、相关性。" +
                        "用 get_quality / get_outliers / get_extremes / get_correlations 查证；" +
                        "发现里必须写清口径（哪些结论受数据质量问题影响）。",
                    tools = listOf("get_overview", "get_quality", "get_outliers", "get_extremes", "get_correlations"),
                ),
            ),
        ),
    )

    /** 全部插件：内置在前，用户按导入序在后 */
    fun list(): List<PluginManifest> = builtIns + user()

    fun get(id: String): PluginManifest? = list().find { it.id == id }

    /** 用户插件（内置不可覆盖：同 id 导入报错） */
    fun user(): List<PluginManifest> {
        val dir = userDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching { PluginManifest.parse(file.readText()) }.getOrNull()
            }
            ?.filter { manifest -> builtIns.none { it.id == manifest.id } }
            ?: emptyList()
    }

    /** 导入（存为 JSON 文件）；id 与内置冲突时拒绝 */
    fun import(text: String): PluginManifest {
        val manifest = PluginManifest.parse(text)
        if (builtIns.any { it.id == manifest.id }) {
            throw IllegalArgumentException("插件 id \"${manifest.id}\" 与内置插件冲突，请换一个 id")
        }
        val dir = userDir ?: throw IllegalArgumentException("本机不支持用户插件")
        dir.mkdirs()
        File(dir, "${manifest.id}.json").writeText(manifest.toJson().toString(), Charsets.UTF_8)
        return manifest
    }

    fun delete(id: String): Boolean {
        if (builtIns.any { it.id == id }) return false
        val file = File(userDir ?: return false, "$id.json")
        return file.exists() && file.delete()
    }

    companion object {
        /** 用户插件目录（filesDir/plugins） */
        fun userDir(filesDir: File): File = File(filesDir, "plugins")
    }
}
