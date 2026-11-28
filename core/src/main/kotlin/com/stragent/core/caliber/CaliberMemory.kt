package com.stragent.core.caliber

/**
 * 口径记忆 —— caliber-memory.ts 的纯逻辑移植。
 *
 * 把用户在追问里说的修正存下来，下次生成时**必须遵守**。三条纪律：
 *   a) 只做候选抽取，绝不静默入库（是否入库由用户点确认决定）；
 *   b) 口径不是数据，不进白名单（caliberBlock 是独立块，绝不并进 brief 统计文本——
 *      allowed_numbers 是从 brief 全文正则提取数字的，并进去等于把用户话里的
 *      数字变成"可用数据"）；
 *   c) 可审计（每条备注带来源与时间）。
 *
 * 存储读写（listCaliberNotes 等 CRUD）在 data/ 适配层——core 只管规则本身。
 */
data class CaliberNote(
    val id: String,
    /** 修正内容（用户原话，不改写——改写就可能改掉他的意思） */
    val text: String,
    /** 来源：追问里确认的（followup），还是用户在口径管理里手动加的（manual） */
    val source: String,
    /** 入库时间 */
    val savedAt: Long,
)

/** 单项目的备注条数上限：口径是"规则"不是"笔记"，十几条以上用户自己都记不清 */
const val CALIBER_MAX_NOTES = 20

/** 单条备注的长度上限（字）。超长的"备注"其实是段话，该去改 prompt 而不是塞进口径 */
const val CALIBER_MAX_NOTE_CHARS = 200

/** 太短的不抽："不含上海" 四个字也可能是话没说完。 */
private const val MIN_HINT_CHARS = 8

/**
 * 口径修正的信号词 + 理由（供 UI 说明"为什么这条被当成候选"）。
 *
 * 为什么是这些词：它们都是中文里"我在纠正你 / 我在下定义"的强信号，
 * 且几乎不会在普通的提问里出现（"华东占比多少"不含它们）。
 * 不含"应该是 / 其实是"单独使用的情形：它们也常出现在猜测里
 * （"这应该是华东的数据吧"），抽出来噪声太多——要它们与其它词共现。
 */
private data class Signal(val pattern: Regex, val reason: String)

private val SIGNALS = listOf(
    Signal(Regex("不含|不包括|排除|剔除|除.{0,6}之外"), "明确划出范围外"),
    Signal(Regex("要扣|扣掉|扣除|减去|剔除.{0,8}(后|再)"), "调整计算式"),
    Signal(Regex("口径|定义为|指的是|也就是说|即(?!使)"), "在下定义"),
    Signal(Regex("统一用|按.{1,12}算|以.{1,12}为准"), "指定算法"),
    Signal(Regex("应该是|其实是|准确说|准确来讲|实际上是"), "纠正前述"),
    Signal(Regex("不要算|别算|不能算|不算"), "排除某项"),
)

/**
 * 从一轮用户输入里抽口径修正的**候选**。
 *
 * 返回 null 表示这轮不像在定口径（普通提问、或者话太短说不清）。
 * 返回值是**候选**，不是结论——是否入库必须由用户确认（纪律 a）。
 * 不做"智能提炼"：提炼是理解，理解就会错。把用户原话存下来、原样喂给模型，
 * 让模型自己去应用，比工程猜一个结构化规则安全得多。
 */
fun extractCaliberHint(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.length < MIN_HINT_CHARS) return null
    // 命中任一信号词即认为是候选。多个词命中不再加权——
    // 候选阶段要的是高召回（漏一条用户就得再纠正一遍），精度由用户确认来保
    if (SIGNALS.none { it.pattern.containsMatchIn(trimmed) }) return null
    // 超过长度上限时截断加省略号：仍作为候选给用户看，
    // 让他自己决定是缩短还是放弃（静默丢掉更糟）
    return if (trimmed.length > CALIBER_MAX_NOTE_CHARS) trimmed.take(CALIBER_MAX_NOTE_CHARS) + "…" else trimmed
}

/** 供 UI 说明"为什么这条被当成候选"（用户有权知道判据） */
fun caliberHintReason(text: String): String? =
    SIGNALS.firstOrNull { it.pattern.containsMatchIn(text.trim()) }?.reason

/**
 * 生成 brief 里的口径块。
 *
 * **必须在 `<allowed_numbers>` 之外**（纪律 b）：这段是用户的指令，不是统计事实。
 * 没有备注时返回 null（调用方据此不注入，不留一个空块占篇幅）。
 */
fun caliberBlock(notes: List<CaliberNote>): String? {
    if (notes.isEmpty()) return null
    val lines = notes.joinToString("\n") { "- ${it.text}" }
    return (
        "<user_caliber>**用户在此前对话中明确确认过的口径修正，必须遵守**" +
            "（与上面的统计描述冲突时，以这里为准）：\n" +
            lines +
            "\n规则：① 这些是用户的指令，**不是数据**——里面出现的数字与名称" +
            "一律不许当作可引用的统计值；② 报告用到某条口径时，就近注明" +
            "「按用户此前的口径修正」；③ 口径与数据矛盾到无法调和时，" +
            "写出矛盾并说明你采用了哪一边，不要静默选一边。</user_caliber>"
    )
}
