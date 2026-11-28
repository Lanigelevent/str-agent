package com.stragent.core.observability

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-156（P2-15）· StructuredLogger 自动 traceId 注入。
 *
 * 日志行与 OTel 链路可关联：链路内日志自动带 traceId 字段（Jaeger 里看到慢
 * span 后能 grep 对应日志）；scope 外日志不带该字段。负控双向。
 */
class TraceScopeLogTest {

    private val captured = StringBuilder()
    private lateinit var previousSink: (String) -> Unit

    @kotlin.test.BeforeTest
    fun captureSink() {
        previousSink = StructuredLogger.globalOut()
        StructuredLogger.setGlobalOut { line -> captured.appendLine(line) }
    }

    @AfterTest
    fun restoreSink() {
        StructuredLogger.setGlobalOut(previousSink)
    }

    @Test
    fun `链路内日志自动带 traceId——scope 外不带`() {
        val logger = StructuredLogger.getLogger("t156.trace")
        val traceId = "a".repeat(32)
        TraceScope.with(OtelSpanContext(traceId = traceId, spanId = "b".repeat(16))) {
            logger.info("在链路内")
        }
        logger.info("在链路外")
        val lines = captured.toString().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "两条日志")
        assertTrue(lines[0].contains("\"traceId\":\"$traceId\""), "链路内日志带 traceId：${lines[0]}")
        assertTrue(!lines[1].contains("traceId"), "scope 外日志不带 traceId 字段：${lines[1]}")
    }

    @Test
    fun `嵌套作用域恢复——内层退出恢复外层值`() {
        val outer = OtelSpanContext("c".repeat(32), "d".repeat(16))
        val inner = OtelSpanContext("e".repeat(32), "f".repeat(16))
        TraceScope.with(outer) {
            assertEquals("c".repeat(32), TraceScope.current()!!.traceId)
            TraceScope.with(inner) {
                assertEquals("e".repeat(32), TraceScope.current()!!.traceId)
            }
            assertEquals("c".repeat(32), TraceScope.current()!!.traceId, "内层退出恢复外层")
        }
        assertNull(TraceScope.current(), "顶层退出恢复 null")
        assertEquals(0, captured.length, "本用例不写日志")
    }
}
