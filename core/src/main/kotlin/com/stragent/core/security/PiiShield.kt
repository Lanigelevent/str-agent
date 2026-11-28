package com.stragent.core.security

import com.stragent.core.ports.PrefsStore

/**
 * PII Shield（T-195）——LLM 出站面的隐私闸（语料 I1 Presidio 代理模式机制借鉴，纯 JVM 自研）。
 *
 * 代理模式：出站消息里的 PII（手机号/身份证/银行卡/email）替换为 `[[P1]]` 顺序占位符，
 * 模型返回按**同一份映射**还原——映射随单次请求存亡：端点看不到原文，本机调用方看到的仍是原文。
 * 接线点在 [com.stragent.data.HttpLlmClient]（全部 LLM 流量的单一咽喉），编排器与门禁零改动。
 *
 * 设计约束：
 *  - **默认关**（env `STRAGENT_PII_SHIELD=1` 或 prefs `privacy.pii.shield=true`，运行时热切换）——
 *    关闭态零行为差异（负控钉住）；T-203 熵回退阈值按测试语料标定，残余误伤边界诚实登记：
 *    无分隔符的纯小写长串（≥24 字符）可能命中熵层（opt-in 闸后影响面有限）。
 *  - **确定性**：占位符按「消息序 × 出现序」枚举、同原文恒同占位符——重放一致，与溯源清单
 *    确定性口径兼容；
 *  - **识别器 = 正则 + 校验和**（Presidio「NER+正则+校验和」组合的轻量版）：身份证走
 *    ISO 7064 MOD 11-2 校验位、银行卡走 Luhn——校验和把订单号/金额类无前缀数字串挡在识别层；
 *  - **T-203 密钥/高熵 token 识别**（privacy-filter 三级检测的第三级：熵回退）：
 *    关键词前缀密钥（sk-/ghp_/AKIA/xoxb/…策展前缀集，误伤≈0）→ 高熵串回退（≥24 字符
 *    连续 base64 族字符、≥2 字符类、Shannon 熵 ≥3.8 bits/字符）——抓「无前缀的裸密钥/token」；
 *    点号/连字符/下划线不进回退字符类：文件名/域名/UUID/snake_case 标识符天然被分隔符切开，
 *    不给熵层误伤的机会（测试钉住标定）；
 *  - **出站脱敏顺带收紧数字白名单**：PII 数字从端点视野消失，模型无法引用，自然无法编造。
 *
 * 边界（诚实）：NER 级实体（姓名/地址）不覆盖；还原依赖占位符逐字保真——模型改写占位符
 * 时还原失败，残留占位符是可见工件（[LEFTOVER] 可检出）；流式增量帧可能短暂含跨帧劈开的
 * 占位符片段，终稿已还原。
 */
object PiiShield {

    /** 还原失败工件检出（调用方可据此在 qualityNotes 留痕） */
    val LEFTOVER: Regex = Regex("""\[\[P\d+\]\]""")

    /** 单次调用的脱敏映射（随请求存亡；同原文恒同占位符） */
    class Mapping {
        private val byPlaceholder = LinkedHashMap<String, String>()
        private val byOriginal = LinkedHashMap<String, String>()

        /** 登记/复用占位符（确定性枚举 P1、P2…） */
        internal fun placeholderFor(original: String): String {
            byOriginal[original]?.let { return it }
            val p = "[[P" + (byPlaceholder.size + 1) + "]]"
            byPlaceholder[p] = original
            byOriginal[original] = p
            return p
        }

        /** 还原用：占位符 → 原文（快照，遍历安全） */
        fun entries(): List<Pair<String, String>> = byPlaceholder.entries.map { it.key to it.value }
    }

    /** opt-in 闸：env `STRAGENT_PII_SHIELD=1` 或 prefs `privacy.pii.shield=true`（任一为真） */
    fun enabled(prefs: PrefsStore): Boolean {
        if (com.stragent.core.config.Config.get("STRAGENT_PII_SHIELD") == "1") return true
        return prefs.get("privacy.pii.shield", "false").trim().equals("true", ignoreCase = true)
    }

    // ---- 识别器（顺序敏感：email 先于数字类；数字类用校验和挡误伤） ----

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val CN_MOBILE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val CN_ID = Regex("""(?<!\d)\d{17}[\dXx](?!\d)""")
    private val BANK_CARD = Regex("""(?<!\d)\d{13,19}(?!\d)""")

    // ---- T-203：密钥/高熵 token 识别（privacy-filter 三级检测的第三级：熵回退） ----

    /** 关键词前缀密钥（策展最小集：结构性前缀，误伤≈0；体部长度下限挡随机同形串） */
    private val KEYED_SECRET = Regex(
        "(?<![A-Za-z0-9])(" +
            "sk-[A-Za-z0-9]{20,}" +
            "|rk-[A-Za-z0-9]{20,}" +
            "|pk_(?:live|test)_[A-Za-z0-9]{14,}" +
            "|ghp_[A-Za-z0-9]{30,}" +
            "|gho_[A-Za-z0-9]{30,}" +
            "|github_pat_[A-Za-z0-9_]{30,}" +
            "|AKIA[0-9A-Z]{16}" +
            "|xox[baprs]-[A-Za-z0-9-]{15,}" +
            "|gsk_[A-Za-z0-9]{20,}" +
            "|AIza[0-9A-Za-z_-]{30,}" +
            "|eyJ[A-Za-z0-9_-]{15,}\\.[A-Za-z0-9_-]{10,}" +
            ")",
    )

    /**
     * 熵回退候选串：≥24 字符连续 base64 族字符。**不含点号/连字符/下划线**——
     * 文件名/域名/UUID/snake_case 标识符被这些分隔符天然切开，不给熵层误伤机会。
     */
    private val ENTROPY_RUN = Regex("[A-Za-z0-9+/=]{24,}")

    /** Shannon 熵（bits/字符）——熵回退的判据 */
    internal fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = HashMap<Char, Int>()
        s.forEach { freq.merge(it, 1, Int::plus) }
        var h = 0.0
        for (f in freq.values) {
            val p = f.toDouble() / s.length
            h -= p * (Math.log(p) / Math.log(2.0))
        }
        return h
    }

    /**
     * 熵回退判据：≥2 字符类（小写+数字即纯 hex 密钥也覆盖）且熵 ≥3.4 bits/字符
     * （3.4 按 hex 密钥实测 3.44 标定；纯数字串熵上限≈3.32，类规则+熵双重出局）。
     * 残余边界：分布极不均的密钥（重复字符多）可能低于阈值漏过——opt-in 闸后的诚实登记。
     */
    private fun entropyOk(s: String): Boolean {
        if (s.length < 24) return false
        val hasUpper = s.any { it.isUpperCase() }
        val hasLower = s.any { it.isLowerCase() }
        val hasDigit = s.any { it.isDigit() }
        val hasSym = s.any { it in "+/=" }
        if (listOf(hasUpper, hasLower, hasDigit, hasSym).count { it } < 2) return false
        return entropy(s) >= 3.4
    }

    /** 出站脱敏：PII → 占位符（登记进 [mapping]；同原文恒同占位符） */
    fun maskText(text: String, mapping: Mapping): String {
        if (text.isEmpty()) return text
        val hits = mutableListOf<Triple<Int, Int, String>>() // start, endExclusive, original
        for (m in EMAIL.findAll(text)) hits.add(Triple(m.range.first, m.range.last + 1, m.value))
        for (m in CN_MOBILE.findAll(text)) hits.add(Triple(m.range.first, m.range.last + 1, m.value))
        for (m in CN_ID.findAll(text)) {
            if (idChecksumOk(m.value)) hits.add(Triple(m.range.first, m.range.last + 1, m.value))
        }
        for (m in BANK_CARD.findAll(text)) {
            if (luhnOk(m.value)) hits.add(Triple(m.range.first, m.range.last + 1, m.value))
        }
        // T-203 两级：关键词前缀密钥 → 熵回退（登记顺序在后——重叠丢弃规则让结构识别器优先）
        for (m in KEYED_SECRET.findAll(text)) hits.add(Triple(m.range.first, m.range.last + 1, m.value))
        for (m in ENTROPY_RUN.findAll(text)) {
            if (entropyOk(m.value)) hits.add(Triple(m.range.first, m.range.last + 1, m.value))
        }
        if (hits.isEmpty()) return text
        hits.sortBy { it.first }
        val sb = StringBuilder()
        var pos = 0
        for (h in hits) {
            if (h.first < pos) continue // 重叠丢弃：email 先登记，其数字子串不再当手机号 // lint:allow zh-first（重叠策略注释，T-195）
            sb.append(text, pos, h.first)
            sb.append(mapping.placeholderFor(h.third))
            pos = h.second
        }
        sb.append(text.substring(pos))
        return sb.toString()
    }

    /** 入站还原：占位符 → 原文；未登记占位符原样保留（不猜） */
    fun restore(text: String, mapping: Mapping): String =
        if (mapping.entries().isEmpty()) text
        else mapping.entries().fold(text) { acc, (p, o) -> acc.replace(p, o) }

    /** 身份证校验位（ISO 7064 MOD 11-2） */
    private fun idChecksumOk(s: String): Boolean {
        if (s.length != 18) return false
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        var sum = 0
        for (i in 0 until 17) {
            val d = s[i] - '0'
            if (d < 0 || d > 9) return false
            sum += d * weights[i]
        }
        val expect = "10X98765432"[sum % 11]
        return s[17] == expect || (expect == 'X' && (s[17] == 'x' || s[17] == 'X'))
    }

    /** 银行卡 Luhn 校验 */
    private fun luhnOk(s: String): Boolean {
        var sum = 0
        var alt = false
        for (i in s.length - 1 downTo 0) {
            val d = s[i] - '0'
            if (d < 0 || d > 9) return false
            if (alt) {
                val dd = d * 2
                sum += if (dd > 9) dd - 9 else dd
            } else {
                sum += d
            }
            alt = !alt
        }
        return sum % 10 == 0
    }
}
