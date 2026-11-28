package com.stragent.host

import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.summarize
import com.stragent.core.report.ReportLib
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.report.targetLengthFor
import com.stragent.data.FilePrefs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-037 辅助：把 8 份样本的 brief 与目标篇幅落盘，供模型替代逐份撰写。
 *
 * 这是**研发辅助测试**（不是门禁）：它只证明样本能被真实流水线解析并产出 brief，
 * 报告质量断言在 [ModelSubstituteSamplingTest]。
 */
class ModelSubstituteBriefDumpTest {

    @Test
    fun `dump briefs for authored reports`() {
        val outDir = File("build/model-substitute/briefs").apply { mkdirs() }
        val index = StringBuilder()
        for (sample in ModelSubstituteSamples.all) {
            val home = Files.createTempDirectory("t037-dump-${sample.id}-").toFile()
            val prefs = FilePrefs(home)
            ReportLib.setReportType(prefs, sample.typeId)
            ReportLib.setReportStyle(prefs, sample.styleId)
            ReportLib.setIndustry(prefs, sample.industryId)

            val dataset = sample.dataset()
            val brief = buildDatasetBrief(dataset, prefs)
            val stats = summarize(dataset)
            val numericColumns = stats.count {
                it.type == ColumnType.NUMBER && !looksLikeIdColumn(it.name)
            }
            val groupColumns = stats.count {
                it.type == ColumnType.TEXT && !looksLikeIdColumn(it.name) &&
                    (it.distinct ?: 0) >= 2 && (it.distinct ?: 0) <= 40
            }
            val target = targetLengthFor(
                brief, dataset.rows.size, dataset.columns.size, numericColumns, groupColumns,
                ReportLib.getReportStyle(prefs).length, ReportLib.getReportType(prefs).id,
            )
            File(outDir, "${sample.id}.md").writeText(brief, Charsets.UTF_8)
            index.append(
                "${sample.id} | type=${sample.typeId} | style=${sample.styleId} | industry=${sample.industryId} | " +
                    "rows=${dataset.rows.size} x cols=${dataset.columns.size} | target=${target.chars} | " +
                    "structured=${isStructuredReportType(sample.typeId)} | briefChars=${brief.length} | ${target.scale}\n",
            )
            assertTrue(brief.contains("列清单"), "brief 应含列清单：${sample.id}")
        }
        File(outDir, "_index.txt").writeText(index.toString(), Charsets.UTF_8)
        println("T-037 briefs dumped to ${outDir.absolutePath}")
    }
}
