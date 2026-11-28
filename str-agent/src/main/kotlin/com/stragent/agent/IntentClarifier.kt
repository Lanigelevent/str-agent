package com.stragent.agent

/**
 * 意图澄清闸 —— 确定性启发式（str-agent 模块层，core 不感知）。
 *
 * 对标 dev-agent 的 intent-parse 三键纪律（ambiguity / assumptions / confirmations）：
 * 外部 agent 经 MCP 传来的追问可能是悬空指代、复合请求或过短泛问——
 * 直接猜着答就是「上一轮说的它」指错对象还编得一本正经。
 *
 * 三类信号（全部显式关键词/长度判据，判据写进证据，不脑补）：
 *   a) 悬空指代（anaphora）：「它/那个/上面提到的…」——回答前必须声明指代落点；
 *   b) 复合请求（compound）：「然后/并且/另外还要…」——拆成子问题逐个答；
 *   c) 过短/泛问（vague）：不足 6 字，或「随便/怎么样/分析一下」——
 *      多种合理解读时不得猜答，只问一个最关键的澄清问题。
 *
 * 无信号时 gateBlock 返回 null：调用方据此不注入，请求形态与旧版逐字节一致
 * （可选参数默认值纪律，与 T-008 memory 同款——默认零行为差异）。
 */
data class IntentSignal(
    /** 种类：anaphora / compound / vague */
    val kind: String,
    /** 命中的证据（原词或判据说明） */
    val evidence: String,
)

data class IntentAssessment(
    val signals: List<IntentSignal>,
) {
    val isVague: Boolean get() = signals.isNotEmpty()

    /**
     * 注入提示词的澄清闸块：四条纪律（选定解读+被拒备选 / 假设清单 / 拆解 / 单问）。
     * 无信号返回 null——不留空块占篇幅。
     */
    fun gateBlock(): String? {
        if (signals.isEmpty()) return null
        val evidence = signals.joinToString("；") { "${it.kind}（${it.evidence}）" }
        return (
            "<intent_gate>这个问题表述含糊（$evidence）。回答前按此纪律：\n" +
                "① 先声明你选定的解读与依据；\n" +
                "② 列出被你拒掉的备选解读（各一句话）——落选解读是给用户复核的，不许静默丢弃；\n" +
                "③ 列出你采用的假设，并说明假设错了结论会怎么变；\n" +
                "④ 复合请求拆成子问题逐个回答，不许只答一半也不许合并成一句糊过去；\n" +
                "⑤ 多种解读会导出根本不同的答案时，不要猜答——只问一个最关键的澄清问题。\n" +
                "以上说明写进回答正文开头，让用户能一眼复核你的理解。</intent_gate>"
        )
    }
}

object IntentClarifier {
    /** 悬空指代词表：出现即声明指代落点（上下文里可能能解，但解对了也要说出来） */
    private val ANAPHORA = setOf("它", "它们", "这个", "那个", "这些", "那些", "上面", "刚才", "之前提到", "上次")

    /** 复合请求连接词：出现即拆子问题 */
    private val COMPOUND = setOf("然后", "接着", "并且", "同时", "另外", "还要", "以及", "顺便", "再来一个", "分别")

    /** 泛问词表：出现即视为多种合理解读 */
    private val VAGUE = setOf("随便", "怎么样", "如何", "分析一下", "看看", "帮我看看", "总结一下", "大概", "有些", "好像")

    /** 过短阈值（字）：短于它的追问几乎必然缺主语或缺对象 */
    private const val MIN_CHARS = 6

    fun analyze(text: String): IntentAssessment {
        val trimmed = text.trim()
        val signals = mutableListOf<IntentSignal>()
        ANAPHORA.firstOrNull { trimmed.contains(it) }?.let {
            signals.add(IntentSignal("anaphora", "悬空指代「$it」"))
        }
        COMPOUND.firstOrNull { trimmed.contains(it) }?.let {
            signals.add(IntentSignal("compound", "复合连接「$it」"))
        }
        VAGUE.firstOrNull { trimmed.contains(it) }?.let {
            signals.add(IntentSignal("vague", "泛问表述「$it」"))
        }
        if (trimmed.isNotEmpty() && trimmed.length < MIN_CHARS) {
            signals.add(IntentSignal("vague", "过短（${trimmed.length} 字 < $MIN_CHARS）"))
        }
        return IntentAssessment(signals)
    }
}
