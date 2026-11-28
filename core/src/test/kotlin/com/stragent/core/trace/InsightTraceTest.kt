package com.stragent.core.trace

import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelSpanContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * InsightTrace 数据结构与序列化（D10 阶段 0 可观测性）的离线单元测试。
 * 不涉及 LLM：只验证「录制 → 落盘」链路的形态正确性。
 *
 * T-056 增补：span 树（阶段区间 / 父子关系 / GenAI 语义 / 可选成本 / 上游 traceparent）。
 */
class InsightTraceTest {
    @Test
    fun `mark + finish + toJson 基本结构`() {
        val trace = InsightTrace("sales.csv", 1000L)
        trace.mark("analyzing", null)
        trace.mark("agent", "EditorialDept")
        trace.finish("editorial-v1", 1234, true)

        val json = trace.toJson()
        assertEquals("sales.csv", json["dataset"]?.jsonPrimitive?.content)
        assertEquals("editorial-v1", json["engineId"]?.jsonPrimitive?.content)
        assertEquals(1234, json["totalTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(true, json["autonomous"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(2, json["spans"]?.jsonArray?.size)
        assertTrue(trace.durationMs() >= 0)
    }

    @Test
    fun `未 finish 时 durationMs 为 0 而非负数`() {
        val t = InsightTrace("x", 0L)
        // 未调用 finish：finishedAtMs 仍为 0，durationMs 不应为负
        assertEquals(0, t.durationMs())
    }

    @Test
    fun `时长测量走单调时钟——亚刻度（~3ms）时长如实记录而非量化为 0（T-081）`() {
        val trace = InsightTrace("d", System.currentTimeMillis())
        // 忙等 ~3ms（< Windows 时钟粒度 15.6ms）：墙钟差值会量化成 0，
        // 单调时钟差值应如实给出 ~3ms——这是新旧实现的判别性断言
        val deadline = System.nanoTime() + 3_000_000L
        while (System.nanoTime() < deadline) {
            Thread.yield()
        }
        trace.finish("classic", 10, autonomous = false)
        assertTrue(trace.durationMs() >= 2, "亚刻度时长应被单调时钟如实记录：${trace.durationMs()}")
        assertTrue(trace.finishedAtMs >= trace.startedAtMs, "墙钟字段仍保留展示/审计语义且单调合理")
    }

    @Test
    fun `spans 保留阶段顺序与 detail`() {
        val t = InsightTrace("d", 0L)
        t.mark("a", "first")
        t.mark("b", "second")
        val spans = t.toJson()["spans"]!!.jsonArray
        assertEquals("a", spans[0].jsonObject["stage"]?.jsonPrimitive?.content)
        assertEquals("first", spans[0].jsonObject["detail"]?.jsonPrimitive?.content)
        assertEquals("b", spans[1].jsonObject["stage"]?.jsonPrimitive?.content)
    }

    // ---------------- T-056：OTel span 树 ----------------

    @Test
    fun `阶段 span 是根的子节点且区间不重叠、按 mark 顺序收口`() {
        val t = InsightTrace("sales.csv", 1000L)
        t.mark("analyzing", "开始")
        Thread.sleep(5)
        t.mark("writing", "写正文")
        Thread.sleep(5)
        t.finish("editorial-v1", 100, true)

        val spans = t.otelSpans()
        assertEquals(3, spans.size, "2 个阶段 + 1 个根")
        val root = spans.last()
        assertEquals("report.generate", root.name)
        assertNull(root.parentSpanId)
        assertEquals(OtelSpan.Kind.INTERNAL, root.kind)
        assertEquals(t.traceId, root.traceId)

        val stages = spans.dropLast(1)
        assertEquals(listOf("report.stage:analyzing", "report.stage:writing"), stages.map { it.name })
        stages.forEach { stage ->
            assertEquals(root.spanId, stage.parentSpanId, "阶段 span 必须挂在报告根下")
            assertEquals(root.traceId, stage.traceId)
            assertEquals(stage.stageOf(), stage.attributesSnapshot()["str_agent.stage"])
            assertTrue(stage.startedAtNanos >= root.startedAtNanos)
        }
        assertTrue(stages[0].endedAtNanos!! <= stages[1].startedAtNanos, "前一阶段在下一阶段开始时收口")
        assertTrue(stages[1].endedAtNanos!! <= root.endedAtNanos!!, "根在所有阶段之后结束")
        assertEquals("开始", stages[0].attributesSnapshot()["str_agent.stage.detail"])
    }

    /** 阶段名是 `report.stage:<stage>`，从名字还原 stage 便于断言 */
    private fun OtelSpan.stageOf(): String = name.substringAfter("report.stage:")

    @Test
    fun `根 span 携带 gen_ai 语义与 token；未配置单价不写成本`() {
        val t = InsightTrace("d", 0L)
        t.mark("a", null)
        t.finish("editorial-v1", 1234, true)
        val root = t.otelSpans().last()
        val attrs = root.attributesSnapshot()

        assertEquals("generate", attrs["gen_ai.operation.name"])
        assertEquals("str_agent", attrs["gen_ai.system"])
        assertEquals(1234L, attrs["gen_ai.usage.total_tokens"])
        assertEquals("editorial-v1", attrs["str_agent.engine.id"])
        assertEquals(true, attrs["str_agent.autonomous"])
        assertEquals(1L, attrs["str_agent.stages"])
        assertEquals(OtelSpan.Status.OK, root.status)
        assertTrue(root.endedAtNanos != null, "finish 必须收口根 span——否则导出的是半截区间")

        assertFalse(attrs.containsKey("str_agent.cost.usd"), "未配置单价不得写成本（0 也是编造）")
        assertFalse(attrs.containsKey("str_agent.cost.basis"))
        // 只拿得到 total_tokens：不得编造输入/输出拆分
        assertFalse(attrs.containsKey("gen_ai.usage.input_tokens"))
        assertFalse(attrs.containsKey("gen_ai.usage.output_tokens"))
    }

    @Test
    fun `配置单价时成本按 totalTokens 计算并标注口径`() {
        val t = InsightTrace("d", 0L, costPer1kTokens = 0.02)
        t.finish("x", 1500, false)
        val attrs = t.otelSpans().last().attributesSnapshot()
        assertEquals(0.03, attrs["str_agent.cost.usd"])
        assertEquals("configured price per 1K tokens", attrs["str_agent.cost.basis"])
    }

    @Test
    fun `上游 traceparent 决定 traceId 与父 span，并写进 JSON`() {
        val parent = OtelSpanContext("1".repeat(32), "2".repeat(16), sampled = true)
        val t = InsightTrace("d", 0L, parent = parent)
        t.mark("a", null)
        t.finish("x", 1, true)

        assertEquals(parent.traceId, t.traceId)
        val root = t.otelSpans().last()
        assertEquals("2".repeat(16), root.parentSpanId)

        val json = t.toJson()
        assertEquals(parent.traceId, json["traceId"]!!.jsonPrimitive.content)
        assertEquals(root.spanId, json["rootSpanId"]!!.jsonPrimitive.content)
        assertEquals(parent.spanId, json["parentSpanId"]!!.jsonPrimitive.content)
        // 阶段 span 也在这条 trace 上
        assertTrue(t.otelSpans().all { it.traceId == parent.traceId })
    }

    @Test
    fun `无上游时 JSON 不写 parentSpanId，traceId 自生成`() {
        val t = InsightTrace("d", 0L)
        t.finish("x", 0, false)
        val json = t.toJson()
        assertEquals(32, json["traceId"]!!.jsonPrimitive.content.length)
        assertNull(json["parentSpanId"])
    }

    @Test
    fun `未 finish 的 trace 不导出根 span 的结束时间——保持未收口`() {
        val t = InsightTrace("d", 0L)
        t.mark("a", null)
        val root = t.otelSpans().last()
        assertNull(root.endedAtNanos)
        assertEquals(OtelSpan.Status.UNSET, root.status)
    }
}
