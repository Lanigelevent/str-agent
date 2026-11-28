package com.stragent.core.semantic

/**
 * 语义层 —— 跨数据集指标定义与约束记忆（S-W6/S-W7 的收口）。
 *
 * 口径记忆（core/caliber）解决的是「用户在追问里纠正过的口径，下次要遵守」；
 * 跨会话记忆（core/memory）解决的是「上一轮聊过什么，这一轮看得见」。
 * 两者都按**数据集内容指纹**失效——换一份数据就召不回来（纪律 c 的代价面）。
 *
 * 语义层补的是它们之间的那段空白：**指标定义**（销售额指的是什么）、
 * **字段口径**（GMV 和销售额的区别）、**业务约束**（退货不算入销售额）
 * 是跨数据集的——用户在 A 数据集上说过「销售额不含退货」，到 B 数据集上
 * 有一列也叫「销售额」时，这条定义应该仍然适用。
 *
 * 三条纪律，与 caliber/memory 同源但**跨数据集**：
 *   a) **语义不是数据**：注入块独立成段，绝不并进 brief —— allowed_numbers
 *      从 brief 全文正则提数字，语义文本混进去等于把定义里的数字升格成
 *      「本次可引用的统计值」，这是最容易自证预言的一条路；
 *   b) **不做提炼**：存的是用户原话截断，不改写、不总结——
 *      总结就是理解，理解就会错，而错的定义下一轮会被当成事实继续用；
 *   c) **跨数据集持久**：键是术语名（归一），不是数据集内容指纹——
 *      换数据不丢语义，但数据集本身变了不影响已定义的术语（两者解耦）。
 *
 * 版本门控（借 EvoOntology 模式，简化版）：
 *   - 每次更新术语，version + 1，旧的定义不删（版本可追溯）；
 *   - 新定义必须比旧定义**更具体**（更长的文本或新增约束），否则不更新
 *     （防止「用户随口一说覆盖了之前认真确认的定义」）。
 */

/** 一条语义术语定义 */
data class SemanticTerm(
    val id: String,
    /** 术语名（如「销售额」「客单价」），归一后是唯一键 */
    val name: String,
    /** 别名列表（如 ["sales", "revenue", "营业收入"]），用于跨数据集列名匹配 */
    val aliases: List<String> = emptyList(),
    /** 定义（用户原话截断，不改写）；为空表示这条只有约束没有定义 */
    val definition: String = "",
    /** 单位（如「元」「万元」「%」）；可空 */
    val unit: String? = null,
    /** 业务约束列表（如「不含退货」「按月统计」「净利润÷营收」） */
    val constraints: List<String> = emptyList(),
    /** 来源：auto（追问里自动抽取的候选）/ followup（追问确认）/ manual（用户手动加的） */
    val source: String,
    /** 版本号：每次更新 +1，从 1 开始 */
    val version: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 单个术语的条数上限：术语是「定义」，不是「笔记」，太多了反而互相矛盾 */
const val SEMANTIC_MAX_TERMS = 50

/** 单条定义的长度上限（字）：超长的是段话，该改 prompt 而不是塞进语义层 */
const val SEMANTIC_MAX_DEF_CHARS = 200

/** 太短的不抽：「销售额」三个字不构成定义 */
private const val MIN_TERM_CHARS = 4

/** 一次注入的最大字数预算：50 条 × 200 字 = 10000 字会吃光上下文 */
const val SEMANTIC_BLOCK_CHAR_BUDGET = 2_000

/**
 * 从一轮用户输入里抽语义术语定义的**候选**。
 *
 * 返回 null 表示这轮不像在定义术语（普通提问、或者话太短说不清）。
 * 返回值是**候选**，不是结论——是否入库必须由用户确认（与 caliber 同纪律 a）。
 *
 * 抽取的是**结构化**候选：术语名 + 定义文本 + 类型（定义/约束），
 * 而不是像 caliber 那样整句存——因为语义层要知道「哪条术语被定义了」，
 * 才能做跨数据集列名匹配。
 *
 * 两类模式：
 *  - **定义**：用户在说「X 指的是 Y」「X 定义为 Y」—— Y 是 X 的定义；
 *  - **约束**：用户在说「X 不含 Y」「X 按 Z 算」—— Y/Z 是 X 的约束。
 *  约束可以叠加到已有术语上（一条术语可以有多个约束）。
 */
data class TermCandidate(
    val name: String,
    val text: String,
    val kind: String, // "definition" | "constraint"
    val reason: String,
)

/** 信号词 + 理由（供审计说明「为什么这条被当成候选」） */
private data class TermSignal(
    val pattern: Regex,
    val reason: String,
    /** 分组索引：1 = 术语名，2 = 定义/约束文本 */
    val nameGroup: Int,
    val textGroup: Int,
)

// 定义类信号：术语 + 定义分隔词 + 定义文本
private val DEFINITION_SIGNALS = listOf(
    TermSignal(Regex("(.{2,12})指的是(.{4,})"), "在下定义", 1, 2),
    TermSignal(Regex("(.{2,12})定义为(.{4,})"), "在下定义", 1, 2),
    TermSignal(Regex("(.{2,12})的口径是(.{4,})"), "在定口径", 1, 2),
    TermSignal(Regex("统一用(.{2,12})表示(.{4,})"), "指定术语", 1, 2),
    TermSignal(Regex("(.{2,12})的?意思是(.{4,})"), "解释含义", 1, 2),
)

// 约束类信号：术语 + 约束分隔词 + 约束文本
private val CONSTRAINT_SIGNALS = listOf(
    TermSignal(Regex("(.{2,12})不含(.{2,})"), "排除范围", 1, 2),
    TermSignal(Regex("(.{2,12})不包括(.{2,})"), "排除范围", 1, 2),
    TermSignal(Regex("(.{2,12})排除(.{2,})"), "排除范围", 1, 2),
    TermSignal(Regex("(.{2,12})按(.{2,})算"), "指定算法", 1, 2),
    TermSignal(Regex("(.{2,12})按(.{2,})计算"), "指定算法", 1, 2),
    TermSignal(Regex("(.{2,12})以(.{2,})为准"), "指定口径", 1, 2),
    TermSignal(Regex("(.{2,12})要扣(.{2,})"), "调整计算", 1, 2),
    TermSignal(Regex("(.{2,12})扣掉(.{2,})"), "调整计算", 1, 2),
    TermSignal(Regex("(.{2,12})不能算(.{2,})"), "排除项", 1, 2),
)

/**
 * 从用户输入中抽取语义术语候选。
 *
 * 与 [extractCaliberHint] 的区别：这里返回**结构化**候选（术语名 + 文本 + 类型），
 * 而不是整句——因为语义层需要知道术语名才能做跨数据集列名匹配。
 *
 * 一句话可能命中多条信号（如「销售额指的是不含退货的净销售」同时命中定义和约束），
 * 此时只取第一个命中（定义优先于约束）——一句话只存一条，避免重复。
 */
fun extractTermHint(text: String): TermCandidate? {
    val trimmed = text.trim()
    if (trimmed.length < MIN_TERM_CHARS) return null

    // 定义优先：用户在定义术语时，整句话就是定义（约束是定义的一部分）
    for (signal in DEFINITION_SIGNALS) {
        val match = signal.pattern.find(trimmed)
        if (match != null) {
            val name = match.groupValues[signal.nameGroup].trim()
            val defText = match.groupValues[signal.textGroup].trim()
            if (name.isNotEmpty() && defText.length >= 4) {
                return TermCandidate(
                    name = name,
                    text = truncateDefinition(defText),
                    kind = "definition",
                    reason = signal.reason,
                )
            }
        }
    }

    // 约束其次：用户在给已有术语加约束
    for (signal in CONSTRAINT_SIGNALS) {
        val match = signal.pattern.find(trimmed)
        if (match != null) {
            val name = match.groupValues[signal.nameGroup].trim()
            val conText = match.groupValues[signal.textGroup].trim()
            if (name.isNotEmpty() && conText.length >= 2) {
                return TermCandidate(
                    name = name,
                    text = truncateDefinition(conText),
                    kind = "constraint",
                    reason = signal.reason,
                )
            }
        }
    }

    return null
}

/** 供审计说明「为什么这条被当成候选」 */
fun termHintReason(text: String): String? {
    val candidate = extractTermHint(text) ?: return null
    return candidate.reason
}

/** 定义文本超长截断（加省略号，让读的人知道话没说完） */
private fun truncateDefinition(text: String): String =
    if (text.length > SEMANTIC_MAX_DEF_CHARS) text.take(SEMANTIC_MAX_DEF_CHARS) + "…" else text

/**
 * 术语名归一：trim → 转小写 → 折叠空白。
 * 用于去重和匹配（大小写不敏感，中文不受影响、英文不再漏判）。
 */
fun normalizeTermName(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), "")

/**
 * 跨数据集列名匹配：已知术语 vs 当前数据集的列名。
 *
 * 匹配规则（按精确度排序，取最精确的命中）：
 *  1. 术语名或别名与列名**精确匹配**（归一后相等）；
 *  2. 术语名或别名是列名的**子串**（如术语「销售额」匹配列「月销售额」）；
 *  3. 列名是术语名或别名的子串（如术语「月销售额」匹配列「销售额」——
 *     这种方向只在前缀/后缀场景有意义，如术语带「月」前缀匹配不带「月」的列）。
 *
 * 没有列名时返回全部术语（追问路径可能不知道列名，语义仍可用）。
 * 返回的术语按匹配精确度排序（精确匹配在前）。
 */
fun matchTermsForColumns(
    terms: List<SemanticTerm>,
    columnNames: List<String>,
): List<SemanticTerm> {
    if (columnNames.isEmpty()) return terms
    val normalizedColumns = columnNames.map { normalizeTermName(it) }
    val result = mutableListOf<Pair<Int, SemanticTerm>>()

    for (term in terms) {
        val termNames = (listOf(term.name) + term.aliases).map { normalizeTermName(it) }
        var bestRank = 0 // 0 = no match, 1 = substring, 2 = exact

        for (termName in termNames) {
            if (termName.isEmpty()) continue
            for (col in normalizedColumns) {
                if (col.isEmpty()) continue
                when {
                    col == termName -> bestRank = maxOf(bestRank, 2)
                    col.contains(termName) || termName.contains(col) -> bestRank = maxOf(bestRank, 1)
                }
            }
        }

        if (bestRank > 0) {
            result.add(bestRank to term)
        }
    }

    return result.sortedByDescending { it.first }.map { it.second }
}

/**
 * 生成注入提示词的语义块。
 *
 * **必须在 brief 与 `<allowed_numbers>` 之外**（纪律 a）：这是用户定义的口径，
 * 不是本次统计。没有术语时返回 null（调用方据此不注入，不留空块占篇幅）。
 *
 * 排序与预算都在这里定死，保证同一组术语 → 同一段文本（可复算、可逐字节对拍）：
 * 按术语名升序（确定性，不依赖时间），超预算的条目整条丢弃。
 */
fun semanticBlock(terms: List<SemanticTerm>): String? {
    if (terms.isEmpty()) return null
    val sorted = terms.sortedBy { normalizeTermName(it.name) }
    val lines = StringBuilder()
    var used = 0
    var kept = 0

    for (term in sorted) {
        val aliasPart = if (term.aliases.isNotEmpty()) "（别名：${term.aliases.joinToString("、")}）" else ""
        val defPart = if (term.definition.isNotEmpty()) "：${term.definition}" else ""
        val constraintPart = if (term.constraints.isNotEmpty()) {
            "（约束：${term.constraints.joinToString("；")}）"
        } else ""
        val unitPart = term.unit?.let { "「单位：$it」" } ?: ""
        val line = "- ${term.name}$aliasPart$defPart$constraintPart$unitPart"

        if (lines.isNotEmpty() && used + line.length + 1 > SEMANTIC_BLOCK_CHAR_BUDGET) break
        lines.append(line).append('\n')
        used += line.length + 1
        kept++
    }

    if (kept == 0) return null
    val dropped = sorted.size - kept
    val tail = if (dropped > 0) "\n（另有 $dropped 条术语因预算未列出）" else ""
    return (
        "<semantic_layer>**此前会话中定义的指标口径，与本次数据列相关时适用**" +
            "（跨数据集沉淀，不随数据更换而丢失）：\n" +
            lines.toString().trimEnd() + tail + "\n" +
            "规则：① 这些是用户定义的口径，**不是数据**——其中出现的数字与名称" +
            "一律不许当作可引用的统计值；② 与本次数据矛盾时以本次为准，并把矛盾" +
            "写出来，不要静默沿用旧定义；③ 用到某条时注明「按用户此前的口径定义」；" +
            "④ 术语定义随数据集变化**不自动失效**——用户在 A 数据集上定义的「销售额" +
            "不含退货」，到 B 数据集上有一列也叫「销售额」时仍应参考。</semantic_layer>"
    )
}

/**
 * 判断新定义是否比旧定义更具体（版本门控：不够具体不更新）。
 *
 * 判据（满足任一即可）：
 *  - 新定义文本更长（信息量更大）；
 *  - 新增了约束；
 *  - 新增了别名（覆盖面更广）；
 *  - 新增了单位（口径更完整）。
 *
 * 这条纪律的目的是防止「用户随口一说覆盖了之前认真确认的定义」——
 * 越具体的定义越有价值，越模糊的定义越可能是用户随口纠正而非正式口径。
 */
fun isMoreSpecific(new: TermCandidate, old: SemanticTerm): Boolean {
    if (new.kind == "constraint") {
        // 约束总是有价值的（叠加到已有术语上，不覆盖定义）
        return old.constraints.none { normalizeTermName(it) == normalizeTermName(new.text) }
    }
    // 定义类：新定义要比旧定义更长才算更具体
    return new.text.length > old.definition.length ||
        old.definition.isEmpty()
}
