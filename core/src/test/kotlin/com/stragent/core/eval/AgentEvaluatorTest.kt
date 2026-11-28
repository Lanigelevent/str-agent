package com.stragent.core.eval

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * S7 评估体系测试（D11 v3）。
 *
 * 验证维度：
 *  1. AgentEvaluator 生成正确评估报告
 *  2. FailureModeDetector 检测六种失败模式
 *  3. 效率指标正确
 *  4. 确定性——同一轨迹产出同一报告
 */
class AgentEvaluatorTest {

    private fun call(tool: String, durationMs: Long = 100, error: String? = null): ToolCallRecord =
        ToolCallRecord(
            tool = tool,
            args = buildJsonObject { put("param", "value") },
            result = buildJsonObject { put("ok", true) },
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            error = error,
        )

    @Test
    fun `正常轨迹无失败模式`() {
        val trace = listOf(
            call("stragent_import_table", 50),
            call("stragent_render_chart", 80),
            call("stragent_provenance", 30),
        )
        val report = AgentEvaluator.evaluate(trace)
        assertEquals(3, report.totalCalls)
        assertEquals(160, report.totalDurationMs)
        assertTrue(report.failureModes.isEmpty(), "正常轨迹不应有失败模式")
    }

    @Test
    fun `低效路径检测`() {
        val trace = (1..25).map { call("tool_$it") }
        val report = AgentEvaluator.evaluate(trace, maxSteps = 20)
        assertTrue(report.failureModes.contains(FailureMode.INEFFICIENT_PATH), "25 步应触发低效路径")
    }

    @Test
    fun `工具误用检测`() {
        val trace = listOf(
            call("stragent_import_table", 50),
            call("stragent_render_chart", 80, error = "参数缺失：valueColumn"),
        )
        val report = AgentEvaluator.evaluate(trace)
        assertTrue(report.failureModes.contains(FailureMode.TOOL_MISUSE), "有错误应触发工具误用")
    }

    @Test
    fun `无限循环检测`() {
        val args = buildJsonObject { put("x", "1") }
        val trace = (1..3).map {
            ToolCallRecord("stragent_render_chart", args, buildJsonObject { put("ok", true) }, System.currentTimeMillis(), 50)
        }
        val report = AgentEvaluator.evaluate(trace)
        assertTrue(report.failureModes.contains(FailureMode.INFINITE_LOOP), "3 次相同调用应触发无限循环")
    }

    @Test
    fun `超时检测`() {
        val trace = listOf(
            call("tool_a", 20_000),
            call("tool_b", 15_000),
        )
        val report = AgentEvaluator.evaluate(trace, timeoutMs = 30_000)
        assertTrue(report.failureModes.contains(FailureMode.TIMEOUT), "35s 应触发超时")
    }

    @Test
    fun `WRONG_ANSWER 检测`() {
        val actual = JsonPrimitive("42")
        val expected = JsonPrimitive("100")
        val mode = FailureModeDetector.checkWrongAnswer(actual, expected)
        assertEquals(FailureMode.WRONG_ANSWER, mode)
    }

    @Test
    fun `正确答案不触发 WRONG_ANSWER`() {
        val val1 = JsonPrimitive("42")
        val mode = FailureModeDetector.checkWrongAnswer(val1, val1)
        assertEquals(null, mode, "相同值不应触发 WRONG_ANSWER")
    }

    @Test
    fun `效率指标正确`() {
        val trace = listOf(
            call("tool_a", 100),
            call("tool_a", 200),
            call("tool_b", 300),
        )
        val report = AgentEvaluator.evaluate(trace)
        assertEquals(2, report.efficiency.uniqueTools, "应有 2 个不同工具")
        assertEquals(1, report.efficiency.duplicateCalls, "应有 1 次重复调用")
        assertEquals(200.0, report.efficiency.avgCallDurationMs, 0.01, "平均耗时 200ms")
        assertTrue(report.efficiency.callsPerSecond > 0, "每秒调用数应 > 0")
    }

    @Test
    fun `确定性_同一轨迹同一报告`() {
        val trace = listOf(call("tool_a", 100), call("tool_b", 200))
        val report1 = AgentEvaluator.evaluate(trace)
        val report2 = AgentEvaluator.evaluate(trace)
        assertEquals(report1.totalCalls, report2.totalCalls)
        assertEquals(report1.totalDurationMs, report2.totalDurationMs)
        assertEquals(report1.failureModes, report2.failureModes)
        assertEquals(report1.efficiency.uniqueTools, report2.efficiency.uniqueTools)
    }

    @Test
    fun `空轨迹不报错`() {
        val report = AgentEvaluator.evaluate(emptyList())
        assertEquals(0, report.totalCalls)
        assertTrue(report.failureModes.isEmpty())
    }

    @Test
    fun `EvaluationReport 序列化为 JSON`() {
        val trace = listOf(call("tool_a", 100))
        val report = AgentEvaluator.evaluate(trace)
        val json = report.toJson()
        assertNotNull(json["totalCalls"])
        assertNotNull(json["efficiency"])
        assertNotNull(json["trace"])
    }
}
