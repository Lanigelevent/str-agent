package com.stragent.core.report

/**
 * 去 AI 味的写作纪律 —— writing-rules.ts 逐字移植。
 *
 * 注入 prompt 的通用约束，优先于行业格式与写作风格。手段三层：
 * 黑名单（具体词表遵循度远高于抽象指令）+ 正反例（few-shot 锚定语感）+
 * 采样温度 0.7（分析调用，chatStep 侧）。
 */

/**
 * 套话黑名单的**确定性形态**（提示词与写作审计共用；`label` 是黑名单条目原文，
 * drift-guard 测试保证它与 [humanWritingBlock] 提示词文本逐条对得上——
 * 在这里加一条而不改提示词（或反过来）测试会红）。
 */
data class BannedWritingRule(val label: String, val pattern: Regex, val promptAnchor: String = label)

val BANNED_WRITING_RULES: List<BannedWritingRule> = listOf(
    // 开头套话
    BannedWritingRule("随着…的不断发展", Regex("随着[^。\\n]{0,24}(不断|日益|持续|飞速)?发展")),
    BannedWritingRule("在当今…时代", Regex("在当今[^。\\n]{0,16}时代")),
    BannedWritingRule("众所周知", Regex("众所周知")),
    BannedWritingRule("近年来，", Regex("近年来，")),
    // 过渡套话
    BannedWritingRule("综上所述", Regex("综上所述")),
    BannedWritingRule("总而言之", Regex("总而言之")),
    BannedWritingRule("值得注意的是", Regex("值得注意的是")),
    BannedWritingRule("需要指出的是", Regex("需要指出的是")),
    BannedWritingRule("不难发现", Regex("不难发现")),
    BannedWritingRule("由此可见", Regex("由此可见")),
    BannedWritingRule("换言之", Regex("换言之")),
    // 空洞形容
    BannedWritingRule("显著提升", Regex("显著提升")),
    BannedWritingRule("至关重要", Regex("至关重要")),
    BannedWritingRule("深远影响", Regex("深远影响")),
    BannedWritingRule("强有力的支撑", Regex("强有力的支撑")),
    BannedWritingRule("保驾护航", Regex("保驾护航")),
    BannedWritingRule("赋能", Regex("赋能")),
    BannedWritingRule("抓手", Regex("抓手")),
    BannedWritingRule("闭环", Regex("闭环")),
    BannedWritingRule("打通", Regex("打通")),
    BannedWritingRule("沉淀", Regex("沉淀")),
    BannedWritingRule("助力", Regex("助力")),
    // 模板句式（三段以上等长排比 / 首先其次再次最后 串段落——审计只查标记词串）
    BannedWritingRule("首先/其次/再次/最后 串段", Regex("首先[，,][^\\n]{4,}其次[，,][^\\n]{4,}(再次|然后|最后)[，,]"), "首先 / 其次 / 再次 / 最后"),
    // 翻译腔
    BannedWritingRule("进行了…分析", Regex("进行了[^。\\n]{0,12}(分析|研究|梳理)")),
    BannedWritingRule("实现了…增长", Regex("实现了[^。\\n]{0,12}(增长|提升|突破)")),
    BannedWritingRule("对…起到了…作用", Regex("对[^。\\n]{0,16}起到了[^。\\n]{0,8}作用")),
    BannedWritingRule("旨在", Regex("旨在")),
    BannedWritingRule("为…奠定了坚实基础", Regex("奠定了坚实基础")),
)

fun humanWritingBlock(language: ReportLanguage = ReportLanguage.ZH): String = when (language) {
    ReportLanguage.ZH -> humanWritingBlockZh()
    ReportLanguage.EN -> humanWritingBlockEn()
}

private fun humanWritingBlockZh(): String = listOf(
    "",
    // 优先级必须限定作用范围：此前写「本条优先于行业格式与风格要求」，
    // 结果教学讲解的"先铺垫概念"、亲和风格的"生活化解释"被判成背景铺垫删掉——
    // 用户选了教学风格却得到一份高管摘要。
    "<de_ai_style>（本条约束的是**句法与套话**，这一点上优先于行业格式与风格要求；" +
        "但体裁必需的内容结构、概念解释与篇幅要求不受本条约束——" +
        "教学讲解要先解释指标含义、亲和风格要用生活化比喻，这些不算背景铺垫）",
    "一、以下套话一个都不许出现：",
    "- 开头套话：「随着…的不断发展」「在当今…时代」「众所周知」「近年来，…」",
    "- 过渡套话：「综上所述」「总而言之」「值得注意的是」「需要指出的是」「不难发现」「由此可见」「换言之」",
    "- 空洞形容：「显著提升」「至关重要」「深远影响」「强有力的支撑」「保驾护航」「赋能」「抓手」「闭环」「打通」「沉淀」「助力」",
    "- 模板句式：连续用「首先 / 其次 / 再次 / 最后」串段落；三段以上等长排比；通篇「不仅…而且…」",
    "- 翻译腔：「进行了…分析」「实现了…增长」「对…起到了…作用」「旨在…」「为…奠定了坚实基础」",
    "二、像人写的具体要求：",
    "- 第一句话就给结论或最硬的数字，禁止任何背景铺垫（体裁要求先解释概念的除外，只留解释所必需的那句）",
    "- 长短句交错：可以有 5–10 字的短句独立成段，也可以有 40 字以上的长句，不允许每句长度差不多",
    "- 每个关键数字后面跟一句「这说明什么」的人话解读，禁止只罗列数字不解释（报告类型要求极简时，解读压到半句，但不能删）",
    "- 有立场、有取舍：能判断「这个增长不可持续」「这个峰值是统计假象」就直接说，禁止「既有机遇也有挑战」这类两头堵的废话",
    "- 段落长度参差不齐，允许一段只有一句话",
    "- 结尾落在具体的下一步动作或一个明确判断上，禁止升华、禁止「未来可期」式展望",
    "- 一个观点只说一次，禁止换一种措辞反复重述同一个意思",
    "- 可以有分析师口吻的主观判断（「更可能的解释是…」「我倾向于认为…」），比全程客观罗列更像人",
    "- 全文允许出现 1–2 处设问句（「增长从哪来？」），用来带节奏；超过 2 处就成了主持稿",
    "- 用词口语化与书面语混搭：可以有「说白了」「坏消息是」这类过渡，禁止全程书面语",
    "三、正反对照（严格按正例的语感写，这是本节最重要的要求）：",
    "反例（AI 味，禁止这样写）：随着市场竞争的不断加剧，企业对数据驱动决策的重视程度日益提升，销售额呈现出稳步增长的良好态势，为后续发展奠定了坚实基础。",
    "正例（人话，照这个语感写）：三个月里销售额从 N 爬到 N，涨了 N%。增长全部来自 A 区，B 区原地踏步。先查 A 区做对了什么，再决定要不要复制到 B 区。",
    "</de_ai_style>",
).joinToString("\n")

/**
 * 英文版去 AI 味纪律（T-173）。
 *
 * 不是中文版的翻译——英文的 AI 腔有自己的一套形态（hedging 开头、名词化、"leverage"、
 * "aims to" 之类），词表与 [BANNED_WRITING_RULES_EN] 一一对应（drift-guard 钉住）。
 */
private fun humanWritingBlockEn(): String = listOf(
    "",
    "<de_ai_style>(This constrains **syntax and filler**, and on that point it outranks format and style requests; " +
        "content structure, concept explanations and length requirements are not affected)",
    "I. None of the following filler phrases may appear:",
    "- Openers: \"In today's … era\", \"It is well known that\", \"In recent years, …\", \"With the rapid development of …\"",
    "- Transitions: \"To sum up\", \"It is worth noting that\", \"It should be pointed out that\", \"It is not difficult to find that\", \"As can be seen\", \"In other words\"",
    "- Empty adjectives: \"significantly improved\", \"crucial\", \"profound impact\", \"strong support\", \"empower\", \"leverage\", \"closed loop\", \"consolidate\", \"facilitate\"",
    "- Template sentences: running \"First, / Second, / Third,\" through a paragraph; three or more equal-length parallel clauses; \"not only … but also …\" everywhere",
    "- Translationese: \"conducted an analysis of\", \"aims to\", \"laid a solid foundation\"",
    "II. Write like a person:",
    "- Open with the conclusion or the hardest number. No background preamble (unless the genre requires explaining a concept first)",
    "- Vary sentence length: 5-10 word sentences standing alone and 40+ word sentences are both fine",
    "- After every key number, add one plain sentence on what it means. Never list numbers without interpreting them",
    "- Take a position: if the growth is unsustainable or the peak is a statistical artefact, say so",
    "- Paragraphs may be uneven; a paragraph of one sentence is allowed",
    "- End on a concrete next action or a clear judgement. No grand conclusions, no \"the future looks bright\"",
    "- Say each point once; do not restate it in different words",
    "- A little analyst voice (\"the more likely explanation is …\") reads more human than neutral listing",
    "III. Contrast (match the positive example's voice — this section matters most):",
    "Bad (AI-ish, do not write like this): With the rapid development of the market and the increasing emphasis on data-driven decision-making, sales have shown a steady growth trend, laying a solid foundation for future development.",
    "Good (write like this): Sales went from N to N in three months, up N%. All of the growth came from region A; region B did not move. Check what A did before copying it to B.",
    "</de_ai_style>",
).joinToString("\n")
