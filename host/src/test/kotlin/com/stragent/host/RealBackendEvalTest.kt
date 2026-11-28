package com.stragent.host

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.ai.SEMANTIC_REVIEW_PROMPT
import com.stragent.core.ai.StepError
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ToolSpec
import com.stragent.core.ports.ToolTurn
import com.stragent.core.ports.TurnMessage
import com.stragent.core.report.ReportLib
import com.stragent.core.report.WritingAudit
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport
import com.stragent.data.HttpLlmClient
import com.stragent.data.STEP_ERR_AUTH
import com.stragent.data.STEP_ERR_NO_KEY
import com.stragent.data.StepfunConfig
import com.stragent.host.eval.JudgeScore
import com.stragent.host.eval.ReportQualityJudge
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * T-061 · 真实 OpenAI 兼容后端抽样（opt-in，**不进默认 test**）。
 *
 * 与 T-052 / T-060 的区别：
 *  - T-052 的 48 份正文由助手撰写，验证的是流程与 rubric；
 *  - T-060 用本地回放端点验证生产 `HttpLlmClient` 的接线与鉴权；
 *  - 这里把端点换成**真实厂商模型**，用生产客户端 + classic 流水线现场生成报告。
 *
 * 安全与边界：
 *  - Key 只从环境变量 `STRAGENT_REAL_LLM_KEY` 读，写进**内存** Prefs（不落盘、不打印）；
 *  - 默认模型 `deepseek-flash`，可用 `STRAGENT_REAL_LLM_MODEL` / `STRAGENT_REAL_LLM_BASE_URL` 覆盖；
 *  - `STRAGENT_REAL_LLM_TYPES` 可限定类型（逗号分隔），`STRAGENT_REAL_LLM_RUNS` 控制轮次（1–3）；
 *  - `STRAGENT_REAL_LLM_SCORECARD` 可改评分卡文件名（分批跑时避免互相覆盖）；
 *  - 单份样本失败（拒答 / 空回 / 截断）记录为失败行后继续；鉴权/缺 Key 整批上抛；
 *  - 一份真实模型的一份样本不等于质量结论：没有人类标签、没有多模型对照，结果只作抽样。
 *
 * 运行：
 * ```
 * JAVA_HOME=<JDK17> STRAGENT_REAL_LLM_KEY=... ./gradlew --offline :host:realLlmTest
 * ```
 * 报告与评分卡写到 `host/build/model-eval/real/`（gitignored）。
 */
class RealBackendEvalTest {

    private data class Row(
        val id: String,
        val typeId: String,
        val targetChars: Int,
        val actualChars: Int,
        val ratio: Double,
        val hard: Int,
        val banned: Int,
        val structureIssues: Int,
        val lengthIssues: Int,
        val judge: JudgeScore?,
        val revisions: Int,
        val gateFailed: Boolean,
        val truncated: Boolean,
        val calls: Int,
        val failedCalls: Int,
        val reviewCalls: Int,
        val reviewFailures: Int,
        /** T-062：审查失败时 qualityNotes 里的显式注记（非 null = 降级可见） */
        val semanticNote: String?,
        val tokens: Int,
        val elapsedMs: Long,
        val error: String? = null,
    )

    /** 只在内存里放 Key：真实 Key 绝不落临时目录。 */
    private class MemoryPrefs : PrefsStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = values[key] ?: fallback
        override fun set(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    /** 记录真实调用次数与 usage；生产路径仍走 [HttpLlmClient]。 */
    private class CountingLlm(private val delegate: LlmClient) : LlmClient {
        var calls = 0
            private set
        var failedCalls = 0
            private set
        var reviewCalls = 0
            private set
        var reviewFailures = 0
            private set
        var tokens = 0
            private set

        /** 语义审查请求按 system 提示词逐字识别：它固定用 [SEMANTIC_REVIEW_PROMPT]。 */
        private fun isReview(messages: List<Pair<String, String>>): Boolean =
            messages.any { it.second == SEMANTIC_REVIEW_PROMPT }

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            val review = isReview(messages)
            return try {
                delegate.chat(model, messages, temperature, maxTokens).also {
                    calls += 1
                    tokens += it.totalTokens
                    if (review) reviewCalls += 1
                }
            } catch (e: Throwable) {
                failedCalls += 1
                if (review) reviewFailures += 1
                throw e
            }
        }

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): ChatResult {
            val review = isReview(messages)
            return try {
                delegate.streamChat(model, messages, temperature, maxTokens, onToken).also {
                    calls += 1
                    tokens += it.totalTokens
                    if (review) reviewCalls += 1
                }
            } catch (e: Throwable) {
                failedCalls += 1
                if (review) reviewFailures += 1
                throw e
            }
        }

        override fun supportsNativeTools(model: String): Boolean = delegate.supportsNativeTools(model)

        override fun chatTools(
            model: String,
            messages: List<TurnMessage>,
            temperature: Double,
            maxTokens: Int,
            tools: List<ToolSpec>,
            toolChoice: String,
            responseFormat: String?,
        ): ToolTurn = try {
            delegate.chatTools(
                model, messages, temperature, maxTokens, tools, toolChoice, responseFormat,
            ).also {
                calls += 1
                tokens += it.totalTokens
            }
        } catch (e: Throwable) {
            failedCalls += 1
            throw e
        }
    }

    @Test
    fun `real backend generates reports across report types`() {
        val apiKey = System.getenv("STRAGENT_REAL_LLM_KEY").orEmpty().trim()
        assumeTrue("未设置 STRAGENT_REAL_LLM_KEY，跳过真实后端抽样", apiKey.isNotEmpty())

        val model = env("STRAGENT_REAL_LLM_MODEL", "deepseek-flash")
        val runs = env("STRAGENT_REAL_LLM_RUNS", "1").toIntOrNull()?.coerceIn(1, 3) ?: 1
        val typeFilter = env("STRAGENT_REAL_LLM_TYPES", "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val baseUrlOverride = System.getenv("STRAGENT_REAL_LLM_BASE_URL")
            ?.trim()?.takeIf { it.isNotEmpty() }

        val prefs = MemoryPrefs()
        prefs.set(StepfunConfig.VENDOR_STORAGE, "deepseek")
        prefs.set(StepfunConfig.CUSTOM_MODEL_STORAGE, model)
        prefs.set(StepfunConfig.KEY_STORAGE, apiKey)
        val client = CountingLlm(HttpLlmClient(prefs, baseUrlOverride = baseUrlOverride))

        // 最小握手：先证明真实端点可达、生产客户端能解析 content 与 usage。
        // 预算给 512 而不是 16：推理模型会先用输出预算思考，预算太小会只留 reasoning、content 为空。
        val probe = client.chat(model, listOf("user" to "Reply with OK only."), 0.0, 512)
        assertTrue(probe.text.isNotBlank(), "真实后端应返回非空内容")
        assertTrue(probe.totalTokens > 0, "真实后端应返回 usage.total_tokens")
        println("[real-llm] handshake ok: model=$model tokens=${probe.totalTokens}")

        val samples = ModelSubstituteSamples.all
            .filter { typeFilter.isEmpty() || it.typeId in typeFilter }
        assertTrue(samples.isNotEmpty(), "STRAGENT_REAL_LLM_TYPES 过滤后没有样本")
        val outDir = File("build/model-eval/real").apply { mkdirs() }
        val rows = mutableListOf<Row>()

        for (run in 1..runs) {
            for (sample in samples) {
                val id = "${sample.id}-r$run"
                ReportLib.setReportType(prefs, sample.typeId)
                ReportLib.setReportStyle(prefs, sample.styleId)
                ReportLib.setIndustry(prefs, sample.industryId)

                val dataset = sample.dataset()
                val brief = buildDatasetBrief(dataset, prefs)
                val callsBefore = client.calls
                val tokensBefore = client.tokens
                val failedBefore = client.failedCalls
                val reviewBefore = client.reviewCalls
                val reviewFailuresBefore = client.reviewFailures
                val started = System.nanoTime()
                // 真实模型会拒答/空回/截断：逐样本记录失败继续跑，别让一份样本毁掉整批。
                val outcome = runCatching {
                    InsightOrchestrator(client, prefs, model = model).generateInsight(dataset)
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val error = outcome.exceptionOrNull()?.let { e ->
                    // 鉴权/缺 Key 不是样本问题：整批判红，避免把配置错误记成模型质量。
                    if (e is StepError && (e.code == STEP_ERR_AUTH || e.code == STEP_ERR_NO_KEY)) throw e
                    "${e::class.simpleName}: ${e.message.orEmpty()}".replace('\n', ' ')
                }
                val result = outcome.getOrNull()
                if (error != null || result == null || result.report.isBlank()) {
                    val note = error ?: "空报告：generateInsight 返回了空白正文"
                    rows += Row(
                        id = id,
                        typeId = sample.typeId,
                        targetChars = 0,
                        actualChars = 0,
                        ratio = 0.0,
                        hard = 0,
                        banned = 0,
                        structureIssues = 0,
                        lengthIssues = 0,
                        judge = null,
                        revisions = 0,
                        gateFailed = false,
                        truncated = false,
                        calls = client.calls - callsBefore,
                        failedCalls = client.failedCalls - failedBefore,
                        reviewCalls = client.reviewCalls - reviewBefore,
                        reviewFailures = client.reviewFailures - reviewFailuresBefore,
                        semanticNote = null,
                        tokens = client.tokens - tokensBefore,
                        elapsedMs = elapsedMs,
                        error = note,
                    )
                    File(outDir, "$id.error.txt").writeText("$note\n", Charsets.UTF_8)
                    println(
                        "[real-llm] $id ERROR $note " +
                            "calls=${client.calls - callsBefore} failed=${client.failedCalls - failedBefore} " +
                            "tokens=${client.tokens - tokensBefore} ms=$elapsedMs",
                    )
                    continue
                }

                assertEquals("classic", result.engineId, "$id 应走 classic 路径")
                val structured = isStructuredReportType(sample.typeId)
                val verification = verifyReport(result.report, brief)
                val audit = WritingAudit.audit(result.report, result.targetChars, structured)
                val judge = ReportQualityJudge.score(result.report, sample.typeId)
                val row = Row(
                    id = id,
                    typeId = sample.typeId,
                    targetChars = result.targetChars,
                    actualChars = audit.actualChars,
                    ratio = audit.actualChars.toDouble() / result.targetChars,
                    hard = countHardIssues(verification),
                    banned = audit.bannedHits,
                    structureIssues = audit.issues.count { it.kind == "缺结构块" },
                    lengthIssues = audit.issues.count { it.kind == "篇幅" },
                    judge = judge,
                    revisions = result.gateRevisions,
                    gateFailed = result.gateFailed,
                    truncated = result.truncated,
                    calls = client.calls - callsBefore,
                    failedCalls = client.failedCalls - failedBefore,
                    reviewCalls = client.reviewCalls - reviewBefore,
                    reviewFailures = client.reviewFailures - reviewFailuresBefore,
                    semanticNote = result.qualityNotes.firstOrNull { it.contains("语义审查未完成") },
                    tokens = client.tokens - tokensBefore,
                    elapsedMs = elapsedMs,
                )
                rows += row
                File(outDir, "$id.md").writeText(result.report, Charsets.UTF_8)
                println(
                    "[real-llm] $id chars=${row.actualChars}/${row.targetChars} " +
                        "hard=${row.hard} banned=${row.banned} judge=${fmt(row.judge?.total ?: 0.0)} " +
                        "grade=${row.judge?.grade ?: "—"} calls=${row.calls} failed=${row.failedCalls} " +
                        "review=${row.reviewCalls}/${row.reviewCalls + row.reviewFailures} " +
                        "reviewNote=${row.semanticNote ?: "-"} " +
                        "tokens=${row.tokens} ms=${row.elapsedMs}",
                )
            }
        }

        val scorecard = buildScorecard(
            rows = rows,
            model = model,
            runs = runs,
            baseUrl = baseUrlOverride ?: StepfunConfig(MemoryPrefs()).getVendor("deepseek").baseUrl,
            totalCalls = client.calls,
            totalTokens = client.tokens,
        )
        val scorecardName = env("STRAGENT_REAL_LLM_SCORECARD", "real-backend-scorecard.md")
        val out = File(outDir, scorecardName)
        out.writeText(scorecard, Charsets.UTF_8)
        print(scorecard)
        println("real backend scorecard written to ${out.absolutePath}")

        assertEquals(samples.size * runs, rows.size)
        val succeeded = rows.filter { it.error == null }
        assertTrue(succeeded.isNotEmpty(), "至少应有一份真实报告成功，当前全部失败")
        assertTrue(succeeded.all { it.tokens > 0 }, "成功报告都应有真实 usage")
        assertTrue(succeeded.all { it.actualChars > 0 }, "成功报告都应有非空正文")
        println(
            "[real-llm] 成功 ${succeeded.size}/${rows.size}，失败 ${rows.size - succeeded.size}；" +
                "审查成功 ${client.reviewCalls}，审查失败 ${client.reviewFailures}",
        )
    }

    private fun buildScorecard(
        rows: List<Row>,
        model: String,
        runs: Int,
        baseUrl: String,
        totalCalls: Int,
        totalTokens: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("真实 OpenAI 兼容后端抽样评分卡\n\n")
        sb.append("条件：真实端点 `$baseUrl`，模型 `$model`，")
        sb.append("${rows.map { it.typeId }.distinct().size} 类型 × $runs 轮；")
        sb.append("生产 `HttpLlmClient` + classic `InsightOrchestrator` 现场生成。\n")
        sb.append("Key 只从环境变量读取、仅驻内存，未写入本卡或仓库。\n\n")

        sb.append("## 逐项\n\n")
        sb.append("| id | 类型 | 字/目标 | 比 | 硬伤 | 套话 | 缺结构 | 篇幅 | rubric | 档 | 修订 | gateFailed | 截断 | calls | 失败调用 | 审查(失败) | 审查注记 | tokens | ms |\n")
        sb.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | --- | ---: | ---: | --- | --- | ---: | ---: |\n")
        for (row in rows) {
            if (row.error != null) {
                sb.append("| ${row.id} | ${row.typeId} | — | — | — | — | — | — | — | — | — | — | — | ")
                sb.append("${row.calls} | ${row.failedCalls} | ")
                sb.append("${row.reviewCalls}(${row.reviewFailures}) | — | ${row.tokens} | ${row.elapsedMs} |\n")
                continue
            }
            sb.append("| ${row.id} | ${row.typeId} | ${row.actualChars}/${row.targetChars} | ")
            sb.append("${fmt(row.ratio)} | ${row.hard} | ${row.banned} | ${row.structureIssues} | ")
            sb.append("${row.lengthIssues} | ${fmt(row.judge?.total ?: 0.0)} | ${row.judge?.grade ?: "—"} | ")
            sb.append("${row.revisions} | ${row.gateFailed} | ${row.truncated} | ${row.calls} | ")
            sb.append("${row.failedCalls} | ${row.reviewCalls}(${row.reviewFailures}) | ")
            sb.append("${if (row.semanticNote != null) "未完成" else "—"} | ")
            sb.append("${row.tokens} | ${row.elapsedMs} |\n")
        }

        val succeeded = rows.filter { it.error == null }
        val failed = rows.filter { it.error != null }
        val judged = rows.mapNotNull { it.judge }
        val pass = succeeded.count { it.hard == 0 && it.banned == 0 }
        val latencies = rows.map { it.elapsedMs }.sorted()
        val p95 = if (latencies.isEmpty()) 0 else latencies[((latencies.size - 1) * 0.95).toInt()]
        sb.append("\n## 汇总\n\n")
        sb.append("- 成功生成：**${succeeded.size}/${rows.size}**，失败 ${failed.size} 份。\n")
        sb.append("- 硬闸 / 套话双零：**$pass/${succeeded.size}**（按成功样本计）；")
        sb.append("硬伤合计 ${succeeded.sumOf { it.hard }}，套话合计 ${succeeded.sumOf { it.banned }}。\n")
        sb.append("- rubric 均分：**${fmt(if (judged.isEmpty()) 0.0 else judged.map { it.total }.average())}**；")
        sb.append("档位分布：${judged.groupingBy { it.grade }.eachCount()}。\n")
        sb.append("- 语义审查：成功 **${rows.sumOf { it.reviewCalls }}** 次，失败 **${rows.sumOf { it.reviewFailures }}** 次；")
        sb.append("失败时显式写入 `qualityNotes`（「语义审查未完成」），本卡计数 **${succeeded.count { it.semanticNote != null }}/${succeeded.size}** 份可见降级（T-062）。\n")
        sb.append("- 真实调用：成功 **$totalCalls 次**、失败 **${rows.sumOf { it.failedCalls }} 次**，usage 合计 **$totalTokens tokens**；")
        sb.append("平均 **${fmt(rows.map { it.elapsedMs }.average())} ms**/份，P95 **$p95 ms**。\n")
        sb.append("- gateFailed ${succeeded.count { it.gateFailed }} 份，截断 ${succeeded.count { it.truncated }} 份。\n")

        if (failed.isNotEmpty()) {
            sb.append("\n## 失败样本\n\n")
            sb.append("| id | 类型 | 错误 | 失败调用 | 已成功调用 | tokens | ms |\n")
            sb.append("| --- | --- | --- | ---: | ---: | ---: | ---: |\n")
            for (row in failed) {
                sb.append("| ${row.id} | ${row.typeId} | ${row.error} | ${row.failedCalls} | ")
                sb.append("${row.calls} | ${row.tokens} | ${row.elapsedMs} |\n")
            }
        }

        sb.append("\n## 边界（诚实）\n\n")
        sb.append("- 单一真实模型、单温度轮次的抽样，不是多模型对照，也不是 D18 的 48 份 factorial。\n")
        sb.append("- rubric 在 T-052 的助手语料上校准，跨模型只作相对参考，不是人类评分。\n")
        sb.append("- 没有人类双标注与 κ；不能据此宣称 D17 §8.1 第 4 项完全达成。\n")
        sb.append("- token 是端点返回的 usage（真实计费口径）；单价与总成本需按厂商账单核对，本卡不估算。\n")
        sb.append("- 失败样本按真实客户端异常原样记录；不重试、不换模型、不补写正文。\n")
        sb.append("- 报告正文写入 `host/build/model-eval/real/`，只作证据，不进入产品数据。\n")
        return sb.toString()
    }

    private fun env(name: String, fallback: String): String =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}
