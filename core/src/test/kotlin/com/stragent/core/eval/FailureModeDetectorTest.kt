package com.stragent.core.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-160 · FailureModeDetector 直测（D25 Q7 覆盖率盲区收口：此前六模式主干仅经
 * AgentEvaluator 间接行使，六模式各自的触发/不触发从未直接钉住）。
 */
class FailureModeDetectorTest {

    private fun call(tool: String, args: String = "{}", durationMs: Long = 10, error: String? = null) =
        ToolCallRecord(
            tool = tool,
            args = Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject,
            result = null,
            timestamp = 0L,
            durationMs = durationMs,
            error = error,
        )

    @Test
    fun `干净轨迹——六模式全不触发`() {
        val trace = listOf(call("get_overview"), call("get_column_profile", """{"column":"x"}"""))
        assertTrue(FailureModeDetector.detect(trace).isEmpty(), "无错误/无超步/无重复/无超时应零模式")
    }

    @Test
    fun `低效路径——步数超上限触发`() {
        val trace = (1..21).map { call("t$it") }
        val modes = FailureModeDetector.detect(trace, maxSteps = 20)
        assertTrue(FailureMode.INEFFICIENT_PATH in modes)
    }

    @Test
    fun `低效路径——步数未超不触发（上界语义是大于而非大于等于）`() {
        val trace = (1..20).map { call("t$it") }
        assertTrue(FailureMode.INEFFICIENT_PATH !in FailureModeDetector.detect(trace, maxSteps = 20))
    }

    @Test
    fun `工具误用——任一调用报错即触发`() {
        val modes = FailureModeDetector.detect(listOf(call("ok"), call("bad", error = "列不存在")))
        assertTrue(FailureMode.TOOL_MISUSE in modes)
    }

    @Test
    fun `无限循环——同工具同参数三次触发，两次不触发`() {
        val same = call("get_overview", "{}")
        assertTrue(FailureMode.INFINITE_LOOP in FailureModeDetector.detect(listOf(same, same, same)))
        assertTrue(
            FailureMode.INFINITE_LOOP !in FailureModeDetector.detect(listOf(same, same)),
            "两次同参调用不构成循环",
        )
    }

    @Test
    fun `超时——总耗时超阈值触发（求和口径）`() {
        val trace = listOf(call("a", durationMs = 20_000), call("b", durationMs = 20_000))
        assertTrue(FailureMode.TIMEOUT in FailureModeDetector.detect(trace, timeoutMs = 30_000))
    }

    @Test
    fun `checkWrongAnswer——不一致报 WRONG_ANSWER，一致与缺预期均 null`() {
        val actual = Json.parseToJsonElement("""{"report":"A"}""")
        assertEquals(
            FailureMode.WRONG_ANSWER,
            FailureModeDetector.checkWrongAnswer(actual, Json.parseToJsonElement("""{"report":"B"}""")),
        )
        assertNull(FailureModeDetector.checkWrongAnswer(actual, actual))
        assertNull(FailureModeDetector.checkWrongAnswer(actual, null))
        assertNull(FailureModeDetector.checkWrongAnswer(null, actual))
    }
}
