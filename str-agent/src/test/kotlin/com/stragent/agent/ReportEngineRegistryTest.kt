package com.stragent.agent

import com.stragent.core.ai.InsightOrchestrator
import com.stragent.core.ai.InsightResult
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-058 · ReportEngine 接口化（D17 G-19）。
 *
 * 证明三件事：
 *  1. 引擎分发由注册表回答——`AgentEngine` 不再硬编码 report/editorial；
 *  2. **新增引擎 + 新增 kind 不改分发点**：本测试只做「登记 kind + 注册引擎」，
 *     编排器/门禁/回落代码一行未动，新引擎照样被选中；
 *  3. 未注册的引擎 kind 不会被误当成报告插件（回落经典）。
 */
class ReportEngineRegistryTest {

    private class MapPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class MapStore : DatasetStore {
        private val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    /** 全程不该被调用的模型：谁调谁爆炸 */
    private class ExplodingLlm : LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult = error("自定义引擎路径不应调用 LLM")
    }

    private class StubEngine(private val report: String) : ReportEngine {
        override val kind: String = "stub-engine"
        override val gateRevisionBudget: Int = 0
        var requests = 0
        override fun generate(request: ReportEngineRequest): InsightResult {
            requests += 1
            return InsightResult(
                report = report,
                totalTokens = 0,
                targetChars = request.target.chars,
                actualChars = report.length,
                autonomous = true,
                engineId = request.plugin.id,
            )
        }
    }

    private fun dataset(): Dataset {
        val text = File("../fixtures/sample/single_col.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "single_col.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `注册表——内置两种引擎，支持与否由注册表回答`() {
        assertTrue(ReportEngineRegistry.BUILT_IN.supports("report"))
        assertTrue(ReportEngineRegistry.BUILT_IN.supports("editorial"))
        assertTrue(!ReportEngineRegistry.BUILT_IN.supports("followup"), "追问不是报告引擎")
        assertTrue(!ReportEngineRegistry.BUILT_IN.supports("stub-engine"))
    }

    @Test
    fun `新增引擎与 kind 不改分发点——登记即可被编排器选中`() {
        // ① 登记一种新 kind（输出契约 = 报告全文）
        PluginKinds.register(PluginKindSpec("stub-engine", FinishShape.REPORT, "args.report = 完整 Markdown 报告全文"))
        // ② 注册表加一个引擎实现
        val engine = StubEngine("## 总体概览\n\n合计 256，平稳。")
        val engines = ReportEngineRegistry.BUILT_IN.with("stub-engine") { _, _ -> engine }

        val prefs = MapPrefs()
        val plugins = PluginRegistry(tempDir("report-engine"))
        plugins.import("""{"id":"stub-plugin","name":"桩引擎","kind":"stub-engine","systemPrompt":"p"}""")
        AgentEngine.setEngineId(prefs, "stub-plugin")

        // 未注册该引擎的注册表里，这个插件不是报告引擎 → 回落经典
        assertNull(AgentEngine.selectedReportPlugin(prefs, plugins), "内置注册表不认识 stub-engine")
        assertNotNull(AgentEngine.selectedReportPlugin(prefs, plugins, engines), "注册后即可承接")

        val llm = ExplodingLlm()
        val orchestrator = AgentIntegratedOrchestrator(
            InsightOrchestrator(llm, prefs, MapStore(), search = null, model = "test"),
            llm, prefs, plugins, reportEngines = engines,
        )
        val result = orchestrator.generateInsight(dataset())

        assertEquals(1, engine.requests, "编排器必须走注册表解析出的引擎")
        assertEquals("stub-plugin", result.engineId)
        assertEquals("## 总体概览\n\n合计 256，平稳。", result.report)
    }

    @Test
    fun `门禁预算由引擎声明——编辑部 0 轮、单 agent 满额`() {
        val editorial = ReportEngineRegistry.BUILT_IN.create(
            PluginManifest(
                id = "e", name = "e", kind = "editorial", systemPrompt = "p",
                writerPrompt = "w", beats = listOf(EditorialBeat("b", "p")),
            ),
            ReportEngineRegistry.Deps(ExplodingLlm(), MapPrefs()),
        )
        assertEquals(0, editorial?.gateRevisionBudget)
        val single = ReportEngineRegistry.BUILT_IN.create(
            PluginManifest(id = "r", name = "r", kind = "report", systemPrompt = "p"),
            ReportEngineRegistry.Deps(ExplodingLlm(), MapPrefs()),
        )
        assertEquals(com.stragent.core.report.MAX_GATE_REVISIONS, single?.gateRevisionBudget)
    }
}
