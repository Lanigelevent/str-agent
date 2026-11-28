package com.stragent.core.memory

/**
 * 跨会话记忆 —— 纯规则层（存储读写在 `data/SessionMemoryStore`，宿主接线在 `host`）。
 *
 * 要解决的问题很具体：MCP 桥的追问每次都把 `history` 传成空表（见
 * `host/PluginMcpTools` 的 `stragent_ask_followup`），**外部 agent 连续问第二个问题时
 * 看不见第一个问题得出的结论**；进程重启后更是全忘。口径记忆（core/caliber）只覆盖
 * 「用户确认过的口径修正」，补不上这一段。
 *
 * 三条纪律，与口径记忆同源但**更要紧**（记忆是模型自己写的，不是用户确认的）：
 *   a) **记忆不是数据**：注入块独立成段，绝不并进 brief —— `allowed_numbers` 是从 brief
 *      全文正则提数字的，把记忆文本塞进 brief 等于把上一轮模型随口说的数字升格成
 *      「本次可引用的统计值」，这是最容易自证预言的一条路；
 *   b) **不做提炼**：存的是当轮产出（问答对 / 报告要点）的原文截断，不改写、不总结 ——
 *      总结就是理解，理解就会错，而错的理解下一轮会被当成事实继续用；
 *   c) **按内容指纹失效**：记忆键 = 数据集内容派生的 datasetId（`ds_<sha256 前 12 位>`），
 *      数据一改键就变，旧记忆自然召不回来；本层不提供「跨数据集合并记忆」的入口。
 */
data class MemoryFact(
    val id: String,
    /** 种类：report（报告产出）/ followup（追问问答）—— 只有这两类是「会话产出」 */
    val kind: String,
    /** 原文截断（不改写；见纪律 b） */
    val text: String,
    /** 谁写的：mcp / host / manual（供审计，用户有权知道这条是哪来的） */
    val source: String,
    /** 入库时间（epoch millis） */
    val savedAt: Long,
)

/** 单数据集的记忆条数上限：记忆是「最近做过什么」，不是历史档案，堆多了反而互相污染 */
const val MEMORY_MAX_FACTS = 30

/** 单条正文长度上限（字）：报告/回答动辄几千字，进上下文的是**开头一段**，超长按字截断 */
const val MEMORY_MAX_TEXT_CHARS = 240

/** 一次注入的最大字数预算：30 条 × 240 字 = 7200 字会把追问预算吃光，这里再收一道 */
const val MEMORY_BLOCK_CHAR_BUDGET = 2_000

/** 合法的 kind 白名单（未知种类不入库——将来加种类要显式改这里，避免脏值进存储） */
val MEMORY_KINDS = setOf("report", "followup")

/**
 * 正文归一：trim → 折叠连续空白 → 空则 null → 超长按**字**截断加省略号。
 *
 * 返回 null 表示这条不该入库。折叠空白是为了让「重复判定」不被排版差异骗过
 * （同一个问题问两遍、中间多几个空格，就是同一条记忆）；截断保留省略号，
 * 让读的人知道话没说完，而不是以为原文就这么长。
 */
/** 空白折叠（T-142 热路径纪律：预编译单例；每条记忆入库前都要过一遍） */
private val WHITESPACE_RUN = Regex("\\s+")

fun normalizeMemoryText(text: String): String? {
    val collapsed = text.trim().replace(WHITESPACE_RUN, " ")
    if (collapsed.isEmpty()) return null
    return if (collapsed.length > MEMORY_MAX_TEXT_CHARS) collapsed.take(MEMORY_MAX_TEXT_CHARS) + "…" else collapsed
}

/** 重复判定的键：与 normalizeMemoryText 同一次归一（大小写不敏感，中文无影响、英文不再漏判） */
fun memoryDedupeKey(text: String): String? = normalizeMemoryText(text)?.lowercase()

/**
 * 生成注入提示词的记忆块。
 *
 * **必须在 brief 与 `<allowed_numbers>` 之外**（纪律 a）：这是上一轮产出的复述，
 * 不是本次统计。没有记忆时返回 null（调用方据此不注入，不留空块占篇幅）。
 *
 * 排序与预算都在这里定死，保证同一条记忆列表 → 同一段文本（可复算、可逐字节对拍）：
 * 按 savedAt **降序**（新的在前），再按 id 兜底；超预算的条目**整条丢弃**而不是截半句 ——
 * 半句话比没有更误导。
 */
fun memoryBlock(facts: List<MemoryFact>): String? {
    if (facts.isEmpty()) return null
    val ordered = facts.sortedWith(
        compareByDescending<MemoryFact> { it.savedAt }.thenBy { it.id },
    )
    val lines = StringBuilder()
    var used = 0
    var kept = 0
    for (f in ordered) {
        val line = "- [${f.kind}] ${f.text}"
        if (lines.isNotEmpty() && used + line.length + 1 > MEMORY_BLOCK_CHAR_BUDGET) break
        lines.append(line).append('\n')
        used += line.length + 1
        kept++
    }
    if (kept == 0) return null
    val dropped = ordered.size - kept
    val tail = if (dropped > 0) "\n（另有 $dropped 条更早的记忆因预算未列出）" else ""
    return (
        "<session_memory>**此前会话对同一份数据的产出记录**（内容指纹一致才召得回，" +
            "仅作背景，不是本次分析结果）：\n" +
            lines.toString().trimEnd() + tail + "\n" +
            "规则：① 这些文字**不是数据**——其中出现的数字与名称一律不许当作" +
            "可引用的统计值，需要它们就得在本次数据上重新算一遍；② 与本次数据摘要" +
            "或首轮报告矛盾时，以本次为准，并把矛盾写出来，不要静默沿用旧的；" +
            "③ 用到某条时说明「来自此前会话的记忆，本次未复核」。</session_memory>"
    )
}
