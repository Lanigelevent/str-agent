package com.stragent.core.report

/**
 * 报告类型的形态参数（T-058 / D17 G-19）——把原先散在四处 `when (typeId)` /
 * `setOf("standard", …)` 里的**类型行为**收成一张登记表：
 *
 *  - `StructureRules`：结构化类型（速览表/适用范围/未答问题）
 *  - `TargetLength`：短体型上限与篇幅系数
 *  - `DatasetBrief`：brief 预算系数
 *  - `ReportChapters`：章节模板与「无分组维度时的列级解读」特例
 *
 * 纪律：新增报告类型只改**数据**（ReportLibData 的类型清单）与**注册**
 * （[ReportTypeRegistry.with] / [ReportTypeRegistry.BUILT_IN]），不改上述四个分发函数。
 * 未登记的类型按 [UNKNOWN] 口径（结构化、1.1 篇幅系数、standard 章节模板）——
 * 这是保守默认，不会因为漏登记而把新类型当成短体型砍到 400 字。
 */
data class ReportTypeProfile(
    val id: String,
    /** 是否需要章节级专业形态（速览表 / 适用范围 / 未答问题 / 依据标注） */
    val structured: Boolean = true,
    /** 短体型：目标篇幅恒按文风字数上限，不进「可引用事实数」公式（executive/bulletin） */
    val shortForm: Boolean = false,
    /** 目标篇幅系数（原 TargetLength 里的 typeFactor） */
    val lengthFactor: Double = 1.1,
    /** brief 预算系数（原 DatasetBrief 里的 typeFactor） */
    val briefFactor: Double = 1.0,
    /** 章节模板键（ReportChapters.TEMPLATES）；未命中回落 standard */
    val templateKey: String = "standard",
    /** 无分组维度时把「分组维度深挖」换成「列级指标解读」（standard 专属文法） */
    val columnChapterWhenNoDimensions: Boolean = false,
)

/** 不可变注册表；`with` 返回新表（组合根装配一次，并发读安全） */
class ReportTypeRegistry(profiles: List<ReportTypeProfile>) {
    private val byId: Map<String, ReportTypeProfile> = profiles.associateBy { it.id }

    fun profileOf(typeId: String): ReportTypeProfile = byId[typeId] ?: UNKNOWN

    /** 已登记的全部 profile（顺序 = 登记顺序） */
    val entries: List<ReportTypeProfile> get() = byId.values.toList()

    /** 登记/覆盖一种类型（新增报告类型 = 调一次这里，不改分发函数） */
    fun with(profile: ReportTypeProfile): ReportTypeRegistry = ReportTypeRegistry(byId.values + profile)

    companion object {
        /** 未登记类型的保守默认（见类注释） */
        val UNKNOWN: ReportTypeProfile = ReportTypeProfile(id = "*")

        /** 随包 8 种报告类型的形态参数（值与原四处硬编码逐条对齐） */
        val BUILT_IN: ReportTypeRegistry = ReportTypeRegistry(
            listOf(
                ReportTypeProfile("standard", columnChapterWhenNoDimensions = true),
                ReportTypeProfile("executive", structured = false, shortForm = true, briefFactor = 0.75),
                ReportTypeProfile("bulletin", structured = false, shortForm = true, briefFactor = 0.75),
                ReportTypeProfile("deepdive", lengthFactor = 1.5, briefFactor = 1.5, templateKey = "deepdive"),
                ReportTypeProfile("trend", lengthFactor = 1.3, briefFactor = 1.3, templateKey = "trend"),
                ReportTypeProfile("compare", lengthFactor = 1.3, briefFactor = 1.3, templateKey = "compare"),
                ReportTypeProfile("anomaly", lengthFactor = 1.3, briefFactor = 1.3, templateKey = "anomaly"),
                ReportTypeProfile("quality", lengthFactor = 1.2, briefFactor = 1.2, templateKey = "quality"),
            ),
        )
    }
}
