package com.stragent.core.security

import com.stragent.core.dataset.Dataset
import com.stragent.core.dataset.TextContent

/**
 * 单条注入命中（T-067 / D21 S-1）。
 *
 * @param rule 规则 id（R1–R8）
 * @param ruleName 规则中文名（回执/UI 可读）
 * @param location 命中位置（如「列名[备注]」「第12行[列:客户留言]」）
 * @param sample 命中片段（截断 80 字符、压平换行——回执可见但不放大载荷）
 */
data class InjectionSignal(
    val rule: String,
    val ruleName: String,
    val location: String,
    val sample: String,
)

/** 一次数据面扫描的结果（[signals] 按 R1→R8 与出现顺序排列，总量有上限） */
data class InjectionScanReport(
    val scannedRows: Int,
    val signals: List<InjectionSignal>,
) {
    val hitCount: Int get() = signals.size
}

/**
 * 数据面 prompt-injection 启发式检测（T-067）。
 *
 * 背景与边界（D21 §2.4 S-1）：数据集内容（列名/样本值）会回流进提示词；
 * MCP 生态已把内容级注入列为系统性威胁（NSA/CSA/Invariant Labs，见 D21 §7）。
 * 本检测的纪律是**「命中 ≠ 拦截」**：数据仍然是数据，绝不能因为「长得像指令」
 * 就改动分析结果——它只做三件事：
 *  ①命中项**不进简报样本段**（替换为 `[已过滤:Rn]` 占位符，见 DatasetBrief）；
 *  ②命中明细随回执可见（`injectionScan` / `injectionSignals`）；
 *  ③绝不改数字、不改统计、不改白名单。
 *
 * 规则集 8 类（双语），全部要求「指令意图」证据，收紧以压误报：
 *  R1 指令覆盖（ignore previous / 忽略之前的指令）
 *  R2 系统标记伪装（`</system>`、`[INST]`、`<<SYS>>`、`<|im_start|>` 出现在数据里）
 *  R3 角色重写（you are now / 从现在开始你是）
 *  R4 提示词窃取（reveal your prompt / 输出你的系统提示词）
 *  R5 不可见字符走私（零宽 U+200B/200C/200D/2060/FEFF）
 *  R6 伪工具调用（`<tool_call>`、`{"name":…,"arguments":…}`）
 *  R7 数据外传指令（send/upload … URL/邮箱；把数据发送到）
 *  R8 编码载荷（base64/decode/解码 关键词紧邻长 base64 串）
 */
object PromptInjectionHeuristics {

    /** 命中片段的截断长度 */
    private const val SAMPLE_MAX = 80

    /** 单次扫描的信号总量上限（防御病态数据撑爆回执） */
    private const val SIGNALS_CAP = 50

    private data class Rule(val id: String, val name: String, val pattern: Regex)

    private val RULES: List<Rule> = listOf(
        Rule(
            "R1", "指令覆盖",
            Regex(
                "(?i)ignore\\s+(all\\s+)?(previous|prior|above|earlier)|disregard\\s+(all\\s+)?(previous|prior|above)|" +
                    "忽略(?:掉)?(?:之前|以上|前面|上述)(?:的)?(?:所有)?(?:指令|提示|设定|规则)",
            ),
        ),
        Rule(
            "R2", "系统标记伪装",
            Regex(
                "(?i)<\\s*/?\\s*system\\s*>|<<\\s*SYS\\s*>>|\\[/?INST]|<\\|im_start\\|>|<\\|im_end\\|>|<\\|system\\|>|" +
                    "###\\s*(?:System|Instruction|指令)",
            ),
        ),
        Rule(
            "R3", "角色重写",
            Regex(
                "(?i)\\b(?:you\\s+are\\s+now|pretend\\s+to\\s+be|new\\s+instructions?\\s*(?:are|:) )|" +
                    "从现在(?:开始)?(?:起)?(?:你是|你将扮演|你必须)|你现在是|你现在扮演|新(?:的)?指令如下",
            ),
        ),
        Rule(
            "R4", "提示词窃取",
            Regex(
                "(?i)\\b(?:reveal|print|repeat|show|output|leak)\\s+(?:(?:your|the|this)\\s+)?(?:system\\s+)?(?:prompt|instructions?|rules)\\b|" +
                    "(?:输出|打印|复述|泄露|透出)(?:你的)?(?:系统)?(?:提示词|系统设定|完整指令)",
            ),
        ),
        Rule("R5", "不可见字符走私", Regex("[\\x{200B}\\x{200C}\\x{200D}\\x{2060}\\x{FEFF}]")),
        Rule(
            "R6", "伪工具调用",
            Regex(
                "(?i)<\\|?tool_?calls?\\|?>|</?tool_call>|\"name\"\\s*:\\s*\"[^\"]{0,60}\"\\s*,\\s*\"arguments\"",
            ),
        ),
        Rule(
            "R7", "数据外传指令",
            Regex(
                "(?i)\\b(?:send|upload|post|forward|email)\\b[^.\\n]{0,40}(?:https?://|[\\w.+-]+@[\\w-]+\\.[\\w.-]+)|" +
                    "(?:把|将|请)[^.\\n]{0,16}(?:数据|内容|结果|截图|记录)[^.\\n]{0,12}(?:发送|上传|外传|转发)",
            ),
        ),
        Rule(
            "R8", "编码载荷",
            Regex("(?i)\\b(?:base64|decode|rot13)\\b.{0,20}?[A-Za-z0-9+/]{40,}|(?:解码|解密|还原).{0,12}[A-Za-z0-9+/]{40,}"),
        ),
    )

    /** 规则的 (id, pattern) 对（[neutralizeText] 的替换循环用，与 RULES 同源单一事实） */
    private val RULE_PATTERNS: List<Pair<String, Regex>> = RULES.map { it.id to it.pattern }

    /** 扫描单段文本：返回全部命中（每条规则至多记 1 次——重复命中同一规则不刷屏） */
    fun scanText(text: String, location: String): List<InjectionSignal> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<InjectionSignal>()
        for (rule in RULES) {
            val found = rule.pattern.find(text) ?: continue
            if (out.size >= SIGNALS_CAP) break
            out.add(InjectionSignal(rule.id, rule.name, location, flatten(found.value)))
        }
        return out
    }

    /** 该文本是否命中任一规则；命中时返回规则 id（简报样本段过滤用，要求最快路径） */
    fun firstRuleId(text: String): String? {
        if (text.isEmpty()) return null
        for (rule in RULES) {
            if (rule.pattern.containsMatchIn(text)) return rule.id
        }
        return null
    }

    /**
     * 扫描整份数据集：列名全扫 + 前 [maxRows] 个数据行的全部单元格。
     * 有界扫描（默认 500 行）——导入回执要的是「可见性」，不是全量杀毒；
     * 超过界的数据行不扫，如实回报在 [InjectionScanReport.scannedRows]。
     */
    fun scanDataset(dataset: Dataset, maxRows: Int = 500): InjectionScanReport {
        val signals = mutableListOf<InjectionSignal>()
        dataset.columns.forEachIndexed { index, name ->
            signals.addAll(scanText(name, "列名[${name.take(24)}]（第${index + 1}列）"))
        }
        val rows = dataset.rows.take(maxRows)
        for ((rowIndex, row) in rows.withIndex()) {
            for ((colIndex, cell) in row.withIndex()) {
                if (cell.isEmpty()) continue
                val hits = scanText(cell, "第${rowIndex + 1}行[列:${dataset.columns.getOrNull(colIndex)?.take(24) ?: colIndex + 1}]")
                signals.addAll(hits)
                if (signals.size >= SIGNALS_CAP) {
                    return InjectionScanReport(rows.size, signals.take(SIGNALS_CAP))
                }
            }
        }
        return InjectionScanReport(rows.size, signals.take(SIGNALS_CAP))
    }

    /**
     * 命中片段回执形制：压平换行并截断（明细可见但不放大载荷）。
     */
    private fun flatten(raw: String): String =
        raw.replace("\n", "\\n").replace("\r", "").replace("\t", " ").take(SAMPLE_MAX)

    /**
     * 该文本是否**可能**命中任一规则。
     *
     * ## 为什么这里**不再有独立的预筛表**（T-143）
     *
     * 原实现有一份**手写的** 42 项字面特征表（`PREFILTER`）做预筛，**预筛不命中
     * 就直接放行**（连规则都不跑）。两张手写表必然漂移——实测：仓内对抗语料里
     * **6 条**载荷命中了规则却被预筛放行（「请忽略之前的所有指令」「忽略上述规则」
     * 「先忽略前面的设定」「[/INST]」「新的指令如下：」、伪 tool_call JSON），
     * 而英文对照全部被拦。**根因不是漏了几个词，是「两张手写表」这个结构。**
     *
     * 现在预筛**直接由规则派生**：命中任一规则即返回 true。于是
     * **结构上不可能再漏放行**——没有第二张表可以漂移。
     *
     * ## 性能：实测选型（20 万格，JDK 17 直接微基准）
     *
     * | 写法 | 耗时 | 正确性 |
     * |---|---|---|
     * | 手写字面表 42×`contains`（原实现） | 15ms | ❌ 有漏放行 |
     * | 派生**合并**正则 1× | **215ms（最慢）** | ✅ |
     * | **本实现**：8 条独立正则逐条 | **78ms** | ✅ |
     *
     * 「合并成一条大交替正则」是**反直觉的慢**——合并后 JDK 正则引擎失去了
     * 各条正则各自的**字面量前缀优化**，每格都要在 8 个分支上回溯。
     * 8 条**独立**的预编译正则逐条短路（[firstRuleId] 命中即返回）反而快 2.8×。
     *
     * 相对原实现本项慢 63ms/20 万格——这是**为正确性付的价**：
     * 原实现的 15ms 是**用漏放行换来的**。
     */
    fun mayContainSignal(text: String): Boolean = firstRuleId(text) != null

    /**
     * 简报安全化（T-067；D25 审查扩面）：命中**格/列名/数据集名**全部替换为 `[已过滤:Rn]`
     * 占位符——样本段、列清单、分组标题、数据来源行等所有下游段落看到同一占位符，
     * 注入原文零泄漏；数字与统计不受影响。零命中返回**原对象**（逐字节门 B 安全）；
     * 命中返回替换后的副本。
     *
     * ## 为什么扩到列名与数据集名（D25 安全审查）
     *
     * 列名（CSV 表头/远程对象 key）与数据集名（文件名/suggestedName）同样是攻击者可控面，
     * 且**逐字进简报**（列清单/分组标题/数据来源行）；原实现只洗格——检出（scanDataset 扫列名）
     * 与净化（只洗格）语义分叉，「命中项不进简报」纪律对列名这一路失效，name 此前连扫描都不扫。
     */
    fun sanitizeForBrief(dataset: Dataset): Pair<Dataset, Int> {
        var hits = 0
        var touched = false
        val newRows = dataset.rows.map { row ->
            row.map { cell ->
                if (cell.isEmpty()) {
                    cell
                } else {
                    // T-143：预筛层已删除（它就是漏洞的根因），这里**只判定一次**——
                    // 原实现是「先预筛、再归因」，等于把规则跑了两遍。
                    val ruleId = firstRuleId(cell)
                    if (ruleId != null) {
                        hits++
                        touched = true
                        "[已过滤:$ruleId]"
                    } else {
                        cell
                    }
                }
            }
        }
        // D25 扩面：列名命中 → 占位符（保持列位对齐：只换 columns，columnTypes 不动）
        val newColumns = dataset.columns.map { col ->
            val ruleId = firstRuleId(col)
            if (ruleId != null) {
                hits++
                touched = true
                "[已过滤:$ruleId]" // lint:allow zh-first（占位符=用户面中文输出，T-067/T-088 语言定位）
            } else {
                col
            }
        }
        // D25 扩面：数据集名命中 → 占位符（显示面；此前连扫描都不扫）
        val newName = run {
            val ruleId = firstRuleId(dataset.name)
            if (ruleId != null) {
                hits++
                touched = true
                "[已过滤:$ruleId]" // lint:allow zh-first（占位符=用户面中文输出，T-067/T-088 语言定位）
            } else {
                dataset.name
            }
        }
        if (!touched) return dataset to hits
        val out = dataset.copy(rows = newRows, columns = newColumns, name = newName)
        return out to hits
    }

    /**
     * TextContent 级中和：每段 text 经 [neutralizeText]，段号与段落结构不动。
     * 存储段保持原文（溯源/quote 逐字对拍不受影响）；只有进入提示词装配的副本被中和。
     */
    fun neutralizeContent(content: TextContent): TextContent {
        var touched = false
        val segments = content.segments.map { seg ->
            val cleaned = neutralizeText(seg.text)
            if (cleaned != seg.text) touched = true
            seg.copy(text = cleaned)
        }
        if (!touched) return content
        return TextContent(segments)
    }

    /**
     * 段落注入中和（D25 安全审查：文本链路净化收口）。
     *
     * 把命中规则的**字面载荷**替换为 `[已过滤:Rn]` 占位符，保留段落其余文本——段落比格长，
     * 整段替换会连合法内容一起抹掉；载荷级替换让正文/数字/引用结构保留，只有注入原文消失。
     *
     * 使用面：文本链路的提示词装配点（askFollowupText 的 content 入口 / TextSummarizer 的
     * 分块正文）——存储段保持原文（溯源/quote 逐字对拍不受影响），只有**进入提示词的副本**
     * 被中和，与 T-067 的简报格净化同纪律：检出与净化同面。
     */
    fun neutralizeText(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for ((id, pattern) in RULE_PATTERNS) {
            out = pattern.replace(out, "[已过滤:$id]") // lint:allow zh-first（占位符=用户面中文输出，T-067/T-088 语言定位）
        }
        return out
    }
}
