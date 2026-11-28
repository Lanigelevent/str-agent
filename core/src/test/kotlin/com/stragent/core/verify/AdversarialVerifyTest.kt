package com.stragent.core.verify

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-051 对抗扰动集。
 *
 * 六类扰动用**确定性生成**（无随机种子）：每类 ≥50 例，召回目标 100%（阈值 ≥95%）：
 *  ① 同形换值：句式与真值一模一样，只把数字换掉
 *  ② 精度拔高：值落在容差内但精度比摘要高（四舍五入解释不通）
 *  ③ 算式洗白：算式自洽，但操作数本身是编的
 *  ④ 算术算错：操作数都有依据，结果算错（含加/减/乘/除与多项链式）
 *  ⑤ 中文数字/百分比造假：中文写法、`0.5%` 与 `50.0%` 串档
 *  ⑥ 豁免伪装：0/1 无量词、年份/分数/格式词/假设句掩护编造数
 *
 * 另有 300+ 条干净语料，误报必须为 0（门槛 ≤2%）。
 */
class AdversarialVerifyTest {

    private val brief = """
数据来源：adversarial.csv
规模：2000 行 × 6 列（数值列 3 个，文本列 3 个）

列清单：
- 区域（文本）：非空 2000，3 个不同值，高频值：East(1000)、West(700)、North(300)
- 销售额（数值）：有效样本 2000，最小 0，中位数 100，最大 500，均值 120.5，合计 241000
- 成本（数值）：有效样本 2000，最小 0，中位数 60，最大 300，均值 72.3，合计 144600
- 利润（数值）：有效样本 2000，最小 0，中位数 40，最大 200，均值 48.2，合计 96400

按月汇总（销售额，最近 6 个月）：2018-01 5000、2018-02 6000、2018-03 7200、2018-04 6480、2018-05 7000、2018-06 7500

月度环比（与上面同口径；本月 = (本月 - 上月) ÷ |上月|，符号表示方向；只列最近 5 个月，更早的对照上面的逐月值）：
  2018-02 环比：+20.0%（5000 → 6000）
  2018-03 环比：+20.0%（6000 → 7200）
  2018-04 环比：-10.0%（7200 → 6480）
  2018-05 环比：+8.0%（6480 → 7000）
  2018-06 环比：+7.1%（7000 → 7500）

按「区域」分组求和（销售额，全量数据；末列为单组均值＝该组合计 ÷ 该组行数）：
- East：合计 120500（50.0%），1000 行，单组均值 120.5
- West：合计 72300（30.0%），700 行，单组均值 103.29
- North：合计 48200（20.0%），300 行，单组均值 160.67

按「区域」分组求和（成本，全量数据）：
- East：合计 72300，1000 行
- West：合计 43380，700 行
- North：合计 28920，300 行

按「区域」分组的双指标派生（销售额 与 成本，全量数据）：
- East：利润率 60.0%（72300 ÷ 120500），主指标单均 120.5，第二指标单均 72.3，1000 行
- West：利润率 60.0%（43380 ÷ 72300），主指标单均 103.29，第二指标单均 61.97，700 行
- North：利润率 60.0%（28920 ÷ 48200），主指标单均 160.67，第二指标单均 96.4，300 行

按年汇总（销售额，全量数据）：
- 2017 年：合计 100000（41.5%），800 行
- 2018 年：合计 141000（58.5%），1200 行
  2018 年同比：+41.0%（100000 → 141000）

前 10 行样本：
```
区域,销售额,成本
East,120,72
West,72,43
```
""".trimIndent()

    // ---------- 口径镜像：只用于筛掉「本来就该放行」的候选值 ----------

    private val briefNatural: Set<Double> by lazy {
        extractNaturalNumbers(citableBriefText(brief)).map { it.value }.toSet()
    }

    private fun backed(value: Double): Boolean = hitsBrief(value, briefNatural)

    private fun n(value: Double): String =
        BigDecimal(Math.round(value * 100.0) / 100.0).stripTrailingZeros().toPlainString()

    private fun desc(result: VerificationResult): String {
        val parts = mutableListOf<String>()
        for (item in result.unsourced) parts.add("unsourced=${item.value}")
        for (item in result.formula) parts.add("formula=${item.value}")
        for (item in result.arithmetic) parts.add("arith=${item.claimed}≠${item.expected}")
        for (item in result.relations) parts.add("relation=${item.claimed}/${item.expected}")
        for (item in result.fields) parts.add("field=${item.field}")
        return parts.joinToString("；").ifEmpty { "无" }
    }

    private fun verify(text: String) = verifyReport(text, brief)

    private fun assertRecall(name: String, cases: List<String>, kind: String) {
        assertTrue(cases.size >= 50, "$name 只生成了 ${cases.size} 例（需 ≥50）")
        val misses = cases.filter { text ->
            val r = verify(text)
            when (kind) {
                "unsourced" -> r.unsourced.isEmpty()
                "formula" -> r.formula.isEmpty()
                "arithmetic" -> r.arithmetic.isEmpty()
                else -> error("未知类别 $kind")
            }
        }
        val recall = 1.0 - misses.size.toDouble() / cases.size
        // 验收上限是 95%，但本方语料每条都是确定性生成的「必须抓到」——
        // 允许 5% 的余量会让整类扰动（如某一运算符）静默失效而测试仍绿。
        assertTrue(
            misses.isEmpty(),
            "$name 召回 ${"%.1f".format(recall * 100)}%（${cases.size - misses.size}/${cases.size}）非满分：\n" +
                misses.take(6).joinToString("\n") { "$it → ${desc(verify(it))}" },
        )
    }

    // ---------- ① 同形换值 ----------

    @Test
    fun `类一 同形换值——句式不变只换数字`() {
        val bases = listOf(120500.0, 72300.0, 48200.0, 144600.0, 96400.0, 241000.0, 120.5, 72.3, 48.2)
        val candidates = LinkedHashSet<Double>()
        for ((index, base) in bases.withIndex()) {
            candidates.add(base * 1.07 + 13 + index)
            candidates.add(base * 0.83 - 11 - index)
            candidates.add(base + 1777 + index)
            candidates.add(base - 1313 - index)
            candidates.add(base * 2 + 29 + index)
        }
        val cases = mutableListOf<String>()
        for (value in candidates) {
            if (value <= 2 || backed(value)) continue
            val shown = n(value)
            cases.add("East 销售额合计 $shown 元。")
            cases.add("West 的成本是 $shown 元。")
            cases.add("North 利润为 $shown 元，整体表现平稳。")
        }
        assertRecall("类一同形换值", cases.take(90), "unsourced")
    }

    // ---------- ② 精度拔高 ----------

    @Test
    fun `类二 精度拔高——四舍五入解释不通`() {
        val bases = listOf(120.5, 72.3, 48.2, 103.29, 160.67, 96.4, 61.97, 241000.0, 144600.0, 96400.0)
        val suffixes = listOf(0.53, 0.37, 0.291, 0.007, 0.0009, 0.61, 0.013, 0.0021, 0.44, 0.08)
        val cases = mutableListOf<String>()
        for ((i, base) in bases.withIndex()) {
            for ((j, suffix) in suffixes.withIndex()) {
                val value = base + suffix
                if (value <= 0 || backed(value)) continue
                val shown = BigDecimal(value).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                cases.add("East 的单组均值精确到 $shown 元。")
                if (cases.size >= 60) break
            }
            if (cases.size >= 60) break
        }
        assertTrue(cases.size >= 50, "只生成 ${cases.size} 例")
        assertRecall("类二精度拔高", cases, "unsourced")
    }

    // ---------- ③ 算式洗白 ----------

    @Test
    fun `类三 算式洗白——自洽算式包着编造的操作数`() {
        val junk = listOf(9999, 8888, 7777, 6666, 5555, 4321, 2468, 1357, 3141, 2718)
        val cases = mutableListOf<String>()
        outer@ for ((i, a) in junk.withIndex()) {
            for ((j, b) in junk.withIndex()) {
                if (j <= i) continue
                if (backed(a.toDouble()) || backed(b.toDouble())) continue
                cases.add("校验：$a + $b = ${a + b}。")
                cases.add("校验：$a - $b = ${a - b}。")
                if (j % 2 == 0) cases.add("校验：$a × 3 = ${a * 3}。")
                if (a % (j + 3) == 0 && j + 3 != 0) cases.add("校验：$a ÷ ${j + 3} = ${a / (j + 3)}。")
                if (cases.size >= 60) break@outer
            }
        }
        assertRecall("类三算式洗白", cases, "formula")
    }

    // ---------- ④ 算术算错 ----------

    @Test
    fun `类四 算术算错——加减乘除与多项链式`() {
        data class Formula(val text: String, val correct: Double)
        val formulas = mutableListOf<Formula>()
        formulas.add(Formula("120,500 + 72,300 = %s 元", 192800.0))
        formulas.add(Formula("241,000 - 144,600 = %s 元", 96400.0))
        formulas.add(Formula("1,000 × 120.5 = %s 元", 120500.0))
        formulas.add(Formula("120,500 ÷ 1,000 = %s 元", 120.5))
        formulas.add(Formula("120,500 + 72,300 + 48,200 = %s 元", 241000.0))
        formulas.add(Formula("241,000 - 144,600 - 96,400 = %s 元", 0.0))
        formulas.add(Formula("1,000 + 300 × 2 = %s 元", 1600.0))
        formulas.add(Formula("500 × 300 = %s 件", 150000.0))
        formulas.add(Formula("48,200 ÷ 300 = %s 元", 160.6666667))
        formulas.add(Formula("144,600 ÷ 241,000 = %s", 0.6))
        val wrongs = listOf(100000.0, 130000.0, 250000.0, 150.0, 999.0, 193000.0, 96000.0, 12050.0, 1610.0, 0.5)
        val cases = mutableListOf<String>()
        for (formula in formulas) {
            for ((i, wrong) in wrongs.withIndex()) {
                if (Math.abs(formula.correct - wrong) <= Math.max(Math.abs(formula.correct) * 0.005, 0.0005)) continue
                val text = formula.text.format(n(wrong))
                cases.add(text)
                if (i % 3 == 1) cases.add("$text 这是本月的口径。")
                if (cases.size >= 60) break
            }
            if (cases.size >= 60) break
        }
        assertRecall("类四算术算错", cases, "arithmetic")
    }

    // ---------- ⑤ 中文数字/百分比造假 ----------

    @Test
    fun `类五 中文数字与百分比造假`() {
        val cases = mutableListOf<String>()
        val fakePercents = listOf(
            "六十五" to 0.65, "四十五" to 0.45, "五十五" to 0.55, "七十" to 0.70, "八十" to 0.80,
            "七十五" to 0.75, "八十五" to 0.85, "九十五" to 0.95, "三十三" to 0.33, "二十二" to 0.22,
        )
        for ((word, value) in fakePercents) {
            if (backed(value)) continue
            cases.add("East 占比百分之${word}。")
            cases.add("North 的份额为百分之${word}。")
        }
        val fakeAmounts = listOf(
            "二十四万二千" to 242000.0,
            "十四万五千" to 145000.0,
            "九万六千五百" to 96500.0,
            "十二万零六百" to 120600.0,
            "七万二千三百五十" to 72350.0,
            "十四万四千七百" to 144700.0,
            "二十四万三千" to 243000.0,
            "十四万六千" to 146000.0,
            "九万七千" to 97000.0,
            "四万九千" to 49000.0,
            "十二万一千" to 121000.0,
            "七万四千" to 74000.0,
            "十四万七千" to 147000.0,
            "九万八千" to 98000.0,
            "二十四万五千" to 245000.0,
            "十二万二千" to 122000.0,
        )
        for ((word, value) in fakeAmounts) {
            if (backed(value)) continue
            cases.add("销售额合计${word}元。")
            cases.add("本月成本${word}元。")
        }
        // 串档：`0.5%` 不能被 `50.0%` 背书
        val ambiguous = listOf(0.5, 0.3, 0.2, 0.6, 0.41, 0.585, 1.2, 2.4, 4.5, 6.3, 9.4, 11.1)
        for (value in ambiguous) {
            if (backed(value / 100)) continue
            cases.add("East 占比 ${n(value)}%。")
            cases.add("West 的份额约 ${n(value)}%。")
            if (cases.size >= 60) break
        }
        assertTrue(cases.size >= 50, "只生成 ${cases.size} 例")
        assertRecall("类五中文与百分比造假", cases, "unsourced")
    }

    // ---------- ⑥ 豁免伪装 ----------

    @Test
    fun `类六 豁免伪装——结构性数字与豁免词掩护编造数`() {
        val cases = mutableListOf<String>()
        // 0/1 无量词/序号语境 → 参与溯源
        val oneClaims = listOf(
            "East 占比 1。", "West 占比 1。", "North 利润率 1。",
            "East 增长 1 倍。", "West 提升 1 倍。", "North 的成本优势是 1 倍。",
            "East 占比为 1，明显领先。", "West 占比达到 1。", "North 占比约 1。",
            "East 的份额是 1。", "West 的占比达到 1。", "North 增长 1 倍以上。",
            "East 利润率约为 1。", "West 的份额是 1。", "North 占比为 1。",
        )
        for (text in oneClaims) {
            // 「1 倍」不做结构豁免——除非摘要里本来就有 1
            if (backed(1.0)) continue
            cases.add(text)
        }
        // 年份/分数伪装
        val junk = listOf(
            105000.0, 88888.0, 77777.0, 66666.0, 55555.0, 44444.0, 33333.0, 22222.0, 11111.0, 98765.0,
            87654.0, 76543.0, 65432.0, 54321.0, 43210.0,
        )
        val years = listOf(2017, 2018, 2019, 2020, 2021)
        for ((i, value) in junk.withIndex()) {
            if (backed(value)) continue
            cases.add("${years[i % years.size]} 年销售额合计 ${n(value)} 元。")
        }
        val scores = listOf(
            99.0, 98.0, 97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0, 89.0,
            88.0, 87.0, 86.0, 85.0, 84.0, 83.0, 82.0, 81.0, 79.0, 78.0,
        )
        for (value in scores) {
            if (backed(value)) continue
            cases.add("数据质量得分 ${n(value)} 分。")
        }
        // 格式词掩护
        for (value in listOf(
            999999.0, 888888.0, 777777.0, 666666.0, 555555.0, 444444.0, 333333.0, 222222.0,
        )) {
            if (backed(value)) continue
            cases.add("销售额合计的写法应为 ${n(value)} 元。")
        }
        // 假设句掩护（数字 >2 个）
        for (value in listOf(888888.0, 777777.0, 666666.0, 555555.0, 444444.0, 333333.0)) {
            cases.add(
                "假设销售额达到 ${n(value)} 元、增长 12.3%、共 9,999 单，则需追加投入。",
            )
        }
        assertTrue(cases.size >= 50, "只生成 ${cases.size} 例")
        assertRecall("类六豁免伪装", cases, "unsourced")
    }

    // ---------- 干净语料 ----------

    @Test
    fun `干净语料误报不超过 2%`() {
        val prefixes = listOf("", "数据显示，", "总体来看，", "从统计结果看，", "综合来看，")
        val bases = listOf(
            "销售额合计 241,000 元，均值 120.5，中位数 100。",
            "成本合计 144,600 元，均值 72.3。",
            "利润合计 96,400 元，均值 48.2。",
            "East 销售额 120,500 元，West 72,300 元，North 48,200 元。",
            "East 占比 50.0%，West 占比 30.0%，North 占比 20.0%。",
            "East 占 50%，West 占 30%。",
            "East 占比百分之五十，West 占比百分之三十。",
            "East 占比 0.5，West 占比 0.3，North 占比 0.2。",
            "销售额合计二十四万一千元。",
            "成本合计十四万四千六百元。",
            "East 利润率 60.0%，West 利润率 60.0%。",
            "East 利润率百分之六十。",
            "1,000 行 × 120.5 = 120,500 元。",
            "120,500 + 72,300 = 192,800 元。",
            "120,500 + 72,300 + 48,200 = 241,000 元。",
            "241,000 - 144,600 = 96,400 元。",
            "120,500 ÷ 241,000 = 50.0%。",
            "West 是 North 的 1.5 倍（72,300 ÷ 48,200 = 1.5）。",
            "East 的销售额是 West 的 1.6667 倍（120,500 ÷ 72,300 = 1.6667）。",
            "East 高于 West。",
            "East 的单组均值高于 West。",
            "2018-02 环比增长 20.0%。",
            "2018-04 环比下降 10.0%。",
            "2018-06 环比上升 7.1%。",
            "2018 年销售额合计 141,000 元。",
            "2017 年销售额 100,000 元。",
            "2018 年同比上升 41.0%。",
            "共 3 个区域、2,000 行数据。",
            "数据质量得分 100 分。",
            "销售额最大 500，最小 0。",
            "月度峰值 7,500，谷值 5,000。",
            "2018-06 销售额 7,500 元。",
            "East 有 1,000 行，West 有 700 行。",
            "North 有 300 行，占 20.0%。",
            "West 的成本 43,380 元。",
            "North 的成本 28,920 元。",
            "West 的单组均值 103.29。",
            "North 的单组均值 160.67。",
            "East 第二指标单均 72.3。",
            "假设该值真实为 120.5，则落回正常区间。",
            "编号 2018-01 的期间数据见上文。",
            "排名第 1 的区域是 East。",
            "见第 15 章的口径说明。",
            "第 3 季度的销售额最高。",
            "第 2 名是 West。",
            "两种写法 120.5 与 120.50 混着用时需要统一。",
            "销售额没有低于 0 元的记录。",
            "高值区间为 300 到 500。",
            "利润率 60.0% 说明成本占销售额的六成。",
            "成本是销售额的 60.0%。",
            "East 的 1,000 行贡献 120,500 元。",
            "24.1 万元是 East 的销售额。",
            "14.46 万元的投入对应 9.64 万元利润。",
            "East 与 West 的占比分别为 50.0% 与 30.0%。",
            "2018-05 的销售额 7,000 元，比 2018-04 的 6,480 元高出 500 元。",
            "East 的第二指标单均 72.3，West 61.97。",
            "North 的利润率 60.0%。",
            "成本占销售额 60.0%。",
            "月度均值 120.5 元。",
            "2018-03 环比增长 20.0%。",
            "2018-05 环比上升 8.0%。",
            "销售额合计 24.1 万元。",
            "利润合计 9.64 万元。",
        )
        val cases = mutableListOf<String>()
        for (prefix in prefixes) for (base in bases) cases.add(prefix + base)
        // 合法算式与合法中文写法的追加变体
        for (suffix in listOf("", "（全量口径）", "。", "，四舍五入到一位小数。")) {
            cases.add("East 占比百分之五十，West 占比百分之三十$suffix")
            cases.add("销售额合计二十四万一千元$suffix")
            cases.add("120,500 ÷ 241,000 = 50.0%$suffix")
        }
        assertTrue(cases.size >= 300, "干净语料只有 ${cases.size} 例")

        val failures = cases.mapNotNull { text ->
            val r = verify(text)
            if (r.unsourced.isEmpty() && r.formula.isEmpty() && r.arithmetic.isEmpty() &&
                r.relations.isEmpty() && r.fields.isEmpty()
            ) {
                null
            } else {
                "$text → ${desc(r)}"
            }
        }
        val fpRate = failures.size.toDouble() / cases.size
        // 契约上限是 2%，但本方语料每条都手工核过「值确实在摘要里」——
        // 允许 1.6% 的余量等于让「中文数字解析错位」这类系统性回归整批漏网（变异测试实测）。
        assertTrue(
            failures.isEmpty(),
            "干净语料误报 ${failures.size}/${cases.size}（${"%.1f".format(fpRate * 100)}%）：\n" +
                failures.take(10).joinToString("\n"),
        )
    }

    // ---------- 负控：每项新检查都必须能红 ----------

    @Test
    fun `负控 减法与乘法真的在验算`() {
        assertTrue(verify("241,000 - 144,600 = 100,000 元。").arithmetic.isNotEmpty(), "减法算错没报")
        assertTrue(verify("1,000 × 120.5 = 130,000 元。").arithmetic.isNotEmpty(), "乘法算错没报")
        assertTrue(verify("241,000 - 144,600 = 96,400 元。").arithmetic.isEmpty(), "减法算对误报")
        assertTrue(verify("1,000 × 120.5 = 120,500 元。").arithmetic.isEmpty(), "乘法算对误报")
    }

    @Test
    fun `负控 多项链式与运算优先级`() {
        assertTrue(verify("120,500 + 72,300 + 48,200 = 241,000 元。").arithmetic.isEmpty(), "链式算对误报")
        assertTrue(verify("120,500 + 72,300 + 48,200 = 250,000 元。").arithmetic.isNotEmpty(), "链式算错没报")
        assertTrue(verify("1,000 + 300 × 2 = 1,600 元。").arithmetic.isEmpty(), "优先级算对误报")
        assertTrue(verify("1,000 + 300 × 2 = 2,600 元。").arithmetic.isNotEmpty(), "优先级算错没报")
        // 紧凑写法（无空格）同样要验算——否则换个排版就能绕过（变异测试：游标差一位靠 \s* 掩蔽）
        val compactOk = verify("120500+72300=192800 元。")
        assertTrue(compactOk.arithmetic.isEmpty() && compactOk.formula.isEmpty(), "紧凑算式误报")
        assertTrue(verify("120500+72300=250000 元。").arithmetic.isNotEmpty(), "紧凑算式算错没报")
    }

    @Test
    fun `负控 中文数字与百分比互认`() {
        assertTrue(verify("销售额合计二十四万一千元。").unsourced.isEmpty(), "中文金额写对仍误报")
        assertTrue(verify("销售额合计二十四万二千元。").unsourced.isNotEmpty(), "中文金额写错没报")
        assertTrue(verify("East 占比百分之五十。").unsourced.isEmpty(), "中文百分比写对仍误报")
        assertTrue(verify("East 占比百分之六十五。").unsourced.isNotEmpty(), "中文百分比写错没报")
        // 中文百分比出现在算式里（parseNumberExpression 路径，与正文抽取不是同一条）
        assertTrue(verify("120,500 ÷ 241,000 = 百分之五十。").arithmetic.isEmpty(), "算式结果写中文百分比误报")
        assertTrue(
            verify("120,500 ÷ 241,000 = 百分之五十五。").arithmetic.isNotEmpty(),
            "算式结果中文百分比算错没报",
        )
    }

    @Test
    fun `负控 百分比不串档`() {
        assertTrue(verify("East 占比 50.0%。").unsourced.isEmpty(), "真百分比误报")
        assertTrue(verify("East 占比 0.5。").unsourced.isEmpty(), "分数写法误报")
        assertTrue(verify("East 占比 0.5%。").unsourced.isNotEmpty(), "0.5% 借 50.0% 洗白")
    }

    @Test
    fun `负控 算式不能替编造操作数洗白`() {
        val good = verify("120,500 ÷ 241,000 = 50.0%。")
        assertTrue(good.unsourced.isEmpty() && good.formula.isEmpty() && good.arithmetic.isEmpty(), "合法算式误报")
        val laundered = verify("120,500 ÷ 482 = 250。")
        assertTrue(laundered.formula.any { it.value == "482" }, "无依据的操作数 482 被放行")
        assertTrue(laundered.formula.any { it.value == "250" }, "无依据算式的结果 250 被授权")
    }

    @Test
    fun `负控 0 与 1 只在结构语境豁免`() {
        assertTrue(verify("排名第 1 的区域是 East。").unsourced.isEmpty(), "序号 1 误报")
        assertTrue(verify("East 占比 1。").unsourced.isNotEmpty(), "无量词的 1 被当结构数放行")
        assertTrue(verify("North 增长 1 倍。").unsourced.isNotEmpty(), "倍数 1 被当结构数放行")
    }

    @Test
    fun `负控 格式词不能整行豁免事实主张`() {
        assertTrue(verify("两种写法 120.5 与 120.50 混着用时需要统一。").unsourced.isEmpty(), "写法举例误报")
        assertTrue(
            verify("销售额合计的写法应为 999,999 元。").unsourced.isNotEmpty(),
            "格式词把编造销售额洗白",
        )
    }

    @Test
    fun `负控 序数豁免只放行序数本身`() {
        // 「第 N 章/名/季度」是排版定位，放行；同一行里的数据主张照旧要溯源
        val r = verify("第 3 名区域的销售额是 888,888 元。")
        assertTrue(r.unsourced.none { it.value == "3" }, "序数 3 误报")
        assertTrue(r.unsourced.any { it.value == "888,888" }, "序数豁免把编造销售额一起放行")
        assertTrue(verify("见第 15 章。").unsourced.isEmpty(), "章节号 15 误报")
        // 公式行里的序数不豁免：操作数必须各自有依据（与 0/1 同一条纪律）
        assertTrue(!isStructural("3", "第 3 + 5 = 8 元。", 3.0, true), "公式里的序数被静默放行")
    }

    @Test
    fun `负控 假设句豁免不放大`() {
        assertTrue(verify("假设该值真实为 120.5，则落回正常区间。").unsourced.isEmpty(), "单数字假设句误报")
        assertTrue(
            verify("假设销售额达到 888,888 元、增长 12.3%、共 9,999 单。").unsourced.size >= 2,
            "多数字假设句整行豁免",
        )
    }

    @Test
    fun `万单位阿拉伯数字不得绕过白名单——融合复核实测抓出的旁路（T-184 负控）`() {
        val brief = "样本统计：华东销售额 1,000，华北 300；合计 1,300。前 2 行样本：\n华东,1000\n华北,300\n"
        // 修复前：「8888 万」整 token 被 NUMERIC_TEXT 拒掉而静默跳过（checked=0）= 门禁旁路
        val r = verifyReport("华东销售额 8888 万，遥遥领先。", brief)
        assertTrue(r.checked >= 1, "带万单位的数字必须进核对（修复前 checked=0）：$r")
        assertTrue(r.unsourced.size >= 1, "8888 万不在简报里必须报未溯源：$r")
        // 正控：简报里的万单位数字（brief 侧同修复）能被背书同形引用
        val briefWan = "样本统计：销售额 1,200 万。前 2 行样本：\n销售,12000000\n销售,500\n"
        val ok = verifyReport("销售额 1,200 万。", briefWan)
        assertTrue(ok.unsourced.isEmpty(), "brief 里带万单位的数字应能背书同形引用：${ok.unsourced}")
    }
}
