package com.stragent.core.eval

import kotlinx.serialization.json.JsonElement

/**
 * 六失败模式检测器 —— D11 v3 S7 评估体系。
 *
 * 六种失败模式（2026 年行业调研）：
 *  1. WRONG_ANSWER：最终结果与预期不符（可自动检测，需预期值）
 *  2. RIGHT_ANSWER_WRONG_REASONING：正确答案但推理错误（需 LLM-as-Judge，可选）
 *  3. INEFFICIENT_PATH：步数/token 超过同类任务 P95（可自动检测）
 *  4. TOOL_MISUSE：工具参数缺失/类型错误/调用不存在工具（可自动检测）
 *  5. INFINITE_LOOP：同一工具+参数重复调用 ≥3 次（可自动检测）
 *  6. TIMEOUT：总耗时超过阈值（可自动检测）
 *
 * 纯 Kotlin、零依赖、确定性。
 */
internal enum class FailureMode(val id: String, val label: String, val severity: String) {
    WRONG_ANSWER("wrong_answer", "错误答案", "critical"),
    RIGHT_ANSWER_WRONG_REASONING("right_answer_wrong_reasoning", "正确答案错误推理", "warning"),
    INEFFICIENT_PATH("inefficient_path", "低效路径", "warning"),
    TOOL_MISUSE("tool_misuse", "工具误用", "error"),
    INFINITE_LOOP("infinite_loop", "无限循环", "critical"),
    TIMEOUT("timeout", "超时", "error"),
}

internal object FailureModeDetector {

    /** 默认效率阈值（步数上限） */
    private const val DEFAULT_MAX_STEPS = 20

    /** 默认重复调用阈值（同一工具+参数 ≥ 此值 = 无限循环） */
    private const val DEFAULT_LOOP_THRESHOLD = 3

    /**
     * 检测所有失败模式。
     *
     * @param trace 工具调用轨迹
     * @param expected 预期结果（null = 不检测 WRONG_ANSWER）
     * @param timeoutMs 超时阈值（默认 30s）
     * @param maxSteps 步数上限（默认 20）
     */
    fun detect(
        trace: List<ToolCallRecord>,
        expected: JsonElement? = null,
        timeoutMs: Long = 30_000,
        maxSteps: Int = DEFAULT_MAX_STEPS,
    ): List<FailureMode> {
        val modes = mutableListOf<FailureMode>()

        // 1. WRONG_ANSWER（需要预期值）
        // 需要从 trace 中提取最终结果与 expected 比对
        // 由于最终结果在报告中而非工具返回值中，此处仅检测工具错误
        // 完整的 WRONG_ANSWER 检测需要报告级评估

        // 2. RIGHT_ANSWER_WRONG_REASONING（需要 LLM-as-Judge）
        // 此模式不在此自动检测，标注为可选

        // 3. INEFFICIENT_PATH
        if (trace.size > maxSteps) {
            modes.add(FailureMode.INEFFICIENT_PATH)
        }

        // 4. TOOL_MISUSE
        for (call in trace) {
            if (call.error != null) {
                modes.add(FailureMode.TOOL_MISUSE)
                break
            }
        }

        // 5. INFINITE_LOOP：同一工具+参数重复 ≥3 次
        val callSignatures = trace.map { "${it.tool}:${it.args}" }
        val duplicates = callSignatures.groupingBy { it }.eachCount().values.any { it >= DEFAULT_LOOP_THRESHOLD }
        if (duplicates) {
            modes.add(FailureMode.INFINITE_LOOP)
        }

        // 6. TIMEOUT
        val totalDuration = trace.sumOf { it.durationMs }
        if (totalDuration > timeoutMs) {
            modes.add(FailureMode.TIMEOUT)
        }

        return modes
    }

    /**
     * 检测 WRONG_ANSWER（需预期值和实际值）。
     */
    fun checkWrongAnswer(actual: JsonElement?, expected: JsonElement?): FailureMode? {
        if (expected == null || actual == null) return null
        return if (actual.toString() != expected.toString()) FailureMode.WRONG_ANSWER else null
    }
}
