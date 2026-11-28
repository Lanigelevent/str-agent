package com.stragent.agent

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.NotebookSerializer.NotebookCell
import com.stragent.core.trace.ProvenanceStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-097 覆盖补强（T-093 边界登记的下一空白）：AgentLoop 深分支——
 * aborted 的三种触发（修复耗尽 / 末轮不 finish / 预算耗尽）与 TEXT_LIMIT_PROMPT 收尾注入；
 * 顺带补三个 0% 类的可达路径：ToolSchema（schema 组装）、NotebookSerializer（nb 形状）、
 * EditorialReportEngine（编辑部引擎的 InsightResult 透传与空手 null）。
 */
class AgentLoopDeepBranchTest {

    /** 按调用序弹出预设回复（与 AgentKernelTest 同构） */
    private class ScriptedLlm(private val outputs: MutableList<String>) : LlmClient {
        val calls = mutableListOf<List<Pair<String, String>>>()
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            return if (outputs.isEmpty()) ChatResult("（空）", 10) else ChatResult(outputs.removeAt(0), 50)
        }
    }

    private val probe = object : AgentTool {
        override val name = "probe"
        override val description = "测试探针"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
    }

    private val registry = ToolRegistry.of(listOf(probe))

    private fun manifest(maxSteps: Int = 5) = PluginManifest(
        id = "test-plugin", name = "测试插件", kind = "report",
        systemPrompt = "做一份测试报告。", tools = null, maxSteps = maxSteps,
    )

    // ---------- aborted：修复耗尽 ----------

    @Test
    fun `aborted——修复预算耗尽返回 null 调用方回落经典流水线`() {
        val llm = ScriptedLlm(
            mutableListOf("坏输出一", "坏输出二", "坏输出三"),
        )
        assertNull(
            AgentLoop(llm).run(manifest(maxSteps = 8), registry, "m", "任务", AGENT_HARNESS_CONTRACT),
            "MAX_REPAIRS=2 用尽 → aborted（null）",
        )
        assertEquals(3, llm.calls.size, "1 首轮 + 2 轮修复重试")
        // 修复提示进了对话历史
        assertTrue(llm.calls[1].any { it.second.contains("不是合法的单个 JSON 对象") })
    }

    // ---------- aborted：末轮不 finish ----------

    @Test
    fun `aborted——最后一轮仍在调工具（模型不守收尾约定）`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"thought":"查一下","tool":"probe","args":{"k":1}}""",
                """{"thought":"还要查","tool":"probe","args":{"k":2}}""",
            ),
        )
        assertNull(
            AgentLoop(llm).run(manifest(maxSteps = 2), registry, "m", "任务", AGENT_HARNESS_CONTRACT),
            "maxSteps=2 且末轮仍调工具 → aborted",
        )
    }

    @Test
    fun `aborted——单步预算首轮即调工具`() {
        val llm = ScriptedLlm(
            mutableListOf("""{"thought":"查","tool":"probe","args":{}}"""),
        )
        assertNull(
            AgentLoop(llm).run(manifest(maxSteps = 1), registry, "m", "任务", AGENT_HARNESS_CONTRACT),
        )
    }

    // ---------- 预算见底：TEXT_LIMIT_PROMPT 注入 ----------

    @Test
    fun `预算见底——末轮前注入收尾指令且模型照办则完成`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"thought":"查","tool":"probe","args":{}}""",
                """{"tool":"finish","args":{"report":"收尾完成"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(maxSteps = 2), registry, "m", "任务", AGENT_HARNESS_CONTRACT)!!
        assertEquals("收尾完成", outcome.output["report"]?.toString()?.trim('"'))
        val lastUser = llm.calls[1].last { it.first == "user" }.second
        assertTrue(lastUser.contains("已达到工具调用步数上限"), "TEXT_LIMIT_PROMPT 注入末轮: $lastUser")
    }

    // ---------- ToolSchema（0% 类） ----------

    @Test
    fun `toolSchema——四种组装形状`() {
        val obj = schemaObject(
            "a" to schemaField("string", "甲"),
            "b" to schemaField("number"),
            "c" to schemaArrayField(schemaField("string"), "列表"),
            required = listOf("a"),
        )
        assertEquals("object", obj.getValue("type").toString().trim('"'))
        assertTrue(obj.getValue("required").toString().contains("a"), "required 在场")
        val props = obj.getValue("properties") as JsonObject
        assertEquals("string", (props.getValue("a") as JsonObject).getValue("type").toString().trim('"'))
        assertTrue((props.getValue("a") as JsonObject).getValue("description").toString().contains("甲"))
        val c = props.getValue("c") as JsonObject
        assertEquals("array", c.getValue("type").toString().trim('"'))
        assertTrue(c.getValue("items").toString().contains("string"))

        val anyField = schemaAnyField("宽松对象")
        assertEquals(1, anyField.size, "schemaAnyField 只有 description")
        assertTrue((anyField["description"] as JsonPrimitive).content.contains("宽松"))

        // required 缺省：整个 required 键都不出现（省字节）
        val bare = schemaObject("x" to schemaField("boolean"))
        assertNull(bare["required"], "无 required 时不该有该键")
        // 无描述的字段：只有 type
        assertEquals(1, ((bare.getValue("properties") as JsonObject).getValue("x") as JsonObject).size)
    }

    // ---------- NotebookSerializer（0% 类） ----------

    @Test
    fun `notebook——trace 来源与 provenance 来源两种形状`() {
        val cell = NotebookCell(
            tool = "probe", args = buildJsonObject { put("k", 1) },
            result = buildJsonObject { put("ok", true) }, durationMs = 12,
        )
        val nb = NotebookSerializer.fromTrace("运行记录", listOf(cell))
        assertEquals("运行记录", nb.name)
        val json = nb.toJson()
        assertEquals(4, json.getValue("nbformat").toString().trim('"').toInt())
        val cells = json.getValue("cells").toString()
        assertTrue(cells.contains("tool_call") && cells.contains("probe") && cells.contains("duration_ms"))

        val step = ProvenanceStep(
            tool = "probe", args = buildJsonObject { put("k", 2) }, argsHash = "h", resultHash = "h",
            datasetHash = "", elapsedMs = 5,
        )
        val fromProv = NotebookSerializer.fromProvenanceSteps("溯源", listOf(step))
        assertEquals(1, fromProv.cells.size)
        assertNull(fromProv.cells[0].result, "manifest 只存哈希不存原始结果")
        assertEquals(5, fromProv.cells[0].durationMs)
        assertTrue(fromProv.toJson().getValue("cells").toString().contains("probe"))
    }

    // ---------- EditorialReportEngine（0% 类） ----------

    private class EnginePrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun dataset(): Dataset {
        val text = File("../fixtures/sample/superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        return Dataset(
            name = "superstore.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows,
            columnTypes = Csv.inferTypes(limited.rows, limited.columns.size),
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    private fun editorialTeam() = PluginManifest(
        id = "editorial-engine-test", name = "编辑部引擎测试", kind = "editorial",
        systemPrompt = "CHIEF_PLAN：选题会。", tools = emptyList(), maxSteps = 4,
        writerPrompt = "WRITER：汇编。",
        beats = listOf(
            EditorialBeat(name = "条线一", prompt = "BEAT_ONE：查证。", tools = emptyList()),
        ),
    )

    /** 与 EditorialDeptTest 同款五阶段路由（单条线精简版） */
    private class EngineRoutingLlm : LlmClient {
        val calls = mutableListOf<List<Pair<String, String>>>()
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
            calls.add(messages)
            val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
            val last = messages.last().second
            val reply = when {
                system.contains("CHIEF_PLAN") && !last.contains("计划已登记") ->
                    """{"thought":"拆线","tool":"submit_plan","args":{"steps":["查证","汇编"]}}"""
                system.contains("CHIEF_PLAN") -> """{"tool":"finish","args":{}}"""
                system.contains("BEAT_") && !last.contains("record_finding") ->
                    """{"tool":"record_finding","args":{"title":"发现甲","detail":"West 725457.94 居首","evidence":"Region"}}"""
                system.contains("BEAT_") -> """{"tool":"finish","args":{"report":"条线小结"}}"""
                system.contains("WRITER") && !last.contains("audit") && !last.contains("audit_report") ->
                    """{"tool":"finish","args":{"report":"## 总体概览\nWest 725,457.94 居首。"}}"""
                else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
            }
            return ChatResult(reply, 20)
        }
    }

    @Test
    fun `editorial 引擎——编辑部产出透传为 InsightResult（autonomous 与 engineId）`() {
        val llm = EngineRoutingLlm()
        val engine = EditorialReportEngine(llm, EnginePrefs())
        assertEquals("editorial", engine.kind)
        assertEquals(0, engine.gateRevisionBudget, "编辑部内部已用满退稿预算 → 外层 0")
        val result = engine.generate(
            com.stragent.agent.ReportEngineRequest(
                dataset = dataset(),
                plugin = editorialTeam(),
                brief = com.stragent.core.brief.buildDatasetBrief(dataset()),
                target = com.stragent.core.report.TargetLength(2000, "测试"),
                typeId = "standard",
            ),
        )
        assertNotNull(result, "全链成功应产出结果")
        assertTrue(result.autonomous, "编辑部路径 autonomous=true")
        assertEquals("editorial-engine-test", result.engineId)
        assertTrue(result.report.contains("725,457.94"))
        assertTrue(result.agentPlan.contains("查证"), "计划层透传")
        assertTrue(result.agentFindings.isNotEmpty(), "发现卡透传")
    }

    @Test
    fun `editorial 引擎——编辑部空手时引擎返回 null（回落经典流水线）`() {
        // 记者线全部 finish 空报告 → EditorialDept 返回 null → 引擎 null
        val llm = object : LlmClient {
            override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): ChatResult {
                val system = messages.firstOrNull { it.first == "system" }?.second ?: ""
                val reply = when {
                    system.contains("CHIEF_PLAN") && !messages.last().second.contains("计划已登记") ->
                        """{"tool":"submit_plan","args":{"steps":["查证"]}}"""
                    system.contains("CHIEF_PLAN") -> """{"tool":"finish","args":{}}"""
                    system.contains("BEAT_") -> """{"tool":"finish","args":{"report":""}}"""
                    system.contains("WRITER") -> """{"tool":"finish","args":{"report":""}}"""
                    else -> """{"tool":"finish","args":{"decision":"publish","notes":""}}"""
                }
                return ChatResult(reply, 5)
            }
        }
        val engine = EditorialReportEngine(llm, EnginePrefs())
        assertNull(
            engine.generate(
                com.stragent.agent.ReportEngineRequest(
                    dataset = dataset(),
                    plugin = editorialTeam(),
                    brief = com.stragent.core.brief.buildDatasetBrief(dataset()),
                    target = com.stragent.core.report.TargetLength(2000, "测试"),
                    typeId = "standard",
                ),
            ),
            "编辑部空手 → 引擎 null → 编排器回落经典流水线",
        )
    }
}
