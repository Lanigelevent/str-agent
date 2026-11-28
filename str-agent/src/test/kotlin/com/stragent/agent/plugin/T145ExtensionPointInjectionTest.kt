package com.stragent.agent.plugin

import com.stragent.agent.AgentEngine
import com.stragent.agent.FinishShape
import com.stragent.agent.PluginKindSpec
import com.stragent.agent.PluginKinds
import com.stragent.agent.ReportEngine
import com.stragent.agent.ReportEngineRegistry
import com.stragent.agent.ReportEngineRequest
import com.stragent.core.ai.InsightResult
import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.report.ReportLib
import com.stragent.core.report.ReportTypeProfile
import com.stragent.core.report.ReportTypeRegistry
import java.io.File
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-145 · 扩展点**生产可注入**的验收测试。
 *
 * ## 为什么这两个用例长这样
 *
 * 深度审查实测到：`ReportEngineRegistry.with()` / `ReportTypeRegistry.with()` 这两个
 * 扩展点**在生产装配路径上不可注入**——
 *  ① `StrAgentPlugin.install()`（**唯一**的生产装配入口）构造编排器时**不传**它们；
 *  ② `StrAgentHostContext` 里**没有任何 registry 字段**——宿主根本无处可传；
 *  ③ 更深一层：`planBriefBudget` / `planChapters` / `targetLengthFor` 三个叶子函数
 *     **都有** `types`/`registry` 参数，却**从没有任何调用方传过**（恒为 `BUILT_IN`）。
 *
 * 所以「扩展点可用」这件事**必须从 `install()` 这一层证明**——直接调编排器构造参数
 * （既有 [com.stragent.agent.ReportEngineRegistryTest] 的做法）**证明不了生产路径**。
 * 本文件因此**只走 `StrAgentPlugin.install(ctx)`**。
 *
 * ## 两条断言各自证明什么
 *
 * ① **引擎注册表真的接上了**：宿主注册一个自定义 kind 的引擎 → 插件在生产装配路径上
 *    **选中它**（`engine.requests == 1`）。此前不可能：`install()` 不传注册表 →
 *    内置注册表不认识这个 kind → 回落经典路径 → 自定义引擎永远不被调用。
 * ② **报告类型登记表真的接上了**：宿主覆盖某个类型的 `lengthFactor` → 编排器算出的
 *    **目标字数**随之改变。目标字数由 `targetLengthFor(..., registry)` 决定，而该
 *    `registry` 参数此前恒为 `BUILT_IN` → 宿主的覆盖**完全无效**。
 *    这里用「引擎收到的 `request.target.chars`」作为**可观察量**——它同时覆盖
 *    「登记表进了编排器」与「登记表进了目标字数计算」两段链路。
 */
class T145ExtensionPointInjectionTest {

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

    /** 自定义引擎路径**不该**调用 LLM；调用即说明分发走错了 */
    private class ExplodingLlm : LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult = error("自定义引擎路径不应调用 LLM")
    }

    /** 记录收到的请求——目标字数是「登记表有没有真的生效」的可观察量 */
    private class RecordingEngine(engineKind: String) : ReportEngine {
        override val kind: String = engineKind
        override val gateRevisionBudget: Int = 0
        var requests = 0
        var lastTargetChars: Int = -1
        override fun generate(request: ReportEngineRequest): InsightResult {
            requests += 1
            lastTargetChars = request.target.chars
            return InsightResult(
                report = "## 总体概览\n\n宿主注入的引擎产出。",
                totalTokens = 0,
                targetChars = request.target.chars,
                actualChars = 12,
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

    /** 装配一个「宿主 + 自定义引擎」：插件清单先落进 pluginDir，再 install */
    private fun installWith(
        kind: String,
        engine: ReportEngine,
        engines: ReportEngineRegistry,
        prefs: PrefsStore,
        reportTypes: ReportTypeRegistry = ReportTypeRegistry.BUILT_IN,
    ): StrAgentPlugin {
        // D25 审查：登记的 kind 用完即摘（unregisterForTest）——全局注册表不再留测试残留
        PluginKinds.register(PluginKindSpec(kind, FinishShape.REPORT, "args.report = 完整 Markdown 报告全文"))
        registeredKinds += kind
        val pluginDir = tempDir("t145")
        com.stragent.agent.PluginRegistry(pluginDir)
            .import("""{"id":"t145-plugin","name":"T145 桩插件","kind":"$kind","systemPrompt":"p"}""")
        AgentEngine.setEngineId(prefs, "t145-plugin")
        val plugin = StrAgentPlugin()
        plugin.install(
            StrAgentHostContext(
                llm = ExplodingLlm(),
                prefs = prefs,
                kv = MapStore(),
                pluginDir = pluginDir,
                reportEngines = engines,
                reportTypes = reportTypes,
            ),
        )
        return plugin
    }

    @Test
    fun `宿主注入自定义报告引擎——生产装配路径上被选中`() {
        val kind = "t145-engine"
        val engine = RecordingEngine(kind)
        val engines = ReportEngineRegistry.BUILT_IN.with(kind) { _, _ -> engine }
        val prefs = MapPrefs()

        val plugin = installWith(kind, engine, engines, prefs)
        try {
            val result = plugin.generateInsight(dataset())
            assertEquals(1, engine.requests, "宿主注册的自定义引擎必须在生产装配路径上被选中")
            assertEquals("t145-plugin", result.engineId)
        } finally {
            plugin.uninstall()
        }
    }

    @Test
    fun `宿主注入自定义报告类型——目标字数随之改变（登记表真的被用上）`() {
        val kind = "t145-engine-typed"
        val engine = RecordingEngine(kind)
        val engines = ReportEngineRegistry.BUILT_IN.with(kind) { _, _ -> engine }
        val prefs = MapPrefs()
        ReportLib.setReportType(prefs, "deepdive")

        // 基线：内置登记表下的目标字数
        val baseline = installWith(kind, engine, engines, prefs)
        val baselineChars: Int
        try {
            baseline.generateInsight(dataset())
            baselineChars = engine.lastTargetChars
            assertTrue(baselineChars > 0, "基线目标字数应可读")
        } finally {
            baseline.uninstall()
        }

        // 覆盖同一个类型的篇幅系数 → 目标字数必须变
        val boosted = ReportTypeRegistry.BUILT_IN.with(
            ReportTypeProfile("deepdive", lengthFactor = 4.0, briefFactor = 4.0),
        )
        val engine2 = RecordingEngine(kind)
        val engines2 = ReportEngineRegistry.BUILT_IN.with(kind) { _, _ -> engine2 }
        val prefs2 = MapPrefs()
        ReportLib.setReportType(prefs2, "deepdive")
        val plugin = installWith(kind, engine2, engines2, prefs2, reportTypes = boosted)
        try {
            plugin.generateInsight(dataset())
            assertTrue(
                engine2.lastTargetChars > baselineChars,
                "宿主覆盖报告类型的篇幅系数后，目标字数应随之改变" +
                    "（基线 ${baselineChars} → 覆盖后 ${engine2.lastTargetChars}）",
            )
        } finally {
            plugin.uninstall()
        }
    }
    @After
    fun cleanupRegisteredKinds() {
        registeredKinds.forEach { PluginKinds.unregisterForTest(it) }
        registeredKinds.clear()
    }

    private val registeredKinds = mutableListOf<String>()
}
