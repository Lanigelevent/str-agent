package com.stragent.core.eval

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent 执行评估器 —— D11 v3 S7 评估体系。
 *
 * 记录每次 Agent 执行的完整轨迹（工具调用序列、中间结果、耗时），
 * 生成评估报告。借鉴 AgentBench 的交互式多轮评估理念。
 *
 * 纯 Kotlin、零依赖、确定性——与溯源清单纪律兼容。
 */
internal data class ToolCallRecord(
    val tool: String,
    val args: JsonObject,
    val result: JsonObject?,
    val timestamp: Long,
    val durationMs: Long,
    val error: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tool", tool)
        put("args", args)
        result?.let { put("result", it) }
        put("timestamp", timestamp)
        put("durationMs", durationMs)
        error?.let { put("error", it) }
    }
}

internal data class EvaluationReport(
    val totalCalls: Int,
    val totalDurationMs: Long,
    val tools: List<String>,
    val failureModes: List<FailureMode>,
    val efficiency: EfficiencyMetrics,
    val trace: List<ToolCallRecord>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("totalCalls", totalCalls)
        put("totalDurationMs", totalDurationMs)
        put("tools", kotlinx.serialization.json.JsonArray(tools.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("failureModes", kotlinx.serialization.json.JsonArray(failureModes.map { kotlinx.serialization.json.JsonPrimitive(it.id) }))
        put("efficiency", efficiency.toJson())
        put("trace", kotlinx.serialization.json.JsonArray(trace.map { it.toJson() }))
    }
}

internal data class EfficiencyMetrics(
    val callsPerSecond: Double,
    val avgCallDurationMs: Double,
    val uniqueTools: Int,
    val duplicateCalls: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("callsPerSecond", String.format("%.2f", callsPerSecond))
        put("avgCallDurationMs", avgCallDurationMs.toLong())
        put("uniqueTools", uniqueTools)
        put("duplicateCalls", duplicateCalls)
    }
}

/**
 * 评估器：从工具调用轨迹生成评估报告。
 */
internal object AgentEvaluator {

    fun evaluate(
        trace: List<ToolCallRecord>,
        expected: JsonElement? = null,
        timeoutMs: Long = 30_000,
        maxSteps: Int = 20,
    ): EvaluationReport {
        val totalDuration = trace.sumOf { it.durationMs }
        val tools = trace.map { it.tool }
        val failureModes = FailureModeDetector.detect(trace, expected, timeoutMs, maxSteps)
        val uniqueTools = tools.distinct().size
        val duplicateCalls = tools.size - uniqueTools
        val callsPerSecond = if (totalDuration > 0) trace.size * 1000.0 / totalDuration else 0.0
        val avgDuration = if (trace.isNotEmpty()) totalDuration.toDouble() / trace.size else 0.0

        return EvaluationReport(
            totalCalls = trace.size,
            totalDurationMs = totalDuration,
            tools = tools,
            failureModes = failureModes,
            efficiency = EfficiencyMetrics(
                callsPerSecond = callsPerSecond,
                avgCallDurationMs = avgDuration,
                uniqueTools = uniqueTools,
                duplicateCalls = duplicateCalls,
            ),
            trace = trace,
        )
    }
}
