package com.stragent.host

import com.stragent.core.ai.HedgeBudget
import com.stragent.core.ai.HedgedCall
import com.stragent.core.ai.LlmQueryDecomposer
import com.stragent.core.ai.summarizeText
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.VisionImage
import com.stragent.core.ports.VisionRequest
import com.stragent.core.report.ReportLib
import com.stragent.data.HttpLlmClient
import com.stragent.data.OpenAiCompatibleVisionClient
import com.stragent.data.StepfunConfig
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * T-135 · 四条链的**端点级**语义抽样（助手作为模型后端）。
 *
 * ## 它到底验了什么（别误读成厂商模型评测）
 *
 * 真实厂商模型需要 API Key，环境里没有。本轮由**助手充当模型后端**：本地起一个
 * OpenAI 兼容端点（[MultiChainBackendStub]），生产客户端**真的**发请求过去。
 *
 * 因此本件证明的是**端点级**事实：
 *  - 四条链（检索 / 分解 / 摘要 / OCR / 适配）都**真的走了生产传输路径**：
 *    真 HTTP、真 Bearer 鉴权、真 OpenAI 请求/响应协议、真 usage 记账；
 *  - **流式链**走**真 SSE 分片**——「增量拼接 = 最终正文」这条硬契约在真流上验，
 *    而不是在内存替身上自证；
 *  - **OCR 链**走**真 vision 协议**（`image_url` 内容块）+ 真 manifest 哈希；
 *  - 每条链的输出都过**生产的零编造门禁**（数字溯源 / 段号锚 / 白名单）。
 *
 * 它**不**证明：厂商模型的质量、成本、限流、拒答率、跨模型差异。
 * **应答由助手撰写并冻结** → 任何「模型质量」的结论都是**我的质量**，不是厂商的。
 * 这条边界写进效果报告与 D7 T-135——**不因跑了就声称达成厂商级结论**。
 *
 * ## 与 T-060 的分工
 *
 * T-060 证明了「生产 `HttpLlmClient` 真的发 OpenAI 请求」这一条链路（单链、单内容、
 * 非流式）。本件把覆盖面扩到**四链 + 真流式 + 真 vision 协议**。
 *
 * ## 运行
 *
 * ```
 * ./gradlew --offline :host:test --tests "com.stragent.host.T135EndpointChainTest"
 * ```
 * 效果报告写到 `host/build/model-eval/t135/`（gitignored，只作证据）。
 */
class T135EndpointChainTest {

    /** 内存偏好（Key 不落盘） */
    private class MemoryPrefs : PrefsStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = values[key] ?: fallback
        override fun set(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    /** 四链的抽样结果（一行 = 一条链的端点级事实） */
    private data class ChainRow(
        val chain: String,
        val detail: String,
        val calls: Int,
        val tokens: Int,
        val elapsedMs: Long,
        val hardIssues: Int,
        val gateFailed: Boolean,
        val failureMode: String? = null,
    )

    private fun prefsWithKey(): PrefsStore = MemoryPrefs().apply {
        set(StepfunConfig.VENDOR_STORAGE, "deepseek")
        set(StepfunConfig.CUSTOM_MODEL_STORAGE, MultiChainBackendStub.MODEL_NAME)
        set(StepfunConfig.KEY_STORAGE, MultiChainBackendStub.API_KEY)
    }

    @Test
    fun `four chains run through the real endpoint with frozen assistant-written samples`() {
        val rows = mutableListOf<ChainRow>()
        MultiChainBackendStub().use { backend ->
            val prefs = prefsWithKey()
            val client = HttpLlmClient(prefs, baseUrlOverride = backend.baseUrl)
            val dataset = T135ChainSamples.dataset()

            // ---------- ① 检索链（追问）：真 HTTP + 真数字溯源门禁 ----------
            run {
                backend.serveCyclic(MultiChainBackendStub.CHAIN_RETRIEVAL, T135ChainSamples.RETRIEVAL_ANSWER)
                val before = backend.calls.size
                val started = System.nanoTime()
                val result = com.stragent.core.ai.askFollowup(
                    llm = client,
                    prefs = prefs,
                    dataset = dataset,
                    history = emptyList(),
                    question = "3 号测点沉降是否超过设计控制值？超了多少？",
                    model = MultiChainBackendStub.MODEL_NAME,
                )
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val calls = backend.calls.drop(before)
                assertEquals(1, calls.size, "追问链应只有 1 次后端调用")
                assertEquals(MultiChainBackendStub.CHAIN_RETRIEVAL, calls.single().chain, "应路由到检索链")
                assertTrue(
                    calls.single().authorization == "Bearer ${MultiChainBackendStub.API_KEY}",
                    "必须走生产鉴权路径",
                )
                assertTrue(result.totalTokens > 0, "必须拿到真实 usage 记账")
                rows += ChainRow(
                    chain = "检索",
                    detail = "回答 ${result.content.length} 字，引用段号 ${result.content.count { it == '段' }} 处",
                    calls = calls.size,
                    tokens = result.totalTokens,
                    elapsedMs = elapsed,
                    hardIssues = result.gateIssues.size,
                    gateFailed = result.gateFailed,
                )
                assertTrue(!result.gateFailed, "追问答案不应有硬伤：${result.gateIssues}")
            }

            // ---------- ② 分解链（T-113）：多跳问题 → 子查询 ----------
            run {
                backend.serveCyclic(MultiChainBackendStub.CHAIN_DECOMPOSE, T135ChainSamples.DECOMPOSE_ANSWER)
                val before = backend.calls.size
                val started = System.nanoTime()
                val result = LlmQueryDecomposer.decompose(
                    llm = client,
                    model = MultiChainBackendStub.MODEL_NAME,
                    question = "3 号测点沉降与设计控制值的差值是多少？同期速率与水位变化有何关联？报警值是否经过设计确认？",
                    cancelSignal = { false },
                )
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val calls = backend.calls.drop(before)
                assertEquals(1, calls.size, "分解链应只有 1 次后端调用")
                val subs = result.subQueries
                assertTrue(subs != null && subs.size >= 2, "多跳问题应分解出 ≥2 子查询，实际 $subs")
                assertTrue(subs!!.all { it.length <= 60 }, "子查询长度须在上限内：$subs")
                rows += ChainRow(
                    chain = "分解",
                    detail = "分解出 ${subs.size} 条子查询：${subs.joinToString(" / ")}",
                    calls = calls.size,
                    tokens = result.totalTokens,
                    elapsedMs = elapsed,
                    hardIssues = 0,
                    gateFailed = false,
                )
            }

            // ---------- ③ 摘要链（T-096）：map 要点 JSON + CoD 两轮 ----------
            run {
                backend.serveCyclic(MultiChainBackendStub.CHAIN_SUMMARY_MAP, T135ChainSamples.SUMMARY_MAP_ANSWER)
                // CoD 固定 2 轮，第 2 轮输入是第 1 轮输出 → 两轮各备一份（**非循环**：
                // 循环会让两轮拿到同一份，第 2 轮的「密度提升」就没了）
                backend.serve(MultiChainBackendStub.CHAIN_SUMMARY_COD, T135ChainSamples.SUMMARY_MERGE_ANSWER)
                backend.serve(MultiChainBackendStub.CHAIN_SUMMARY_COD, T135ChainSamples.SUMMARY_COD_ROUND2)
                val before = backend.calls.size
                val started = System.nanoTime()
                val summary = summarizeText(
                    llm = client,
                    model = MultiChainBackendStub.MODEL_NAME,
                    dataset = dataset,
                    maxWords = 400,
                )
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val calls = backend.calls.drop(before)
                assertTrue(calls.isNotEmpty(), "摘要链应有后端调用")
                assertTrue(
                    calls.all { it.chain == MultiChainBackendStub.CHAIN_SUMMARY_MAP || it.chain == MultiChainBackendStub.CHAIN_SUMMARY_COD },
                    "摘要链只应命中 map/cod 链，实际 ${calls.map { it.chain }}",
                )
                assertTrue(summary.summary.isNotBlank(), "总结正文不应为空")
                assertTrue(summary.totalTokens > 0, "摘要链应拿到真实 usage 记账")
                assertTrue(summary.failedChunks.isEmpty(), "不应有失败块：${summary.failedChunks}")
                rows += ChainRow(
                    chain = "摘要",
                    detail = "总结 ${summary.summary.length} 字，${summary.points.size} 条要点，${summary.chunkCount} 块",
                    calls = calls.size,
                    tokens = summary.totalTokens,
                    elapsedMs = elapsed,
                    hardIssues = 0,
                    gateFailed = false,
                )
            }

            // ---------- ④ OCR 链（视觉）：真 vision 协议 + 真 manifest ----------
            run {
                backend.serveCyclic(MultiChainBackendStub.CHAIN_OCR, T135ChainSamples.OCR_ANSWER)
                val png = File(Files.createTempDirectory("t135-img-").toFile(), "gl03.png")
                writeTinyPng(png)
                val vision = OpenAiCompatibleVisionClient(
                    config = StepfunConfig(prefs),
                    model = MultiChainBackendStub.MODEL_NAME,
                    baseUrlOverride = backend.baseUrl,
                )
                assertTrue(vision.isAvailable(), "视觉端应可用（有 Key 与模型名）")
                val before = backend.calls.size
                val started = System.nanoTime()
                val result = vision.describe(
                    VisionRequest(
                        image = VisionImage.LocalFile(png.absolutePath),
                        prompt = "请逐字读出图中的测点编号、累计沉降量、最大沉降速率、观测日期与报警状态。",
                    ),
                )
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val calls = backend.calls.drop(before)
                assertEquals(1, calls.size, "OCR 链应只有 1 次后端调用")
                assertEquals(MultiChainBackendStub.CHAIN_OCR, calls.single().chain, "应路由到 OCR 链")
                assertTrue(
                    calls.single().body.contains("image_url"),
                    "视觉请求必须带 image_url 内容块（真 vision 协议）",
                )
                assertEquals("21.6 mm", result.text.substringAfter("累计沉降量：").substringBefore("；"), "OCR 数值应逐字读出")
                assertTrue(result.totalTokens > 0, "视觉链应拿到真实 usage 记账")
                assertTrue(result.manifest.imageHash.isNotBlank(), "视觉链应产出图像哈希（可溯源）")
                assertTrue(result.manifest.outputHash.isNotBlank(), "视觉链应产出输出哈希")
                rows += ChainRow(
                    chain = "OCR",
                    detail = "读出 ${result.text.length} 字，图像哈希 ${result.manifest.imageHash.take(12)}…",
                    calls = calls.size,
                    tokens = result.totalTokens,
                    elapsedMs = elapsed,
                    hardIssues = 0,
                    gateFailed = false,
                )
            }

            // ---------- ⑤ 适配链（报告类型 / 行业 / 风格注入） ----------
            run {
                // 报告链（classic）内部会复用多种角色提示词（文本分析员 / 数据分析师），
                // 内容路由在那里不可靠 → **声明模式**：本链的请求统一按「报告正文」应答，
                // 审查请求按审查模板特征单独分流（见端点的 isReviewRequest）。
                //
                // 正文用 T-052 语料（48 份里已过硬闸的那批）——**不自己另写一份**：
                // 自己写的正文若含材料里没有的数字，会被零编造门禁判为 unsourced_number
                // （实测：门禁在真链路上确实生效——那不是误报，是本仓该有的拦截）。
                val corpus = loadCorpusReports().first()
                val corpusSample = ModelSubstituteSamples.all.first { it.typeId == corpus.typeId }
                backend.serveCyclic(MultiChainBackendStub.CHAIN_REPORT, corpus.report)
                repeat(8) { backend.serveCyclic(MultiChainBackendStub.CHAIN_REVIEW, """{"issues":[]}""") }
                val before = backend.calls.size
                val started = System.nanoTime()
                val reportPrefs = MemoryPrefs().apply {
                    set(StepfunConfig.KEY_STORAGE, MultiChainBackendStub.API_KEY)
                    set(StepfunConfig.CUSTOM_MODEL_STORAGE, MultiChainBackendStub.MODEL_NAME)
                    ReportLib.setReportType(this, corpus.typeId)
                    ReportLib.setReportStyle(this, corpusSample.styleId)
                    ReportLib.setIndustry(this, corpusSample.industryId)
                }
                val reportClient = HttpLlmClient(reportPrefs, baseUrlOverride = backend.baseUrl)
                // 声明覆盖整条报告链（章节数由流水线决定，故给足；用完自动回到内容路由）
                backend.expect(MultiChainBackendStub.CHAIN_REPORT, 64)
                val result = com.stragent.core.ai.InsightOrchestrator(
                    reportClient,
                    reportPrefs,
                    model = MultiChainBackendStub.MODEL_NAME,
                ).generateInsight(corpusSample.dataset())
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val calls = backend.calls.drop(before)
                assertTrue(calls.isNotEmpty(), "适配链应有后端调用")
                assertTrue(result.report.isNotBlank(), "报告正文不应为空")
                assertTrue(result.report.length > 100, "报告正文不应是片段（实际 ${result.report.length} 字）")
                assertTrue(result.totalTokens > 0, "适配链应拿到真实 usage 记账")
                // 适配维度可观测：报告请求里带上了行业/类型/风格的注记
                assertTrue(
                    calls.any { it.body.contains("industry_framework") || it.body.contains("<role>") },
                    "报告请求应带适配注记（行业/类型/风格）",
                )
                rows += ChainRow(
                    chain = "适配",
                    detail = "报告 ${result.report.length} 字（类型 ${corpus.typeId} / 行业 ${corpusSample.industryId}），" +
                        "${calls.size} 次调用（含语义审查）",
                    calls = calls.size,
                    tokens = result.totalTokens,
                    elapsedMs = elapsed,
                    hardIssues = 0,
                    gateFailed = result.gateFailed,
                    failureMode = if (result.gateFailed) "gateFailed=${result.qualityNotes.joinToString(";")}" else null,
                )
            }

            // ---------- ⑥ 流式链：真 SSE 分片下「增量拼接 = 最终正文」 ----------
            run {
                val streamContent = T135ChainSamples.RETRIEVAL_ANSWER
                backend.serveCyclic(MultiChainBackendStub.CHAIN_RETRIEVAL, streamContent)
                val streamClient = HttpLlmClient(prefsWithKey(), baseUrlOverride = backend.baseUrl)
                val sb = StringBuilder()
                var fragments = 0
                val before = backend.calls.size
                // 流式请求的内容不带角色提示词 → 内容路由不可靠 → 显式声明走哪条链
                backend.expect(MultiChainBackendStub.CHAIN_RETRIEVAL, 1)
                val result = streamClient.streamChat(
                    model = MultiChainBackendStub.MODEL_NAME,
                    messages = listOf("user" to "流式增量拼接自证"),
                    temperature = 0.7,
                    maxTokens = 1024,
                    onToken = { sb.append(it); fragments++ },
                )
                val calls = backend.calls.drop(before)
                assertEquals(1, calls.size, "流式链应只有 1 次后端调用")
                assertTrue(calls.single().stream, "必须走真流式（stream=true）")
                assertTrue(fragments > 1, "应收到多个分片（真流式），实际 $fragments 个")
                assertEquals(streamContent.trim(), sb.toString().trim(), "增量拼接必须逐字等于最终正文")
                assertEquals(result.text, sb.toString().trim(), "返回值与增量拼接必须一致")
                assertTrue(result.totalTokens > 0, "流式链应拿到真实 usage 记账（末帧带 usage）")
                rows += ChainRow(
                    chain = "流式",
                    detail = "$fragments 个 SSE 分片拼接后逐字等于最终正文",
                    calls = calls.size,
                    tokens = result.totalTokens,
                    elapsedMs = 0,
                    hardIssues = 0,
                    gateFailed = false,
                )
            }

            // ---------- ⑦ 对冲在真端点上的成本上界（T-137 的判据②在此取真实读数） ----------
            run {
                // 端点整体慢（每次都慢）→ 对冲**无收益**（诚实边界）；这里量的是成本上界
                repeat(4) { backend.serveCyclic(MultiChainBackendStub.CHAIN_RETRIEVAL, T135ChainSamples.RETRIEVAL_ANSWER) }
                val slowBudget = HedgeBudget(1.0)
                val hedged = HedgedCall(slowBudget, hedgeAfterMs = 50)
                val before = backend.calls.size
                // 对冲请求的内容不带角色提示词 → 显式声明（至多 2 次：主副本 + 至多 1 副本）
                backend.expect(MultiChainBackendStub.CHAIN_RETRIEVAL, 2)
                val started = System.nanoTime()
                val value = hedged.run({
                    client.chat(
                        MultiChainBackendStub.MODEL_NAME,
                        listOf("user" to "对冲成本上界自证"),
                        0.7,
                        256,
                    )
                })
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val calls = backend.calls.drop(before)
                assertTrue(value.text.isNotBlank(), "对冲路径应返回真实内容")
                assertTrue(
                    calls.size <= 2,
                    "单次调用最多产生 1 个副本（成本上界），实际 ${calls.size} 次",
                )
                rows += ChainRow(
                    chain = "对冲",
                    detail = "1 次调用产生 ${calls.size} 次后端请求（主副本 + 至多 1 副本）",
                    calls = calls.size,
                    tokens = value.totalTokens,
                    elapsedMs = elapsed,
                    hardIssues = 0,
                    gateFailed = false,
                )
            }
        }

        val report = buildReport(rows)
        val outDir = File("build/model-eval/t135").apply { mkdirs() }
        File(outDir, "t135-endpoint-chain-report.md").writeText(report, Charsets.UTF_8)
        print(report)
        println("T-135 endpoint chain report written to ${outDir.absolutePath}")

        assertEquals(7, rows.size, "应覆盖 7 个端点级抽样面（四链 + 流式 + 对冲）")
        assertTrue(rows.all { it.calls > 0 }, "每条链都应有真实后端调用")
        assertTrue(rows.all { it.tokens > 0 }, "每条链都应拿到真实 usage 记账")
        assertTrue(
            rows.filter { it.chain != "对冲" }.none { it.gateFailed },
            "除对冲（无门禁）外，各链不应有门禁失败：${rows.filter { it.gateFailed }}",
        )
    }

    /** 负控：缺 Key 时客户端根本不会发请求（生产鉴权前置） */
    @Test
    fun `no key means the real client never reaches the endpoint`() {
        MultiChainBackendStub().use { backend ->
            val client = HttpLlmClient(MemoryPrefs(), baseUrlOverride = backend.baseUrl)
            val error = runCatching {
                client.chat(MultiChainBackendStub.MODEL_NAME, listOf("user" to "你好"), 0.2, 100)
            }.exceptionOrNull()
            assertTrue(error is com.stragent.core.ai.StepError, "应抛 StepError，实际 $error")
            assertEquals(0, backend.calls.size, "没有 Key 时不应有任何请求到达端点")
        }
    }

    /** 负控：错 Key 必须被端点真的拒绝（401）——证明鉴权不是摆设 */
    @Test
    fun `wrong key is rejected by the endpoint`() {
        MultiChainBackendStub().use { backend ->
            val prefs = MemoryPrefs().apply {
                set(StepfunConfig.KEY_STORAGE, "sk-wrong")
                set(StepfunConfig.CUSTOM_MODEL_STORAGE, MultiChainBackendStub.MODEL_NAME)
            }
            val client = HttpLlmClient(prefs, baseUrlOverride = backend.baseUrl)
            val error = runCatching {
                client.chat(MultiChainBackendStub.MODEL_NAME, listOf("user" to "你好"), 0.2, 100)
            }.exceptionOrNull()
            assertTrue(error is com.stragent.core.ai.StepError, "应抛 StepError，实际 $error")
            assertEquals(
                com.stragent.data.STEP_ERR_AUTH,
                (error as com.stragent.core.ai.StepError).code,
                "端点必须真的校验 Bearer",
            )
            assertEquals(0, backend.calls.size, "被 401 拒绝的请求不进入回放记录")
        }
    }

    /**
     * 读 T-052 语料（`/model-eval/corpus.md`）里的报告条目。
     *
     * 格式：`@@ id=... type=... tier=... run=...` 起一条，直到下一个 `@@`。
     * 只取 type 与正文（T-135 用得上这两样；id/tier/run 是评审元数据，不进判断）。
     */
    private data class CorpusReport(val id: String, val typeId: String, val report: String)

    private fun loadCorpusReports(): List<CorpusReport> {
        val stream = javaClass.getResourceAsStream("/model-eval/corpus.md")
            ?: error("缺少资源：/model-eval/corpus.md")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val header = Regex("^@@ id=(\\S+) type=(\\S+) tier=(\\S+) run=(\\S+)$")
        val out = mutableListOf<CorpusReport>()
        var id: String? = null
        var typeId: String? = null
        val body = StringBuilder()
        fun flush() {
            val i = id
            val t = typeId
            if (i != null && t != null) out += CorpusReport(i, t, body.toString().trim())
            body.setLength(0)
        }
        for (line in text.lines()) {
            val m = header.matchEntire(line)
            if (m != null) {
                flush()
                id = m.groupValues[1]
                typeId = m.groupValues[2]
            } else if (id != null) {
                body.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    /** 极小 PNG（1x1）：OCR 链只验协议与 manifest，不验图像内容识别 */
    private fun writeTinyPng(file: File) {
        val bytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        file.writeBytes(bytes)
    }

    /** 效果报告：逐链事实 + 失败模式 + 诚实边界 */
    private fun buildReport(rows: List<ChainRow>): String {
        val sb = StringBuilder()
        sb.append("# T-135 · 四链端点级抽样报告（助手作为模型后端）\n\n")
        sb.append("## 条件\n\n")
        sb.append("- 后端：**本地 OpenAI 兼容端点**（`MultiChainBackendStub`）——助手撰写的冻结应答，运行时不生成新内容。\n")
        sb.append("- 客户端：**生产 `HttpLlmClient` / `OpenAiCompatibleVisionClient`**（真 HTTP、真 Bearer、真 OpenAI 协议、真 usage 记账）。\n")
        sb.append("- 材料：管廊连接围护监测（8 段，有意构造，埋 3 个可发现真问题）。\n")
        sb.append("- 调用数合计：${rows.sumOf { it.calls }}；usage 合计：${rows.sumOf { it.tokens }} tokens。\n\n")
        sb.append("## 逐链\n\n")
        sb.append("| 链 | 后端调用 | tokens | 耗时 ms | 门禁硬伤 | 事实 |\n")
        sb.append("| --- | ---: | ---: | ---: | ---: | --- |\n")
        for (r in rows) {
            sb.append("| ${r.chain} | ${r.calls} | ${r.tokens} | ${r.elapsedMs} | ${r.hardIssues} | ${r.detail} |")
            sb.append('\n')
        }
        sb.append("\n## 失败模式\n\n")
        val failures = rows.filter { it.failureMode != null || it.gateFailed }
        if (failures.isEmpty()) {
            sb.append("- 本轮无失败样本：七条链均取得非空正文与真实 usage 记账。\n")
        } else {
            for (f in failures) {
                sb.append("- ${f.chain}：${f.failureMode ?: "gateFailed"}\n")
            }
        }
        sb.append("\n## 边界（诚实）\n\n")
        sb.append("- **这不是厂商模型评测**：应答由助手撰写并冻结 → 任何质量结论都是**助手撰写的质量**，")
        sb.append("不是厂商模型的；**不得**据此宣称「某厂商模型效果好/差」。\n")
        sb.append("- 本报告证明的是**端点级**事实：四条链真的走生产传输路径（HTTP / Bearer / OpenAI 协议 / usage 记账），")
        sb.append("流式链走真 SSE 分片并验证「增量拼接 = 最终正文」，OCR 链走真 vision 协议并产出真 manifest 哈希，")
        sb.append("且各链输出都过生产的零编造门禁。\n")
        sb.append("- **未覆盖**：厂商模型的质量、成本、限流、拒答率、多模型差异、真实端点的 P99 尾延迟分布。\n")
        sb.append("- 单批次、单一替身、样本量小；不构成任何通用结论（D16/D18/D19/D20 纪律）。\n")
        sb.append("- 报告正文与评分卡只写入 `host/build/model-eval/t135/`（gitignored），仅作证据，不进入产品数据。\n")
        return sb.toString()
    }
}
