package com.stragent.core.verify

import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 门 C：report-verify 的三层契约。
 *
 * ① label-baseline 契约：extractLabelValues 在 53 个样本上的提取结果
 *    与 TS 基线逐条一致（key + value 完全相同，含顺序）。
 * ② verifyReport 的行为契约：regression-verify-numbers 里的关键场景
 *    （白名单命中、撞数被抓、假设句豁免、算式验算、方向说反）。
 */
class VerifyGateTest {
    private val sampleDir = File("../fixtures/sample")
    private val fixturesRoot = File("../fixtures")

    private fun datasetOf(name: String): Dataset {
        val text = File(sampleDir, "$name.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "$name.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    // ---------- ① label-baseline 契约 ----------

    @Test
    fun `门 C-1 label-baseline 提取结果逐条一致`() {
        val baseline = Json.parseToJsonElement(File(fixturesRoot, "baseline/label-baseline.json").readText()).jsonObject
        assertTrue(baseline.size >= 50, "基线样本数 ${baseline.size}")
        val failures = mutableListOf<String>()
        var compared = 0
        // TS dump 的排序口径：(key + metric + String(value)) 字符串序。两侧按同一键排序后逐条比。
        for ((sample, entriesEl) in baseline) {
            val brief = buildDatasetBrief(datasetOf(sample))
            val kt = extractLabelValues(brief)
            val tsEntries: List<Triple<String, String, String>> = entriesEl.jsonArray.map { triple ->
                val t = triple.jsonArray
                Triple(t[0].jsonPrimitive.content, t[1].jsonPrimitive.content, t[2].jsonPrimitive.content)
            }.sortedBy { it.first + it.second + it.third }
            compared += 1
            val ktFlat: List<Triple<String, String, String>> = kt.entries
                .flatMap { (k, list) -> list.map { Triple(k, it.metric, jsNumberString(it.value)) } }
                .sortedBy { it.first + it.second + it.third }
            if (tsEntries != ktFlat) {
                val ktSet = ktFlat.toSet()
                val tsSet = tsEntries.toSet()
                val tsOnly = tsEntries.filter { it !in ktSet }
                val ktOnly = ktFlat.filter { it !in tsSet }
                failures.add("$sample: TS ${tsEntries.size} 条 vs KT ${ktFlat.size} 条；TS-only ${tsOnly.take(2)} KT-only ${ktOnly.take(2)}")
                if (failures.size >= 5) break
            }
        }
        assertTrue(failures.isEmpty(), "提取差异（已比 $compared 样本）：\n${failures.joinToString("\n")}")
    }

    // ---------- ② verifyReport 行为契约（regression-verify-numbers 的场景） ----------

    private val briefSuperstore by lazy { buildDatasetBrief(datasetOf("superstore")) }

    @Test
    fun `摘要里有的数字不报`() {
        val clean = "Sales 合计 2,297,201.08 元，中位数 54.49，Consumer 占 50.6%，1,161,401.52 元。"
        val r = verifyReport(clean, briefSuperstore)
        assertEquals(0, r.unsourced.size, r.unsourced.joinToString { "${it.value}: ${it.context}" })
    }

    @Test
    fun `撞数被抓（精度高于摘要）`() {
        // 888.8 撞 889：精度变高，四舍五入解释不通
        val r = verifyReport("某列的均值是 888.8。", briefSuperstore)
        assertTrue(r.unsourced.size >= 1, "888.8 应被抓")
    }

    @Test
    fun `整数化四舍五入仍放行`() {
        val r = verifyReport("均值约 230，中位数约 54。", briefSuperstore)
        assertEquals(0, r.unsourced.size)
    }

    @Test
    fun `编造数被抓`() {
        val r = verifyReport("销售额合计 9,999,999.99 元，共 12345 单。", briefSuperstore)
        assertTrue(r.unsourced.size >= 2, "两个编造数都应被抓")
    }

    @Test
    fun `假设句豁免（数字少）与不豁免（数字多）`() {
        val hypo = "假设该值真实为 839.998，则落回正常区间。"
        assertEquals(0, verifyReport(hypo, briefSuperstore).unsourced.size)
        // 数字 >2 个：多项事实陈述，不豁免
        val multi = "假设产能跟上，明年销售额可能达到 123,456.78 元、增长 45.6%、共 7,890 单。"
        assertTrue(verifyReport(multi, briefSuperstore).unsourced.size >= 1, "多数字假设句不该整行豁免")
    }

    @Test
    fun `算式验算——算对不报、算错报`() {
        val good = "占比 50.6%（算式：1,161,401.52 ÷ 2,297,201.08 = 0.5056）。"
        assertEquals(0, verifyReport(good, briefSuperstore).arithmetic.size)
        val bad = "占比 80%（算式：1,161,401.52 ÷ 2,297,201.08 = 0.8）。"
        assertTrue(verifyReport(bad, briefSuperstore).arithmetic.size == 1, "0.8 算不通应报")
    }

    @Test
    fun `关系校验——倍数与方向`() {
        // West 是 South 的 1.85 倍（实算 725457.94/391721.9 = 1.85）
        val ok = "West 区销售额是 South 区的 1.85 倍。"
        assertEquals(0, verifyReport(ok, briefSuperstore).relations.size)
        val wrong = "West 区销售额是 South 区的 3.5 倍。"
        assertTrue(verifyReport(wrong, briefSuperstore).relations.size >= 1, "3.5 倍算不通应报")
        // 方向说反：Consumer 高于 Corporate（均值 223.73 < 233.82——单组均值口径）
        val dirWrong = "Consumer 的单组均值高于 Corporate。"
        val r = verifyReport(dirWrong, briefSuperstore)
        assertTrue(r.relations.any { it.expected.contains("方向说反") }, r.relations.joinToString { it.expected })
    }

    @Test
    fun `月度环比方向——2018-12 环比下降说成上升被抓`() {
        // 摘要里 2018-12 环比是 -29.2%（负）
        val r = verifyReport("2018-12 环比上升 29.2%。", briefSuperstore)
        assertTrue(r.relations.any { it.expected.contains("方向说反") }, r.relations.joinToString { it.expected })
    }

    @Test
    fun `隐含算式——A 是 B 的 Z 倍（不写算式符号）`() {
        // 226.49 ÷ 54.49 ≈ 4.16——写成 4.2 倍（容差内放行并授权）
        val ok = "Sales 的均值 226.49 是中位数 54.49 的 4.2 倍。"
        val rOk = verifyReport(ok, briefSuperstore)
        assertEquals(0, rOk.arithmetic.size, rOk.arithmetic.joinToString { "${it.claimed}: ${it.context}" })
        // 写错倍数 → arithmetic
        val bad = "Sales 的均值 226.49 是中位数 54.49 的 9.9 倍。"
        assertTrue(verifyReport(bad, briefSuperstore).arithmetic.size == 1)
    }

    @Test
    fun `字段名幻觉——点名不存在的列`() {
        val r = verifyReport("按 Customer Level 列分组可见明显分层。", briefSuperstore)
        assertTrue(r.fields.any { it.field == "Customer Level" }, r.fields.joinToString { it.field })
    }

    @Test
    fun `describeVerification 汇总文案`() {
        val clean = verifyReport("均值约 230。", briefSuperstore)
        assertTrue(describeVerification(clean).startsWith("已核对"))
        assertTrue(countHardIssues(clean) == 0)
    }
}
