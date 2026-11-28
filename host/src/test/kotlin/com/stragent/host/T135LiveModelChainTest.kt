package com.stragent.host

import com.stragent.core.ai.LlmQueryDecomposer
import com.stragent.core.ai.summarizeText
import com.stragent.core.ports.PrefsStore
import com.stragent.data.HttpLlmClient
import com.stragent.data.StepfunConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-135 · 语义级抽样升级轮：**会话模型对真实 prompt 现场生成应答**（两段式）。
 *
 * ## 与 [T135EndpointChainTest]（冻结样本轮）的实质区别
 *
 * 冻结轮的应答是「**没见过 prompt 的预写**」——只能证明端点级事实。
 * 本件把抽样升级为两段式：
 *
 * 1. **capture**（`-Dstragent.t135.live.mode=capture`）：用冻结样本让管线走完整，
 *    逐请求记录**真实 prompt**（OpenAI 请求体全文）落
 *    `host/build/model-eval/t135/t135-live-prompts.json`；
 * 2. **会话模型（GLM，ZCode 会话内）** 读捕获的真实 prompt，**现场生成**应答，
 *    冻结为仓内资源 `host/src/test/resources/model-eval/t135-live-responses.json`；
 * 3. **replay**（`-Dstragent.t135.live.mode=replay`）：生产客户端把生成应答经真 HTTP
 *    送进管线，跑**生产的零编造门禁**（数字溯源 / 段号锚 / JSON 解析 / 子查询上限），
 *    出语义级效果报告。
 *
 * ## 诚实边界（报告与 D7 自带）
 *
 * - 应答是**本会话模型**的生成——质量结论是「GLM-5.3-Flash 作为后端」的表现，
 *   **不是任何厂商模型的结论**，不得误读；
 * - 应答生成后**随件冻结**（与真实评测批次的「批次日志固化」同形态）——复跑对拍的是
 *   同一批生成物与管线/门禁的确定性，不是每次重新生成；
 * - **同模型自审偏置**：门禁是确定性代码（无 LLM judge），但「生成与评价同一模型」的
 *   偏置如实登记；
 * - **OCR 链不适用现场生成**：夹具是 1×1 PNG，无内容可读——「识别」无从谈起，
 *   OCR 维持协议级验证（真 vision 协议 + manifest），不冒充语义级；
 * - 适配/流式/对冲三张脸是**传输契约面**（语料复放 / SSE 拼接 / 成本上界），与模型
 *   质量无关，由冻结轮覆盖，本件不重复；
 * - **未覆盖**：厂商 serving 面（限流 / 拒答率 / P99 尾延迟 / 多模型差异）——
 *   T-137 判据②仍需真实端点。
 *
 * ## 运行
 *
 * ```
 * ./gradlew :host:test --tests "com.stragent.host.T135LiveModelChainTest" \
 *   -Dstragent.t135.live.mode=capture   # 第一段：捕 prompt
 * # 会话模型据 t135-live-prompts.json 生成应答 → 填 resources/model-eval/t135-live-responses.json
 * ./gradlew :host:test --tests "com.stragent.host.T135LiveModelChainTest" \
 *   -Dstragent.t135.live.mode=replay    # 第二段：回放生成应答 + 门禁 + 报告
 * ```
 */
class T135LiveModelChainTest {

    /** 本件覆盖的语义链（适配/流式/对冲/OCR 不在此列，见类注释的边界段） */
    private val liveChains = setOf(
        MultiChainBackendStub.CHAIN_RETRIEVAL,
        MultiChainBackendStub.CHAIN_DECOMPOSE,
        MultiChainBackendStub.CHAIN_SUMMARY_MAP,
        MultiChainBackendStub.CHAIN_SUMMARY_MERGE,
        MultiChainBackendStub.CHAIN_SUMMARY_COD,
    )

    /** 内存偏好（Key 不落盘）——与 T135EndpointChainTest 同款 */
    private class MemoryPrefs : PrefsStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = values[key] ?: fallback
        override fun set(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private fun prefsWithKey(): PrefsStore = MemoryPrefs().apply {
        set(StepfunConfig.VENDOR_STORAGE, "deepseek")
        set(StepfunConfig.CUSTOM_MODEL_STORAGE, MultiChainBackendStub.MODEL_NAME)
        set(StepfunConfig.KEY_STORAGE, MultiChainBackendStub.API_KEY)
    }

    /** 三张语义脸的运行结果（一行 = 一张脸的语义级事实） */
    private data class LiveRow(
        val face: String,
        val calls: Int,
        val tokens: Int,
        val gateFailed: Boolean,
        val gateIssues: Int,
        val fact: String,
    )

    /** 三张脸的完整结果（含原始产出，供断言与报告） */
    private data class FacesResult(
        val rows: List<LiveRow>,
        val retrievalAnswer: String,
        val retrievalGateIssues: List<String>,
        val retrievalGateFailed: Boolean,
        val subQueries: List<String>,
        val summaryText: String,
        val summaryFailedChunks: List<Int>,
        val calls: List<MultiChainBackendStub.Call>,
    )

    /** 跑三张语义脸（检索 / 分解 / 摘要），应答由已登记的端点队列提供 */
    private fun runFaces(backend: MultiChainBackendStub, client: HttpLlmClient, dataset: com.stragent.core.dataset.Dataset): FacesResult {
        val rows = mutableListOf<LiveRow>()

        // ---------- ① 检索链（追问）：真实 prompt → 现场生成应答 → 数字溯源门禁 ----------
        val retrieval = run {
            val before = backend.calls.size
            val result = com.stragent.core.ai.askFollowup(
                llm = client,
                prefs = prefsWithKey(),
                dataset = dataset,
                history = emptyList(),
                question = "3 号测点沉降是否超过设计控制值？超了多少？",
                model = MultiChainBackendStub.MODEL_NAME,
            )
            val calls = backend.calls.drop(before)
            LiveRow(
                face = "检索",
                calls = calls.size,
                tokens = result.totalTokens,
                gateFailed = result.gateFailed,
                gateIssues = result.gateIssues.size,
                fact = "回答 ${result.content.length} 字；门禁 issues=${result.gateIssues.size}",
            ) to result
        }
        rows += retrieval.first

        // ---------- ② 分解链（T-113）：真实 prompt → 现场生成子查询 ----------
        val decompose = run {
            val before = backend.calls.size
            val result = LlmQueryDecomposer.decompose(
                llm = client,
                model = MultiChainBackendStub.MODEL_NAME,
                question = "3 号测点沉降与设计控制值的差值是多少？同期速率与水位变化有何关联？报警值是否经过设计确认？",
                cancelSignal = { false },
            )
            val calls = backend.calls.drop(before)
            LiveRow(
                face = "分解",
                calls = calls.size,
                tokens = result.totalTokens,
                gateFailed = false,
                gateIssues = 0,
                fact = "分解出 ${result.subQueries?.size ?: 0} 条子查询",
            ) to result
        }
        rows += decompose.first

        // ---------- ③ 摘要链（T-096）：map → 归并 → CoD 两轮，全部现场生成 ----------
        val summary = run {
            val before = backend.calls.size
            val summary = summarizeText(
                llm = client,
                model = MultiChainBackendStub.MODEL_NAME,
                dataset = dataset,
                maxWords = 400,
            )
            val calls = backend.calls.drop(before)
            LiveRow(
                face = "摘要",
                calls = calls.size,
                tokens = summary.totalTokens,
                gateFailed = false,
                gateIssues = 0,
                fact = "总结 ${summary.summary.length} 字，${summary.points.size} 条要点，${summary.chunkCount} 块",
            ) to summary
        }
        rows += summary.first

        return FacesResult(
            rows = rows,
            retrievalAnswer = retrieval.second.content,
            retrievalGateIssues = retrieval.second.gateIssues,
            retrievalGateFailed = retrieval.second.gateFailed,
            subQueries = decompose.second.subQueries ?: emptyList(),
            summaryText = summary.second.summary,
            summaryFailedChunks = summary.second.failedChunks,
            calls = backend.calls.filter { it.chain in liveChains },
        )
    }

    @Test
    fun `live model two phase sampling - capture prompts then replay assistant generated responses`() {
        val mode = System.getProperty("stragent.t135.live.mode")
        assumeTrue(
            "显式跳过：需 -Dstragent.t135.live.mode=capture|replay 显式触发（两段式证据轮，不进常规全量）",
            mode == "capture" || mode == "replay",
        )

        MultiChainBackendStub().use { backend ->
            val client = HttpLlmClient(prefsWithKey(), baseUrlOverride = backend.baseUrl)
            val dataset = T135ChainSamples.dataset()

            if (mode == "capture") {
                // ---- 第一段：冻结样本让管线走完整（否则请求发不全），记录真实 prompt ----
                backend.serveCyclic(MultiChainBackendStub.CHAIN_RETRIEVAL, T135ChainSamples.RETRIEVAL_ANSWER)
                backend.serveCyclic(MultiChainBackendStub.CHAIN_DECOMPOSE, T135ChainSamples.DECOMPOSE_ANSWER)
                backend.serveCyclic(MultiChainBackendStub.CHAIN_SUMMARY_MAP, T135ChainSamples.SUMMARY_MAP_ANSWER)
                backend.serve(MultiChainBackendStub.CHAIN_SUMMARY_COD, T135ChainSamples.SUMMARY_MERGE_ANSWER)
                backend.serve(MultiChainBackendStub.CHAIN_SUMMARY_COD, T135ChainSamples.SUMMARY_COD_ROUND2)
                backend.serveCyclic(MultiChainBackendStub.CHAIN_SUMMARY_COD, T135ChainSamples.SUMMARY_COD_ROUND2)

                val result = runFaces(backend, client, dataset)

                val prompts = result.calls.mapIndexed { i, c ->
                    buildJsonObject {
                        put("seq", i)
                        put("chain", c.chain)
                        put("stream", c.stream)
                        put("body", c.body)
                    }
                }
                val outDir = File("build/model-eval/t135").apply { mkdirs() }
                File(outDir, "t135-live-prompts.json").writeText(
                    buildJsonArray { prompts.forEach { add(it) } }.toString(),
                    Charsets.UTF_8,
                )
                println("T-135 live capture: ${prompts.size} prompts -> ${outDir.absolutePath}/t135-live-prompts.json")
                println("链序: ${result.calls.map { it.chain }}")
                assertTrue(result.calls.isNotEmpty(), "应捕获到语义链请求")
            } else {
                // ---- 第二段：回放会话模型生成的应答（仓内资源，生成后冻结） ----
                val resource = javaClass.getResourceAsStream("/model-eval/t135-live-responses.json")
                assertNotNull(resource, "缺资源 /model-eval/t135-live-responses.json：先跑 capture 段，会话模型生成应答后入库")
                val entries = Json.parseToJsonElement(resource.use { it.readBytes().toString(Charsets.UTF_8) }).jsonArray
                // 按捕获顺序入队（per-chain FIFO；队列耗尽 = 500 = 管线漂移，测试红）
                for (e in entries) {
                    val o = e.jsonObject
                    backend.serve(o["chain"]!!.jsonPrimitive.content, o["content"]!!.jsonPrimitive.content)
                }

                val result = runFaces(backend, client, dataset)

                // ---------- 门禁与事实断言（生产的零编造门禁跑在本会话模型的生成物上） ----------
                assertTrue(!result.retrievalGateFailed, "检索链门禁不应失败：${result.retrievalGateIssues}")
                assertTrue(result.retrievalAnswer.length > 60, "检索回答不应是片段（实际 ${result.retrievalAnswer.length} 字）")
                assertTrue(result.subQueries.size >= 2, "多跳问题应分解出 ≥2 子查询，实际 ${result.subQueries}")
                assertTrue(result.subQueries.all { it.length <= 60 }, "子查询长度须在上限内：${result.subQueries}")
                assertTrue(result.summaryText.isNotBlank(), "总结正文不应为空")
                assertTrue(result.summaryFailedChunks.isEmpty(), "不应有失败块：${result.summaryFailedChunks}")
                assertTrue(result.rows.all { it.tokens > 0 }, "每张脸都应拿到真实 usage 记账")

                // ---------- 效果报告 ----------
                val report = buildLiveReport(result, entries.size)
                val outDir = File("build/model-eval/t135").apply { mkdirs() }
                File(outDir, "t135-live-report.md").writeText(report, Charsets.UTF_8)
                print(report)
            }
        }
    }

    /** 语义级效果报告：逐脸事实 + 现场生成应答全文 + 诚实边界 */
    private fun buildLiveReport(result: FacesResult, responseCount: Int): String {
        val sb = StringBuilder()
        sb.append("# T-135 · 语义级抽样报告（会话模型对真实 prompt 现场生成）\n\n")
        sb.append("## 条件\n\n")
        sb.append("- 应答来源：**会话模型（GLM-5.3-Flash，ZCode 会话内）对捕获的真实 prompt 现场生成**，")
        sb.append("生成后随件冻结（`host/src/test/resources/model-eval/t135-live-responses.json`，$responseCount 条）。\n")
        sb.append("- 客户端与门禁：**生产** `HttpLlmClient`（真 HTTP / 真 Bearer / 真 OpenAI 协议 / 真 usage 记账）")
        sb.append("与**生产的零编造门禁**（数字溯源 / 段号锚）。\n")
        sb.append("- 材料：管廊连接围护监测（8 段，有意构造，埋 3 个可发现真问题）。\n")
        sb.append("- 调用合计：${result.rows.sumOf { it.calls }}；usage 合计：${result.rows.sumOf { it.tokens }} tokens。\n\n")
        sb.append("## 逐脸\n\n")
        sb.append("| 脸 | 后端调用 | tokens | 门禁 | 事实 |\n")
        sb.append("| --- | ---: | ---: | --- | --- |\n")
        for (r in result.rows) {
            val gate = if (r.gateFailed) "失败(${r.gateIssues})" else "通过(${r.gateIssues} issues)"
            sb.append("| ${r.face} | ${r.calls} | ${r.tokens} | $gate | ${r.fact} |\n")
        }
        sb.append("\n## 现场生成应答（原文）\n\n")
        sb.append("### 检索链回答\n\n${result.retrievalAnswer}\n\n")
        sb.append("### 分解链子查询\n\n${result.subQueries.joinToString("\n") { "- $it" }}\n\n")
        sb.append("### 摘要链终稿\n\n${result.summaryText}\n\n")
        sb.append("## 边界（诚实）\n\n")
        sb.append("- **这不是厂商模型评测**：应答是本会话模型的生成。质量结论属于「GLM-5.3-Flash 作为后端」")
        sb.append("在本管线上的表现；**不得**据此宣称任何厂商模型的效果。\n")
        sb.append("- 应答生成后随件冻结——复跑证明的是「该批生成物 × 管线 × 门禁」的确定性对拍，不是每次重新生成。\n")
        sb.append("- **同模型自审偏置**：生成与被门禁评价的是同一族模型（门禁本身是确定性代码，无 LLM judge）。\n")
        sb.append("- OCR 链不适用现场生成（1×1 夹具无内容可读），维持协议级验证；适配/流式/对冲为传输契约面（冻结轮覆盖）。\n")
        sb.append("- **未覆盖**：厂商 serving 面（限流/拒答率/P99 尾延迟/成本/多模型差异）——T-137 判据②仍需真实端点。\n")
        sb.append("- 单批次、小样本；不构成通用结论（D16/D18/D19/D20 纪律）。\n")
        return sb.toString()
    }
}
