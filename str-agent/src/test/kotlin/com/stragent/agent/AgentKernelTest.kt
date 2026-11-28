package com.stragent.agent

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.LlmClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * harness 内核测试：JSON 文本协议循环（含修复/预算/取消）、注册表白名单、
 * 清单校验、分析工具适配层（列名解析与 JSON 形状）。
 */
class AgentKernelTest {
    /** 按调用序弹出预设回复（与 OrchestrationTest 的 ScriptedLlm 同构，但直接回文本） */
    private class ScriptedLlm(private val outputs: MutableList<String>) : LlmClient {
        val calls = mutableListOf<List<Pair<String, String>>>()
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): com.stragent.core.ports.ChatResult {
            calls.add(messages)
            return if (outputs.isEmpty()) com.stragent.core.ports.ChatResult("（空）", 10) else com.stragent.core.ports.ChatResult(outputs.removeAt(0), 50)
        }
    }

    private val probe = object : AgentTool {
        var executed = 0
        var lastArgs: JsonObject? = null
        override val name = "probe"
        override val description = "测试探针：返回被查的 key"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject =
            buildJsonObject {
                executed += 1
                lastArgs = args
                put("ok", true)
                put("echo", args.toString())
            }
    }

    private val registry = ToolRegistry.of(listOf(probe))

    private fun manifest(maxSteps: Int = 5) = PluginManifest(
        id = "test-plugin", name = "测试插件", kind = "report",
        systemPrompt = "做一份测试报告。", tools = null, maxSteps = maxSteps,
    )

    @Test
    fun `循环——工具调用后回填结果再 finish`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"thought":"先探针","tool":"probe","args":{"k":1}}""",
                """{"tool":"finish","args":{"report":"## 结论 42"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals("## 结论 42", outcome!!.output["report"]?.toString()?.trim('"'))
        assertEquals(1, outcome.toolCalls)
        assertEquals(1, probe.executed)
        // 第 2 轮的消息里带 <tool_result> 回填与 assistant 原文
        val secondCall = llm.calls[1]
        assertTrue(
            secondCall.any { it.first == "assistant" && it.second.contains("\"tool\":\"probe\"") },
            "assistant 原文垫回: ${secondCall.joinToString("|") { it.first + ":" + it.second.take(60) }}",
        )
        assertTrue(
            secondCall.any { it.first == "user" && it.second.contains("<tool_result tool=\"probe\">") },
            "tool_result 回填: ${secondCall.joinToString("|") { it.first + ":" + it.second.take(60) }}",
        )
        assertTrue(
            secondCall.any { it.second.contains("\"echo\"") },
            "echo 结果: ${secondCall.joinToString("|") { it.first + ":" + it.second.take(80) }}",
        )
        assertTrue(llm.calls[0][0].second.contains("<harness>"), "契约在场")
        assertTrue(llm.calls[0][0].second.contains("\"name\":\"probe\""), "目录在场: ${llm.calls[0][0].second.take(400)}")
        assertTrue(llm.calls[0][0].second.contains("做一份测试报告"), "插件提示词在场")
    }

    @Test
    fun `循环——解析失败触发修复提示（最多 2 轮）`() {
        val llm = ScriptedLlm(
            mutableListOf(
                "这不是 JSON",
                "好的，我明白了", // 第 2 次仍不是 JSON
                """{"tool":"finish","args":{"report":"补救成功"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals("补救成功", outcome!!.output["report"]?.toString()?.trim('"'))
        // 两条修复提示都进了历史
        assertTrue(llm.calls.count { calls -> calls.any { it.second.contains("不是合法的单个 JSON 对象") } } >= 2)
    }

    @Test
    fun `循环——预算见底强推 finish、仍不守约则回落 null`() {
        // maxSteps=1：唯一一轮在调工具而非 finish → null（调用方回落经典流水线）
        val llm = ScriptedLlm(mutableListOf("""{"tool":"probe","args":{}}"""))
        assertNull(AgentLoop(llm).run(manifest(maxSteps = 1), registry, "m", "任务", AGENT_HARNESS_CONTRACT))
    }

    @Test
    fun `循环——cancelSignal 每步检查`() {
        val llm = ScriptedLlm(mutableListOf("""{"tool":"finish","args":{"report":"x"}}"""))
        assertFailsWith<AgentCancelled> {
            AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT, cancelSignal = { true })
        }
    }

    @Test
    fun `循环——未知工具回填错误且不算崩溃`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"tool":"no_such_tool","args":{}}""",
                """{"tool":"finish","args":{"report":"ok"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals(0, outcome!!.toolCalls, "未知工具不计入 toolCalls")
        assertTrue(llm.calls[1].any { it.second.contains("未知工具: no_such_tool") })
    }

    @Test
    fun `plan——submit_plan 登记计划并回传 outcome`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"tool":"submit_plan","args":{"steps":["看概况","查分组","写报告"]}}""",
                """{"tool":"finish","args":{"report":"正文"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals(listOf("看概况", "查分组", "写报告"), outcome!!.plan)
        // 计划确认回填进历史
        assertTrue(llm.calls[1].any { it.second.contains("计划已登记（3 步）") })
    }

    @Test
    fun `发现日志——record_finding 记卡（有状态，不进缓存）`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"tool":"record_finding","args":{"title":"华东最高","detail":"华东 1500.5 占 59%","evidence":"Region 列"}}""",
                """{"tool":"record_finding","args":{"title":"华南次高","detail":"华南 1020.25","evidence":"Region 列"}}""",
                """{"tool":"finish","args":{"report":"两批发现"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals(2, outcome!!.findings.size)
        assertEquals("华东最高", outcome.findings[0].title)
        assertEquals("Region 列", outcome.findings[1].evidence)
    }

    @Test
    fun `缓存——同工具同参数重复调用回缓存（探针只执行一次）`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"tool":"probe","args":{"k":1}}""",
                """{"tool":"probe","args":{"k":1}}""",
                """{"tool":"finish","args":{"report":"done"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals(1, probe.executed, "同参重复调用不重复执行")
        assertEquals(2, outcome!!.steps.size, "两次 probe 记步（finish 直接返回不记步）")
        // 第二次调用结果标 cached
        assertTrue(llm.calls[2].any { it.second.contains("\"cached\":true") })
    }

    @Test
    fun `audit_report——未溯源数字回填给 agent 修（确定性自检）`() {
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val registry = ToolRegistry.of(listOf(com.stragent.core.tools.auditReportTool(ds, brief)))
        // 草稿里放一个 brief 里不可能有的数字
        val draft = "## 总体概览\n本月神秘指标高达 987654.32 元，表现异常。"
        val outcome = registry.get("audit_report")!!.invoke(
            buildJsonObject { put("report", draft) },
        )
        val text = outcome.toString()
        assertTrue(text.contains("987654.32"), "未溯源数字应被点名: $text")
        assertTrue(text.contains("hardIssues") && text.contains("verdict"))
    }

    @Test
    fun `suggest_chart——登记图表、回传索引、上限 5 张、空 data 拒绝`() {
        val llm = ScriptedLlm(
            mutableListOf(
                """{"tool":"suggest_chart","args":{"type":"bar","title":"各组销售额","data":[{"label":"West","value":725457.94},{"label":"East","value":678781.37}],"description":"分组对比"}}""",
                """{"tool":"finish","args":{"report":"## 报告\n见 {{chart:0}}。\n\n{{chart:0}}"}}""",
            ),
        )
        val outcome = AgentLoop(llm).run(manifest(), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals(1, outcome!!.charts.size)
        assertEquals("bar", outcome.charts[0].type)
        assertEquals("West", outcome.charts[0].data[0].first)
        assertEquals(725457.94, outcome.charts[0].data[0].second, 0.01)
        // 模型被告知 chartIndex 与 {{chart:0}} 语法
        assertTrue(llm.calls[1].any { it.second.contains("chartIndex") && it.second.contains("{{chart:0}}") })

        // 上限 5 张（maxSteps=10 给足空间让第 6 次调用收到拒绝消息）
        val outputs = mutableListOf<String>()
        repeat(6) { i ->
            outputs.add("""{"tool":"suggest_chart","args":{"type":"bar","title":"图$i","data":[{"label":"a","value":1}]}}""")
        }
        outputs.add("""{"tool":"finish","args":{"report":"done"}}""")
        val llm2 = ScriptedLlm(outputs)
        val outcome2 = AgentLoop(llm2).run(manifest(maxSteps = 10), registry, "m", "任务", AGENT_HARNESS_CONTRACT)
        assertEquals(5, outcome2!!.charts.size, "上限 5 张")
        assertTrue(llm2.calls[5].any { it.second.contains("上限 5 张") }, "第 6 次被拒")
    }

    @Test
    fun `web_fetch——URL 校验（不真发请求）`() {
        val registry = ToolRegistry.of(listOf(webFetchTool()))
        val error = runCatching {
            registry.get("web_fetch")!!.invoke(buildJsonObject { put("url", "ftp://bad") })
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("http"), "应报 URL 格式错: ${error.message}")
        val empty = runCatching {
            registry.get("web_fetch")!!.invoke(buildJsonObject { })
        }.exceptionOrNull()!!
        assertTrue(empty.message!!.contains("不能为空"))
    }

    @Test
    fun `注册表——白名单过滤保序、重名拒绝`() {
        val two = ToolRegistry.of(listOf(probe, com.stragent.core.tools.anomaliesTool(dataset(), preferredGroup = -1)))
        assertEquals(listOf("probe", "scan_anomalies"), two.all().map { it.name })
        assertEquals(listOf("scan_anomalies"), two.resolve(listOf("scan_anomalies", "missing")).map { it.name })
        assertEquals(2, two.resolve(null).size)
        val error = runCatching { ToolRegistry.of(listOf(probe, probe)) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("工具重名"))
    }

    @Test
    fun `清单——解析往返与校验`() {
        val manifest = PluginManifest(
            id = "report-deepdive", name = "深挖", kind = "report",
            systemPrompt = "挖透它", tools = listOf("get_overview", "get_column_profile"),
            temperature = 0.5, maxSteps = 14,
        )
        val parsed = PluginManifest.parse(manifest.toJson().toString())
        assertEquals(manifest, parsed)
        // 校验：坏 kind / 缺 id / 预算越界
        assertTrue(PluginManifest.parse("""{"id":"x","kind":"review","systemPrompt":"p"}""").kind == "review")
        assertTrue(
            runCatching { PluginManifest.parse("""{"id":"x","kind":"hacker","systemPrompt":"p"}""") }.exceptionOrNull()!!.message!!.contains("未知插件类型"),
        )
        assertTrue(
            runCatching { PluginManifest.parse("""{"kind":"report","systemPrompt":"p"}""") }.exceptionOrNull()!!.message!!.contains("缺少 id"),
        )
        assertTrue(
            runCatching { PluginManifest.parse("""{"id":"x","kind":"report","systemPrompt":"p","maxSteps":999}""") }.exceptionOrNull()!!.message!!.contains("maxSteps"),
        )
    }

    private fun dataset(): Dataset {
        val text = File("../fixtures/sample/superstore.csv").readText()
        val parsed = Csv.parseDelimited(text)
        val limited = Csv.limitRows(parsed)
        val types = Csv.inferTypes(limited.rows, limited.columns.size)
        return Dataset(
            name = "superstore.csv", importedAt = 1,
            columns = limited.columns, rows = limited.rows, columnTypes = types,
            delimiter = limited.delimiter, truncated = limited.truncated, sourceRows = limited.totalRows,
        )
    }

    @Test
    fun `工具适配——列名解析与 JSON 形状（superstore）`() {
        val ds = dataset()
        val brief = com.stragent.core.brief.buildDatasetBrief(ds)
        val tools = buildAgentTools(ds, brief, search = null)
        val registry = ToolRegistry.of(tools)
        // 15 个分析工具 + 目录不含 finish（finish 由 loop 内建）
        assertTrue(registry.all().size >= 14)
        // 概况：金标密度与行列数
        val overview = registry.get("get_overview")!!.invoke(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(9994, overview["rows"]?.toString()?.toInt())
        // superstore 夹具无空单元格（脏值也是非空串）→ 密度 1.0
        assertEquals(1.0, overview["density"].toString().toDoubleOrNull()!!, 1e-9)
        // 分组聚合：列名解析 + West 合计金标
        val west = registry.get("group_aggregate")!!.invoke(
            buildJsonObject {
                put("dimension", "Region")
                put("metric", "Sales")
                put("mode", "sum")
                put("limit", 50)
            },
        )
        val westJson = west.toString()
        println("WEST_JSON=$westJson")
        assertTrue(westJson.contains("West") && westJson.contains("725457.94"), westJson.take(300))
        // 列名错误：附全部列名
        val error = runCatching {
            registry.get("group_aggregate")!!.invoke(
                buildJsonObject { put("dimension", "不存在"); put("metric", "Sales") },
            )
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("列不存在: 不存在") && error.message!!.contains("Ship Mode"))
        // 类型校验：数值列当维度
        val typeError = runCatching {
            registry.get("group_aggregate")!!.invoke(
                buildJsonObject { put("dimension", "Sales"); put("metric", "Profit") },
            )
        }.exceptionOrNull()!!
        assertTrue(typeError.message!!.contains("被识别为数值列"))
        // 归因链（列名 period）
        val chain = registry.get("get_attribution_chain")!!.invoke(
            buildJsonObject { put("period", "2015-09") },
        )
        assertTrue(chain.toString().contains("Consumer") || chain.toString().contains("Standard Class"))
        // 摘要段落过滤
        val context = registry.get("get_report_context")!!.invoke(
            buildJsonObject { put("query", "分组求和") },
        )
        assertTrue(context["matched"].toString().toInt() > 0)
    }
}
