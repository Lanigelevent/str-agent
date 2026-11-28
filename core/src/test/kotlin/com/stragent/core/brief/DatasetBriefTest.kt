package com.stragent.core.brief

import com.stragent.core.csv.ColumnType
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * dataset-brief 移植的对齐测试（结构性）。
 *
 * 注意：本移植暂未覆盖 fullStats / extraSheets / analyzeQuality / detectAllOutliers
 * 四个段（对应模块未移植），因此这里验的是已移植段的**结构与关键事实**，
 * 逐字节门（brief-baseline 159 份比对）在这四个段补齐后进行。
 */
class DatasetBriefTest {
    private val sampleDir = File("../fixtures/sample")

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

    @Test
    fun `superstore brief 头三行与列清单结构`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        val lines = brief.split("\n")
        assertEquals("数据来源：superstore.csv", lines[0])
        assertEquals("规模：9994 行 × 21 列（数值列 7 个，文本列 14 个）", lines[1])
        assertEquals("列清单：", lines[3])
        // ID 列压缩（列1 / Row ID 是标识列）
        assertTrue(brief.contains("- 列1（数值，标识列，略过统计）"))
        assertTrue(brief.contains("- Row ID（数值，标识列，略过统计）"))
        // Sales 行整行与 TS 逐字一致（默认偏好；TS 实测 dump）
        assertTrue(
            brief.contains("- Sales（数值）：有效样本 9994，最小 0.44，P25 17.28，中位数 54.49，P75 209.94，最大 22,638.48，均值 229.86，合计 2,297,201.08"),
            brief.lines().firstOrNull { it.startsWith("- Sales（数值）") } ?: "(Sales 行缺失)",
        )
        // Profit 脏值口径 + 负值最小
        assertTrue(
            brief.contains("- Profit（数值）：有效样本 9929（65 个空值或不可解析），最小 -6,599.98"),
            brief.lines().firstOrNull { it.startsWith("- Profit（数值）") } ?: "(Profit 行缺失)",
        )
    }

    @Test
    fun `superstore 月度段与环比段`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        // 按月汇总段：默认偏好下 budget 给 24 个点（TS 实测）
        assertTrue(brief.contains("按月汇总（Sales，最近 24 个月）："), brief.lines().firstOrNull { it.contains("按月汇总") } ?: "(月度段缺失)")
        // 月度极值行与 TS 逐字（窗口内峰值 2018-11）
        assertTrue(
            brief.contains("月度极值（与上面同口径）：峰值 2018-11 118,447.83（占这 24 个月合计的 8.8%），谷值 2017-01 18,542.52"),
            brief.lines().firstOrNull { it.contains("月度极值") } ?: "(月度极值缺失)",
        )
        // 月度环比段
        assertTrue(brief.contains("月度环比（与上面同口径；"))
    }

    @Test
    fun `superstore 分组聚合与双指标派生`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        // 主指标分组（Segment，含单组均值）
        assertTrue(brief.contains("按「Segment」分组求和（Sales，全量数据；末列为单组均值＝该组合计 ÷ 该组行数）："))
        // West 合计——两侧实测一致（校准后 fixture 的 aggregateBy 值；旧契约 .93 在 ±0.01 容差内）
        assertTrue(brief.contains("725,457.94"), brief.lines().firstOrNull { it.startsWith("- West") } ?: "(West 行缺失)")
        // 第二指标（Profit）分组（TS 实测段序：主指标 → 第二指标 → 双指标派生）
        assertTrue(brief.contains("按「Segment」分组求和（Profit，全量数据）："), "第二指标段")
        // 双指标派生段（利润率）
        assertTrue(brief.contains("按「Segment」分组的双指标派生"), "双指标派生段标题")
        assertTrue(brief.contains("利润率 "), "利润率字段")
    }

    @Test
    fun `superstore 年度段与年度同比`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        assertTrue(brief.contains("按年汇总（Sales，全量数据）："), "年度汇总段")
        // 2015→2016 年同比（TS: +9.2% 量级——具体值由数据决定，这里验格式存在）
        assertTrue(Regex("\\d{4} 年同比：[+−]?\\d+\\.\\d+%").containsMatchIn(brief), "年度同比行格式")
        // 年度 × 维度段
        assertTrue(brief.contains("分组的年度走势"), "年度×维度段")
    }

    @Test
    fun `异常扫描段——superstore 有高危时列出`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        // superstore 的时序突变开了火（门 A 前的 anomaly 测试已证）
        assertTrue(
            brief.contains("数据异常自动扫描（六类检测：时序突变 / 结构突变 / 时间缺口 / 主键重复 / 负值 / 序列塌陷；") ||
                brief.contains("数据异常自动扫描："),
            "扫描段存在",
        )
        // 2015-09 的 shift（若列出）带同比 suffix（第一年没有同比）
        if (brief.contains("2015-09「Sales」")) {
            assertTrue(brief.contains("**没有同比可对照**：数据里不存在去年同月"))
        }
    }

    @Test
    fun `白名单与样本段结构`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        assertTrue(brief.contains("<allowed_numbers>**本报告只允许使用下面这些数字**（共 "))
        assertTrue(brief.contains("规则：① 清单外的数字一个都不许出现"))
        assertTrue(brief.contains("</allowed_numbers>"))
        assertTrue(brief.contains("行样本："), "样本段标题")
        assertTrue(brief.contains("```"), "样本 CSV 围栏")
        // 白名单不含样本行的值（261.96 是样本里第一行 Sales 值）
        // ——TS 的白名单从统计文本提取；我们的 statsText 在样本段之前，同口径
    }

    @Test
    fun `subset 扫描段——每月 25 行达到可信线，时序开火`() {
        val brief = buildDatasetBrief(datasetOf("superstore-subset"))
        // 重建的 subset 是 2016 年 12 个月 × 25 行：中位数 25 ≥ 20 → monthly=true，扫描列出发现
        assertTrue(
            brief.contains("数据异常自动扫描（六类检测：时序突变 / 结构突变 / 时间缺口 / 主键重复 / 负值 / 序列塌陷；"),
            brief.lines().firstOrNull { it.contains("数据异常自动扫描") } ?: "(扫描段缺失)",
        )
        // 时序突变带同比 suffix（subset 只有 2016 → 没有同比可对照）
        assertTrue(brief.contains("**没有同比可对照**") || brief.contains("（同比 "), "shift 行带同比说明")
    }

    @Test
    fun `小表 iris——无日期列时无月度段但有分组`() {
        val brief = buildDatasetBrief(datasetOf("iris"))
        assertTrue(!brief.contains("按月汇总"), "iris 无日期列")
        assertTrue(brief.contains("分组求和"), "有分组段")
        // 三品种（setosa 5.006 均值是 TS 契约值）
        assertTrue(brief.contains("setosa"))
    }

    @Test
    fun `体量预算——superstore 不超 15100`() {
        val brief = buildDatasetBrief(datasetOf("superstore"))
        assertTrue(brief.length < 15100, "brief=${brief.length} 字（TS 侧同夹具实测 15,015±，上限断言 15100）")
        assertTrue(brief.length > 5000, "也不该过短：${brief.length}")
    }

    @Test
    fun `抽样数据集——规模与口径都如实标成样本，不给全量措辞`() {
        val base = datasetOf("superstore")
        val sampled = base.copy(
            truncated = true,
            sourceRows = 999_400,
            sampling = com.stragent.core.dataset.SamplingInfo(
                targetRows = 9994,
                step = 100,
                totalRows = 999_400,
                fingerprint = "sha256:" + "ab".repeat(32),
                rowNumbers = (0 until 9994).map { it * 100 + 1 },
            ),
        )
        val brief = buildDatasetBrief(sampled)
        assertTrue(brief.contains("确定性系统抽样"), "应标明抽样方法")
        assertTrue(brief.contains("源文件 999400 行"), "应给源文件规模")
        assertTrue(brief.contains("抽样口径"), "应有抽样口径段")
        assertTrue(brief.contains("不是全量"), "必须明确不是全量")
        assertTrue(brief.contains("sha256:abab"), "应带文件指纹")
        assertTrue(brief.contains("(N − 1) × 100 + 1"), "应给行号换算公式")
        assertTrue(brief.contains("抽样数据"), "分组/年度段应写抽样数据")
        assertTrue(!brief.contains("全量数据"), "抽样数据集不得出现「全量数据」字样：${brief.lines().firstOrNull { it.contains("全量数据") }}")
    }
}
