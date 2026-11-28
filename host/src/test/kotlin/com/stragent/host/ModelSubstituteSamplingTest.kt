package com.stragent.host

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.looksLikeIdColumn
import com.stragent.core.dataset.summarize
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.report.ReportLib
import com.stragent.core.report.WritingAudit
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.report.targetLengthFor
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport
import com.stragent.data.FilePrefs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-037 · 真实模型质量抽样 —— **模型替代**回放。
 *
 * 环境里没有真实第三方模型的可用 Key，本测试由 agent 充当「模型替代」：先按每份
 * brief 人工撰写报告（`src/test/resources/model-substitute/` 目录下的 md），再让**真实经典流水线**
 * （`InsightOrchestrator.generateInsight`：brief → 提示词 → chatStep → 语义审查）回放，
 * 最后用两道确定性闸门复核：
 *  ① `verifyReport`：报告里每个数字都要能回到 brief，算式/关系/字段名不许出错（硬项=0）；
 *  ② `WritingAudit`：套话黑名单、结构化类型的四段结构块、篇幅区间（0.5×–2.0× 目标）。
 *
 * 它能证明什么、不能证明什么（别自欺）：
 *  - 能：真实流水线会把 brief 送到模型位、报告原样返回；8 种报告类型各有 1 份能过双闸的样本；
 *  - 不能：真实第三方模型的写作水平、拒答率、token 成本，也不能替代真实 Key 下的抽样验收。
 */
class ModelSubstituteSamplingTest {

    /** 按 prompt 形状分流：语义审查 → 空问题；其余 → 本次要回放的报告 */
    private class ModelSubstituteLlm(private val report: String) : LlmClient {
        var reportCalls = 0
            private set
        var reviewCalls = 0
            private set

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            val joined = messages.joinToString("\n") { it.second }
            val text = when {
                joined.contains("\"issues\"") -> {
                    reviewCalls += 1
                    "{\"issues\":[]}"
                }
                joined.contains("<followup_requirements>") -> ""
                else -> {
                    reportCalls += 1
                    report
                }
            }
            return ChatResult(text = text, totalTokens = (text.length / 4).coerceAtLeast(1), truncated = false)
        }
    }

    @Test
    fun `eight report types replay through the classic pipeline with zero hard findings`() {
        val outDir = File("build/model-substitute").apply { mkdirs() }
        val scorecard = StringBuilder()
        scorecard.append("T-037 模型替代抽样评分卡（真实流水线回放；数字闸门 + 写作审计）\n")
        scorecard.append("样本 | 类型 | 目标字数 | 实际字数 | 篇幅比 | 核对数字 | 硬问题 | 算式待确认 | 套话命中 | 结构块 | 篇幅区间\n")

        for (sample in ModelSubstituteSamples.all) {
            val authored = readReport(sample)
            val home = Files.createTempDirectory("t037-sample-${sample.id}-").toFile()
            val prefs = FilePrefs(home)
            ReportLib.setReportType(prefs, sample.typeId)
            ReportLib.setReportStyle(prefs, sample.styleId)
            ReportLib.setIndustry(prefs, sample.industryId)

            val dataset = sample.dataset()
            val brief = buildDatasetBrief(dataset, prefs)
            val expectedTarget = expectedTargetChars(dataset, brief, prefs)
            val llm = ModelSubstituteLlm(authored)
            val result = InsightOrchestrator(llm, prefs, model = "model-substitute")
                .generateInsight(dataset)

            assertEquals(authored, result.report, "流水线应把模型回包原样作为报告：${sample.id}")
            assertEquals(expectedTarget, result.targetChars, "目标篇幅应按真实口径计算：${sample.id}")
            assertEquals("classic", result.engineId)
            assertFalse(result.truncated, "模型替代回放不应截断：${sample.id}")
            assertTrue(result.semantic.isEmpty(), "语义审查回包为空问题：${sample.id}")
            assertEquals(1, llm.reportCalls, "报告生成应只调用一次：${sample.id}")
            assertEquals(1, llm.reviewCalls, "语义审查应调用一次：${sample.id}")

            val verification = verifyReport(result.report, brief)
            val hard = countHardIssues(verification)
            val structured = isStructuredReportType(sample.typeId)
            val audit = WritingAudit.audit(result.report, result.targetChars, structured)
            val structureIssues = audit.issues.filter { it.kind == "缺结构块" }
            val lengthIssues = audit.issues.filter { it.kind == "篇幅" }
            val ratio = result.report.replace(Regex("\\s"), "").length.toDouble() / result.targetChars

            val detail = buildString {
                append("样本 ${sample.id} 未过硬闸门：")
                if (verification.unsourced.isNotEmpty()) {
                    append("\n  未溯源：").append(verification.unsourced.joinToString("；") { "${it.value} @ ${it.context}" })
                }
                if (verification.arithmetic.isNotEmpty()) {
                    append("\n  算式：").append(verification.arithmetic.joinToString("；") { "${it.context} 声称 ${it.claimed}，应为 ${it.expected}" })
                }
                if (verification.relations.isNotEmpty()) {
                    append("\n  关系：").append(verification.relations.joinToString("；") { "${it.context} 声称 ${it.claimed}，应为 ${it.expected}" })
                }
                if (verification.fields.isNotEmpty()) {
                    append("\n  字段：").append(verification.fields.joinToString("；") { "${it.field} @ ${it.context}" })
                }
                if (audit.hard) append("\n  套话命中 ${audit.bannedHits} 处：").append(audit.issues.filter { it.kind == "套话" }.joinToString("；") { it.detail })
                if (structureIssues.isNotEmpty()) append("\n  结构块：").append(structureIssues.joinToString("；") { it.detail })
                if (lengthIssues.isNotEmpty()) append("\n  篇幅：").append(lengthIssues.joinToString("；") { it.detail })
            }
            assertEquals(0, hard, detail)
            assertFalse(audit.hard, detail)
            assertTrue(structureIssues.isEmpty(), detail)
            assertTrue(lengthIssues.isEmpty(), detail)
            assertTrue(ratio >= WritingAudit.LENGTH_RATIO_FLOOR && ratio <= WritingAudit.LENGTH_RATIO_CEILING, detail)

            scorecard.append(
                listOf(
                    sample.id,
                    sample.typeId,
                    expectedTarget.toString(),
                    audit.actualChars.toString(),
                    String.format("%.2f", ratio),
                    verification.checked.toString(),
                    hard.toString(),
                    verification.formula.size.toString(),
                    audit.bannedHits.toString(),
                    if (structured) if (structureIssues.isEmpty()) "全" else "缺" else "不适用",
                    if (lengthIssues.isEmpty()) "合规" else "偏离",
                ).joinToString(" | "),
            ).append('\n')
        }

        val scorecardFile = File(outDir, "scorecard.txt")
        scorecardFile.writeText(scorecard.toString(), Charsets.UTF_8)
        print(scorecard)
        println("T-037 scorecard written to ${scorecardFile.absolutePath}")
    }

    private fun readReport(sample: ModelSubstituteSample): String {
        val path = "/model-substitute/${sample.id}.md"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("缺少模型替代报告资源：$path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun expectedTargetChars(
        dataset: com.stragent.core.dataset.Dataset,
        brief: String,
        prefs: FilePrefs,
    ): Int {
        val stats = summarize(dataset)
        val numericColumns = stats.count { it.type == ColumnType.NUMBER && !looksLikeIdColumn(it.name) }
        val groupColumns = stats.count {
            it.type == ColumnType.TEXT && !looksLikeIdColumn(it.name) &&
                (it.distinct ?: 0) >= 2 && (it.distinct ?: 0) <= 40
        }
        return targetLengthFor(
            brief, dataset.rows.size, dataset.columns.size, numericColumns, groupColumns,
            ReportLib.getReportStyle(prefs).length, ReportLib.getReportType(prefs).id,
        ).chars
    }
}
