package com.stragent.host

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.ai.StepError
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.report.ReportLib
import com.stragent.core.report.WritingAudit
import com.stragent.core.report.isStructuredReportType
import com.stragent.core.verify.countHardIssues
import com.stragent.core.verify.verifyReport
import com.stragent.data.FilePrefs
import com.stragent.data.HttpLlmClient
import com.stragent.data.STEP_ERR_AUTH
import com.stragent.data.STEP_ERR_NO_KEY
import com.stragent.data.StepfunConfig
import com.stragent.host.eval.CohenKappa
import com.stragent.host.eval.JudgeScore
import com.stragent.host.eval.ReportQualityJudge
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-052 · 真实模型评测 MVP —— **模型替身**条件。
 *
 * 真实条件（8 类型 × 3 模型 × 2 温度、2 名人类评审、人类 κ）需要第三方 Key 与真人，
 * 环境里都没有。本测试把三个轴诚实替换为：
 *  - 3 模型 → 3 个质量档（strong / medium / weak，同一后端模型的替身撰写）；
 *  - 2 温度 → 2 个轮次（r1 / r2，两次独立撰写）；
 *  - 2 名人类评审 → 2 个独立评审包（同族模型的独立上下文，见 labels-reviewer-*.tsv）；
 *  - LLM judge → 确定性 rubric（[ReportQualityJudge]），只用报告文本与类型盲评。
 *
 * 能证明：48 份替身报告能过真实流水线与数字硬闸；rubric 与 30 个锚点标签的 κ；
 * 双评审 κ；位置交换对称；注水不涨分；三档排序在轮次与 3 个 bootstrap seed 下稳定。
 * 不能证明：真实厂商模型的质量/成本/延迟、人类评审一致性、跨模型自我偏好。
 *
 * T-060 追加：同一批报告再经**生产 HttpLlmClient** 发往本地 OpenAI 兼容端点回放
 * （[AssistantBackendStub]），证明没有厂商 Key 时后端接线也能完整跑通；端点不是模型，
 * 边界见 `build/model-eval/backend-scorecard.md` 与 D19。
 */
class ModelEvalSubstituteTest {

    private data class EvalItem(
        val id: String,
        val typeId: String,
        val tier: String,
        val run: Int,
        val report: String,
    )

    private data class ReplayRow(
        val item: EvalItem,
        val targetChars: Int,
        val actualChars: Int,
        val ratio: Double,
        val checked: Int,
        val hard: Int,
        val banned: Int,
        val structureIssues: Int,
        val lengthIssues: Int,
        val judge: JudgeScore,
        val elapsedMs: Long,
    )

    private class SubstituteLlm(private val report: String) : LlmClient {
        var reportCalls = 0
            private set

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            val joined = messages.joinToString("\n") { it.second }
            val text = when {
                joined.contains("\"issues\"") -> "{\"issues\":[]}"
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
    fun `forty eight substitute reports pass hard gates and calibrate the judge`() {
        val items = loadCorpus()
        assertEquals(48, items.size, "语料应为 8 类型 × 3 档 × 2 轮")
        assertEquals(8, items.map { it.typeId }.distinct().size)
        assertEquals(setOf("strong", "medium", "weak"), items.map { it.tier }.distinct().toSet())
        assertEquals(setOf(1, 2), items.map { it.run }.distinct().toSet())
        assertEquals(8, items.count { it.tier == "strong" && it.run == 1 }, "每个类型每档每轮各一份")

        val rows = items.map { item ->
            val llm = SubstituteLlm(item.report)
            val row = replay(item, llm)
            assertEquals(1, llm.reportCalls, "报告生成应只调用一次：${item.id}")
            row
        }

        // ① 真实流水线 + 数字硬闸：48/48 不许有未溯源、算式、关系、字段硬伤；套话 0。
        rows.forEach { row ->
            val detail = buildString {
                append("${row.item.id} 未过 T-052 回放：")
                append(" 硬问题=${row.hard}，套话=${row.banned}")
                append("，目标=${row.targetChars}，实际=${row.actualChars}，比=${String.format("%.2f", row.ratio)}")
            }
            assertEquals(0, row.hard, detail)
            assertEquals(0, row.banned, detail)
            if (row.item.tier == "strong" && isStructuredReportType(row.item.typeId)) {
                assertEquals(0, row.structureIssues, "$detail；strong 档的结构块必须完整")
            }
            if (row.item.tier == "strong" && !isStructuredReportType(row.item.typeId)) {
                assertEquals(0, row.lengthIssues, "$detail；strong 短体型应满足篇幅区间")
            }
        }

        // ② rubric 分档：strong→高、medium→中、weak→低（rubric 与语料设计的对照）
        val byTier = rows.groupBy { it.item.tier }
        assertEquals(setOf("高"), byTier.getValue("strong").map { it.judge.grade }.toSet())
        assertEquals(setOf("中"), byTier.getValue("medium").map { it.judge.grade }.toSet())
        assertEquals(setOf("低"), byTier.getValue("weak").map { it.judge.grade }.toSet())

        // ③ 30 锚点：judge vs 评审 A；20 重叠：A vs B。
        val labelsA = loadLabels("/model-eval/labels-reviewer-a.tsv")
        val labelsB = loadLabels("/model-eval/labels-reviewer-b.tsv")
        assertEquals(30, labelsA.size, "评审 A 应覆盖 30 个锚点")
        assertEquals(20, labelsB.size, "评审 B 应覆盖 20 个重叠锚点")
        val rowsById = rows.associateBy { it.item.id }
        assertTrue(labelsA.keys.all { it in rowsById }, "锚点必须来自 48 份语料：${labelsA.keys - rowsById.keys}")
        assertTrue(labelsB.keys.all { it in labelsA.keys }, "B 的重叠集必须落在 A 的锚点内：${labelsB.keys - labelsA.keys}")

        val binA = labelsA.mapValues { toBin(it.value) }
        val judgeBins = labelsA.keys.associateWith { rowsById.getValue(it).judge.grade }
        val kappaJudge = CohenKappa.compute(
            labelsA.keys.map { binA.getValue(it) },
            labelsA.keys.map { judgeBins.getValue(it) },
        )
        val kappaReview = CohenKappa.compute(
            labelsB.keys.map { binA.getValue(it) },
            labelsB.keys.map { toBin(labelsB.getValue(it)) },
        )
        assertTrue(kappaJudge >= 0.6, "judge 与评审 A 的 κ 应 ≥0.6，实际 ${String.format("%.3f", kappaJudge)}")

        // ④ 位置交换：12 对，正反比较必须反对称；自己与自己为 0。
        var swaps = 0
        val byId = rows.associateBy { it.item.id }
        for (type in listOf("standard", "deepdive", "compare", "anomaly")) {
            for ((left, right) in listOf(
                "strong-r1" to "weak-r1",
                "medium-r1" to "weak-r1",
                "strong-r1" to "medium-r1",
            )) {
                val a = byId.getValue("$type-$left").item.report
                val b = byId.getValue("$type-$right").item.report
                val forward = ReportQualityJudge.compare(a, b, type)
                val backward = ReportQualityJudge.compare(b, a, type)
                assertEquals(-backward, forward, "位置交换应反对称：$type $left vs $right")
                assertEquals(0, ReportQualityJudge.compare(a, a, type), "自比为 0：$type $left")
                swaps += 1
            }
        }
        assertEquals(12, swaps)

        // ⑤ 长度偏置：强报告注水（追加无数字的三句）不得涨分、不得升档。
        val filler = "\n\n本报告只讨论已给出的统计口径。阅读时请以表格中的数值为准。以上内容不构成经营建议。"
        val lengthDeltas = byTier.getValue("strong").filter { it.item.run == 1 }.map { row ->
            val padded = ReportQualityJudge.score(row.item.report + filler, row.item.typeId)
            val delta = padded.total - row.judge.total
            assertTrue(delta <= 0.5, "${row.item.id} 注水后涨分 ${String.format("%.2f", delta)}")
            assertEquals(row.judge.grade, padded.grade, "${row.item.id} 注水后不应升档")
            delta
        }

        // ⑥ 排序稳定：三档均值在全量、两轮、3 个 bootstrap seed 下保持 strong > medium > weak。
        fun mean(tier: String, run: Int? = null): Double = rows
            .filter { it.item.tier == tier && (run == null || it.item.run == run) }
            .map { it.judge.total }
            .average()

        val rankAll = mapOf("strong" to mean("strong"), "medium" to mean("medium"), "weak" to mean("weak"))
        assertTrue(rankAll.getValue("strong") > rankAll.getValue("medium"), "全量 strong 应高于 medium：$rankAll")
        assertTrue(rankAll.getValue("medium") > rankAll.getValue("weak"), "全量 medium 应高于 weak：$rankAll")
        val rankByRun = (1..2).associateWith { run ->
            mapOf("strong" to mean("strong", run), "medium" to mean("medium", run), "weak" to mean("weak", run))
        }
        for ((run, rank) in rankByRun) {
            assertTrue(rank.getValue("strong") > rank.getValue("medium"), "r$run strong 应高于 medium：$rank")
            assertTrue(rank.getValue("medium") > rank.getValue("weak"), "r$run medium 应高于 weak：$rank")
        }
        val types = rows.map { it.item.typeId }.distinct().sorted()
        val bootstrap = (1..3).map { seed ->
            val random = Random(seed.toLong())
            val picked = (1..types.size).map { types[random.nextInt(types.size)] }
            val means = listOf("strong", "medium", "weak").associateWith { tier ->
                picked.flatMap { type -> rows.filter { it.item.typeId == type && it.item.tier == tier } }
                    .map { it.judge.total }
                    .average()
            }
            assertTrue(means.getValue("strong") > means.getValue("medium"), "seed $seed strong 应高于 medium：$means")
            assertTrue(means.getValue("medium") > means.getValue("weak"), "seed $seed medium 应高于 weak：$means")
            seed to means
        }

        // ⑦ 成本/延迟：延迟实测；替身无 API 计费，token 记账只作占位。
        val latencies = rows.map { it.elapsedMs }.sorted()
        val p95 = latencies[((latencies.size - 1) * 0.95).toInt()]
        val tokenLedger = rows.sumOf { (it.item.report.length / 4).toLong() }

        val scorecard = buildScorecard(
            rows = rows,
            kappaJudge = kappaJudge,
            kappaReview = kappaReview,
            labelsA = labelsA,
            labelsB = labelsB,
            rankAll = rankAll,
            rankByRun = rankByRun,
            bootstrap = bootstrap,
            lengthDeltas = lengthDeltas,
            latencyTotal = latencies.sum(),
            latencyMean = latencies.average(),
            latencyP95 = p95,
            tokenLedger = tokenLedger,
        )
        val out = File("build/model-eval/scorecard.md")
        out.parentFile.mkdirs()
        out.writeText(scorecard, Charsets.UTF_8)
        print(scorecard)
        println("T-052 scorecard written to ${out.absolutePath}")
    }

    /**
     * T-060 · 助手作为**模型后端**：生产 HttpLlmClient 真的发 OpenAI 请求，
     * 本地端点回放助手撰写的 48 份正文。
     *
     * 与上一个测试的区别：那边是进程内 `LlmClient` 直接返回字符串，绕过了 HTTP、
     * 鉴权、JSON 解析与错误分类；这里走完整的 `HttpLlmClient`（OpenAI 兼容协议、
     * Bearer、usage 回包），只把「供应商 API Key + 公网模型」替换为「本地回放端点」。
     */
    @Test
    fun `assistant backend serves 48 reports through the real OpenAI compatible client`() {
        val items = loadCorpus()
        AssistantBackendStub().use { backend ->
            val clientPrefs = FilePrefs(Files.createTempDirectory("t060-prefs-").toFile())
            clientPrefs.set(StepfunConfig.KEY_STORAGE, AssistantBackendStub.API_KEY)
            val client = HttpLlmClient(clientPrefs, baseUrlOverride = backend.baseUrl)

            val rows = items.map { item ->
                backend.serve(item.report)
                val before = backend.calls.size
                val row = replay(item, client, model = "assistant-substitute")
                val calls = backend.calls.drop(before)
                assertEquals(2, calls.size, "${item.id} 应只有正文 + 语义审查两次后端调用：$calls")
                val reportCall = calls.single { it.kind == "report" }
                val reviewCall = calls.single { it.kind == "review" }
                assertEquals("Bearer ${AssistantBackendStub.API_KEY}", reportCall.authorization)
                assertEquals("Bearer ${AssistantBackendStub.API_KEY}", reviewCall.authorization)
                assertTrue(reportCall.body.contains("\"stream\":false"), "正文应走单次通道：${item.id}")
                assertTrue(reportCall.body.contains("\"temperature\":0.7"), "正文温度应为 0.7：${item.id}")
                assertTrue(reportCall.body.contains("\"model\":\"assistant-substitute\""), "模型名应如实传递：${item.id}")
                assertTrue(reviewCall.body.contains("\"temperature\":0.2"), "审查温度应为 0.2：${item.id}")
                assertEquals(0, row.hard, "${item.id} 经 HTTP 后端回放后不应有硬伤")
                assertEquals(0, row.banned, "${item.id} 经 HTTP 后端回放后不应有套话")
                row
            }

            assertEquals(48, rows.size)
            assertEquals(96, backend.calls.size, "48 份 ×（正文 + 审查）= 96 次")
            assertEquals(48, backend.calls.count { it.kind == "report" })
            assertEquals(48, backend.calls.count { it.kind == "review" })

            val latencies = rows.map { it.elapsedMs }.sorted()
            val p95 = latencies[((latencies.size - 1) * 0.95).toInt()]
            val tokenLedger = backend.calls.sumOf { it.totalTokens.toLong() }
            val scorecard = buildBackendScorecard(
                rows = rows,
                calls = backend.calls.size,
                reportCalls = backend.calls.count { it.kind == "report" },
                reviewCalls = backend.calls.count { it.kind == "review" },
                tokenLedger = tokenLedger,
                latencyTotal = latencies.sum(),
                latencyMean = latencies.average(),
                latencyP95 = p95,
            )
            val out = File("build/model-eval/backend-scorecard.md")
            out.parentFile.mkdirs()
            out.writeText(scorecard, Charsets.UTF_8)
            print(scorecard)
            println("T-060 backend scorecard written to ${out.absolutePath}")
        }
    }

    /** 负控：助手后端不绕过生产鉴权——缺 Key 客户端拒绝发送，错 Key 端点 401 */
    @Test
    fun `no API key means the real client never reaches the assistant backend`() {
        AssistantBackendStub().use { backend ->
            val prefs = FilePrefs(Files.createTempDirectory("t060-nokey-").toFile())
            val client = HttpLlmClient(prefs, baseUrlOverride = backend.baseUrl)
            val error = runCatching {
                client.chat("assistant-substitute", listOf("user" to "你好"), 0.2, 100)
            }.exceptionOrNull()
            assertTrue(error is StepError, "应抛 StepError，实际 $error")
            assertEquals(STEP_ERR_NO_KEY, (error as StepError).code)
            assertTrue(error.message!!.contains("还没有配置"), "错误提示应指向缺少 Key：${error.message}")
            assertEquals(0, backend.calls.size, "没有 Key 时不应有任何请求到达后端")

            val wrongPrefs = FilePrefs(Files.createTempDirectory("t060-wrongkey-").toFile())
            wrongPrefs.set(StepfunConfig.KEY_STORAGE, "sk-wrong")
            val wrongClient = HttpLlmClient(wrongPrefs, baseUrlOverride = backend.baseUrl)
            val authError = runCatching {
                wrongClient.chat("assistant-substitute", listOf("user" to "你好"), 0.2, 100)
            }.exceptionOrNull()
            assertTrue(authError is StepError, "错 Key 应抛 StepError，实际 $authError")
            assertEquals(STEP_ERR_AUTH, (authError as StepError).code, "端点必须真的校验 Bearer")
            assertEquals(0, backend.calls.size, "被 401 拒绝的请求不进入回放记录")
        }
    }

    private fun replay(item: EvalItem, llm: LlmClient, model: String = "model-substitute"): ReplayRow {
        val sample = ModelSubstituteSamples.all.firstOrNull { it.typeId == item.typeId }
            ?: error("未知报告类型：${item.typeId}")
        val home = Files.createTempDirectory("t052-${item.id}-").toFile()
        val prefs = FilePrefs(home)
        ReportLib.setReportType(prefs, sample.typeId)
        ReportLib.setReportStyle(prefs, sample.styleId)
        ReportLib.setIndustry(prefs, sample.industryId)

        val dataset = sample.dataset()
        val brief = buildDatasetBrief(dataset, prefs)
        val started = System.nanoTime()
        val result = InsightOrchestrator(llm, prefs, model = model).generateInsight(dataset)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(item.report, result.report, "流水线应把替身回包原样作为报告：${item.id}")
        assertEquals("classic", result.engineId)
        assertFalse(result.truncated, "替身回放不应截断：${item.id}")

        val verification = verifyReport(result.report, brief)
        val structured = isStructuredReportType(item.typeId)
        val audit = WritingAudit.audit(result.report, result.targetChars, structured)
        val ratio = audit.actualChars.toDouble() / result.targetChars
        return ReplayRow(
            item = item,
            targetChars = result.targetChars,
            actualChars = audit.actualChars,
            ratio = ratio,
            checked = verification.checked,
            hard = countHardIssues(verification),
            banned = audit.bannedHits,
            structureIssues = audit.issues.count { it.kind == "缺结构块" },
            lengthIssues = audit.issues.count { it.kind == "篇幅" },
            judge = ReportQualityJudge.score(item.report, item.typeId),
            elapsedMs = elapsedMs,
        )
    }

    private fun loadCorpus(): List<EvalItem> {
        val text = resource("/model-eval/corpus.md")
        val items = mutableListOf<EvalItem>()
        var current: EvalItem? = null
        val body = StringBuilder()
        fun flush() {
            current?.let {
                items += it.copy(report = body.toString().trim())
            }
            body.setLength(0)
        }
        for (line in text.lines()) {
            val header = HEADER.matchEntire(line)
            if (header != null) {
                flush()
                current = EvalItem(
                    id = header.groupValues[1],
                    typeId = header.groupValues[2],
                    tier = header.groupValues[3],
                    run = header.groupValues[4].toInt(),
                    report = "",
                )
            } else if (current != null) {
                body.append(line).append('\n')
            }
        }
        flush()
        return items
    }

    private fun loadLabels(path: String): Map<String, Int> {
        val lines = resource(path).removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "标签文件不能为空：$path")
        assertTrue(lines.first().startsWith("id"), "标签文件需要表头：$path")
        val labels = linkedMapOf<String, Int>()
        for (line in lines.drop(1)) {
            val parts = line.split('\t')
            if (parts.size < 2) error("标签行格式错误：$line")
            val id = parts[0].trim()
            val label = parts[1].trim().toIntOrNull() ?: error("标签不是整数：$line")
            assertTrue(label in 1..5, "标签必须在 1–5：$line")
            labels[id] = label
        }
        return labels
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: error("缺少资源：$path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun toBin(label: Int): String = when (label) {
        5 -> "高"
        3, 4 -> "中"
        1, 2 -> "低"
        else -> error("标签越界：$label")
    }

    /**
     * T-060 后端评分卡：只记录「生产 HTTP 客户端 + 本地回放端点」这条链路的证据，
     * 不重复 T-052 的 κ / 排序统计（那些结论仍归 D18，不因换传输层而增强）。
     */
    private fun buildBackendScorecard(
        rows: List<ReplayRow>,
        calls: Int,
        reportCalls: Int,
        reviewCalls: Int,
        tokenLedger: Long,
        latencyTotal: Long,
        latencyMean: Double,
        latencyP95: Long,
    ): String {
        val sb = StringBuilder()
        sb.append("T-060 助手作为模型后端评分卡\n\n")
        sb.append("条件：48 份报告（8 类型 × 3 档 × 2 轮）由助手撰写（T-052 冻结语料），\n")
        sb.append("经**生产 HttpLlmClient** 发往本地 OpenAI 兼容端点（127.0.0.1，仅回环）回放。\n")
        sb.append("真实条件（供应商 API Key、公网模型、成本/限流/拒答）仍不可用；本卡证明的是\n")
        sb.append("「项目后端接线可以在无厂商 Key 时完整跑通」，不是「某个真实模型的质量」。\n\n")

        sb.append("## 逐项\n\n")
        sb.append("| id | 类型 | 档 | 轮 | 字 | 硬伤 | 套话 | 总分 | 档位 | ms |\n")
        sb.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | --- | ---: |\n")
        for (row in rows) {
            sb.append("| ${row.item.id} | ${row.item.typeId} | ${row.item.tier} | ${row.item.run} | ")
            sb.append("${row.actualChars} | ${row.hard} | ${row.banned} | ${String.format("%.1f", row.judge.total)} | ")
            sb.append("${row.judge.grade} | ${row.elapsedMs} |\n")
        }

        sb.append("\n## 后端调用\n\n")
        sb.append("- 请求 $calls 次：正文 $reportCalls 次 + 语义审查 $reviewCalls 次；全部命中 127.0.0.1 回放端点。\n")
        sb.append("- 请求形态：`stream:false`、正文 `temperature:0.7` / 审查 `temperature:0.2`、")
        sb.append("`model:assistant-substitute`；响应逐条解析 `choices[0].message.content` 与 `usage.total_tokens`。\n")
        sb.append("- 鉴权：每次请求都带 `Authorization: Bearer <占位 Key>`；无 Key 负控中生产客户端抛 `no-key`，端点收到 0 次请求；")
        sb.append("错 Key 负控中端点返回 401，客户端映射为 `auth` 错误。\n")
        sb.append("- token 记账（服务端 usage 口径，占位）：$tokenLedger。该数含每次请求的完整 prompt（brief 全文）与回包，")
        sb.append("不是正文长度÷4；与 D18 的 3,254 不同口径，不可直接比较。\n")

        sb.append("\n## 延迟\n\n")
        sb.append("- 回放总 ${latencyTotal} ms，均值 ${String.format("%.1f", latencyMean)} ms，P95 ${latencyP95} ms。\n")
        sb.append("- 含 brief 构建、两道门禁与 HTTP 回环往返；**不含公网**，不能当作厂商模型延迟。\n")

        sb.append("\n## 诚实边界\n\n")
        sb.append("- 端点不是模型：它原样回放助手撰写的冻结语料，运行时没有生成新内容；")
        sb.append("不能证明供应商模型的质量、成本、限流或拒答率。\n")
        sb.append("- 48 份正文是 T-052 语料，不是本轮现写；质量与 κ 结论沿用 D18，不因改走 HTTP 而增强。\n")
        sb.append("- 语义审查固定回 `{\"issues\":[]}`，与 T-052 进程内替身同口径，不代表真实模型的审校能力。\n")
        sb.append("- 真实 API Key、供应商模型、人类双标注仍是外部阻塞；D17 §8.1 第 4 项维持「部分」。\n")
        return sb.toString()
    }

    private fun buildScorecard(
        rows: List<ReplayRow>,
        kappaJudge: Double,
        kappaReview: Double,
        labelsA: Map<String, Int>,
        labelsB: Map<String, Int>,
        rankAll: Map<String, Double>,
        rankByRun: Map<Int, Map<String, Double>>,
        bootstrap: List<Pair<Int, Map<String, Double>>>,
        lengthDeltas: List<Double>,
        latencyTotal: Long,
        latencyMean: Double,
        latencyP95: Long,
        tokenLedger: Long,
    ): String {
        val sb = StringBuilder()
        sb.append("T-052 模型替身评测评分卡\n\n")
        sb.append("条件：8 报告类型 × 3 质量档（strong / medium / weak）× 2 轮（r1 / r2）＝ 48 份。\n")
        sb.append("真实模型条件：**不可用**（无第三方 Key）。三档是同一后端模型的替身撰写，不是三个厂商模型；")
        sb.append("两轮是两次独立撰写，不是温度参数；评审 A/B 是同族模型的独立上下文，不是两名人类评审。\n")
        sb.append("盲评：评审包用匿名编号（A 为 P01–P30、B 为 Q01–Q20）并固定种子随机排序，只给报告文本与类型；")
        sb.append("标签在语料定稿后采集（首轮评审因编号泄露档位已作废）。\n")
        sb.append("数据：host/src/test/resources/model-eval/corpus.md；锚点：labels-reviewer-a/b.tsv；")
        sb.append("流水线：真实 InsightOrchestrator + verifyReport + WritingAudit。\n\n")

        sb.append("## 逐项\n\n")
        sb.append("| id | 类型 | 档 | 轮 | 目标字 | 实际字 | 篇幅比 | 核对数字 | 硬伤 | 套话 | 结构块缺 | 证据条数 | 结构分 | 证据分 | 可读分 | 结论分 | 总分 | 档位 | ms |\n")
        sb.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |\n")
        for (row in rows) {
            sb.append("| ${row.item.id} | ${row.item.typeId} | ${row.item.tier} | ${row.item.run} | ${row.targetChars} | ")
            sb.append("${row.actualChars} | ${String.format("%.2f", row.ratio)} | ${row.checked} | ${row.hard} | ${row.banned} | ")
            sb.append("${row.structureIssues} | ${row.judge.evidenceTokens} | ${String.format("%.1f", row.judge.structure)} | ")
            sb.append("${String.format("%.1f", row.judge.evidence)} | ${String.format("%.1f", row.judge.readability)} | ")
            sb.append("${String.format("%.1f", row.judge.conclusion)} | ${String.format("%.1f", row.judge.total)} | ")
            sb.append("${row.judge.grade} | ${row.elapsedMs} |\n")
        }

        sb.append("\n## 三档均值\n\n")
        sb.append("| 视角 | strong | medium | weak |\n| --- | ---: | ---: | ---: |\n")
        sb.append("| 全量 | ${String.format("%.2f", rankAll.getValue("strong"))} | ${String.format("%.2f", rankAll.getValue("medium"))} | ${String.format("%.2f", rankAll.getValue("weak"))} |\n")
        for ((run, rank) in rankByRun) {
            sb.append("| r$run | ${String.format("%.2f", rank.getValue("strong"))} | ${String.format("%.2f", rank.getValue("medium"))} | ${String.format("%.2f", rank.getValue("weak"))} |\n")
        }
        for ((seed, rank) in bootstrap) {
            sb.append("| bootstrap seed $seed | ${String.format("%.2f", rank.getValue("strong"))} | ${String.format("%.2f", rank.getValue("medium"))} | ${String.format("%.2f", rank.getValue("weak"))} |\n")
        }

        sb.append("\n## 一致性\n\n")
        sb.append("- judge vs 评审 A（30 锚点，三档）：κ = ${String.format("%.3f", kappaJudge)}\n")
        sb.append("- 评审 A vs 评审 B（20 重叠，三档）：κ = ${String.format("%.3f", kappaReview)}\n")
        sb.append("- 标签分布 A：${labelsA.values.groupingBy { it }.eachCount().toSortedMap()}\n")
        sb.append("- 标签分布 B：${labelsB.values.groupingBy { it }.eachCount().toSortedMap()}\n")
        sb.append("- 分箱口径：1–2 → 低，3–4 → 中，5 → 高（与评审说明的 5=可交付、4=好但有瑕疵、3=合格、2=勉强、1=不合格一致）\n")

        sb.append("\n## 偏置与稳定性\n\n")
        sb.append("- 位置交换：12 对全部反对称（A vs B 与 B vs A 互为相反数，自比为 0）。\n")
        sb.append("- 长度偏置：8 份强报告追加无数字填充后，最大涨分 ${String.format("%.2f", lengthDeltas.maxOrNull() ?: 0.0)}，")
        sb.append("平均涨分 ${String.format("%.2f", lengthDeltas.average())}；档位不变。rubric 不含长度项，注水不带来收益。\n")
        sb.append("- 排序稳定：全量、r1、r2、3 个 bootstrap seed 下均为 strong > medium > weak。\n")
        sb.append("- 自我偏好：judge 不接收模型身份，三档由同一替身撰写，**跨模型自我偏好无法测**；此处只证明评分接口没有身份轴。\n")

        sb.append("\n## 成本与延迟\n\n")
        sb.append("- 成本：0（替身无 API 计费，不能代表真实模型单价）；token 记账（报告字数÷4，占位）= $tokenLedger。\n")
        sb.append("- 回放延迟：总 ${latencyTotal} ms，均值 ${String.format("%.1f", latencyMean)} ms，P95 ${latencyP95} ms（含 brief 构建与两道门禁，不含网络）。\n")

        sb.append("\n## 诚实边界\n\n")
        sb.append("- 3 档 ≠ 3 个厂商模型；2 轮 ≠ 2 个温度；评审 A/B ≠ 人类评审；judge 是确定性 rubric，不是 LLM-as-judge。\n")
        sb.append("- 48 份统一为短稿（约 40–660 字），结构化类型的篇幅比为 0.01–0.17（目标 2,626–5,794 字），")
        sb.append("篇幅门禁只记录不参与评分；strong 档的强只体现在结构/证据/结论，不代表满足真实目标篇幅。\n")
        sb.append("- rubric 与语料由同一个替身设计，30 个锚点也来自同族评审，κ 只能证明「规则与自评一致」，不能外推为人类一致性。\n")
        sb.append("- 真实模型质量、拒答率、token 成本、跨模型偏好仍待真实 Key（T-037 的边界继续有效）。\n")
        return sb.toString()
    }

    private companion object {
        val HEADER = Regex("""^@@ id=(\S+) type=(\S+) tier=(\S+) run=(\d+)$""")
    }
}
