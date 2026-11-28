package com.stragent.core.security

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-196 · 数据载体注入探针——管线级断言（语料 I2 增补的靶场化）。
 *
 * 裸短语语料（T-140）证明的是「信号词能被规则抓住」；本类证明的是**载体形态**：
 * 指令藏在单元格/行里、周围全是合法业务数据时——扫描仍命中、净化仍逐格替换、
 * 干净业务行零误报。信号在噪声里的存活才是生产攻击面的真实口径。
 */
class DataCarrierInjectionTest {

    /** 把文本放进 2x2 数据集的载荷格（其余格为合法业务数据） */
    private fun datasetWithPayload(payload: String): Dataset = Dataset(
        name = "t",
        importedAt = 0L,
        columns = listOf("门店", "备注"),
        rows = listOf(
            listOf("华东", "正常业务记录 1200"),
            listOf("华南", payload),
        ),
        columnTypes = listOf(ColumnType.TEXT, ColumnType.TEXT),
    )

    @Test
    fun `载体探针——对抗语料嵌进单元格仍命中扫描且被逐格中和`() {
        for ((ruleId, examples) in InjectionCorpus.adversarial) {
            for (payload in examples) {
                val ds = datasetWithPayload(payload)
                val report = PromptInjectionHeuristics.scanDataset(ds)
                assertTrue(report.signals.isNotEmpty(), "$ruleId 载体应命中扫描：$payload")
                val (safe, hits) = PromptInjectionHeuristics.sanitizeForBrief(ds)
                assertTrue(hits >= 1, "$ruleId 载体应命中净化：$payload")
                val cell = safe.rows[1][1]
                assertTrue(cell.contains("[已过滤"), "载荷格应带净化标记：$cell")
                assertTrue(!cell.contains(payload), "载荷原文应消失：$cell")
            }
        }
    }

    @Test
    fun `干净行——真实业务行（含载体形态）零误报`() {
        for (text in InjectionCorpus.clean) {
            val ds = datasetWithPayload(text)
            val report = PromptInjectionHeuristics.scanDataset(ds)
            assertTrue(report.signals.isEmpty(), "clean 误报：$text → ${report.signals}")
        }
    }

    @Test
    fun `语料规模——载体扩展后对抗 47 条 clean 46 条`() {
        assertEquals(47, InjectionCorpus.adversarial.values.sumOf { it.size })
        assertEquals(46, InjectionCorpus.clean.size)
    }
}
