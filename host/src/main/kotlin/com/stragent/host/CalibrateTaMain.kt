package com.stragent.host

import com.stragent.core.config.Config
import com.stragent.core.report.TaSample
import com.stragent.core.report.humanWritingBlock
import com.stragent.core.report.medianRatio
import com.stragent.core.report.parseTaFactor
import com.stragent.core.report.recommendFactor
import com.stragent.data.HttpLlmClient
import com.stragent.data.StepfunConfig
import java.io.File

/**
 * TA 校准器（T-109）：真实后端测「字数目标→实际产出」的依从曲线，推荐 STRAGENT_LENGTH_TA 因子。
 *
 * 协议（Retkowski & Waibel arXiv:2501.00233 的 Target Adjustment 实测版）：
 *  - 档位 [300, 500, 800, 1200, 2000, 3500] 字 × 每档 3 样本（STRAGENT_TA_SAMPLES 可调 1-5）；
 *  - 提示词与 TextReview 分章路径同形（role/task + zero_fabrication + 去 AI 味块 + 要点清单 + 字数上限）；
 *  - 实测口径与 TextReview 的成文字数同源（去 markdown 符号与空白）；
 *  - 判据：全档中位比 ≥0.8 = 依从足够（TA 保持关闭）；<0.8 = 推荐因子 1/ratio（夹 [1.0, 1.5]）。
 *
 * 运行（不进 CI，与 realLlmTest 同 opt-in 纪律）：
 *   STRAGENT_REAL_LLM_KEY=... ./gradlew :host:calibrateTA
 * 结果落 build/reports/ta-calibration.md；无 Key 时打印指引并以 0 退出（手动任务，SKIP 不假绿）。
 */
private class MemoryPrefs : com.stragent.core.ports.PrefsStore {
    val m = mutableMapOf<String, String>()
    override fun get(key: String, fallback: String): String = m[key] ?: fallback
    override fun set(key: String, value: String) {
        m[key] = value
    }
}

/** 与 TextReview.countReportChars 同口径（成文字数：去 markdown 符号与全部空白） */
private fun reportChars(report: String): Int =
    report.replace(Regex("[#*`>|-]"), "").replace(Regex("\\s"), "").length

private val BANDS = listOf(300, 500, 800, 1200, 2000, 3500)

private val POINTS_FIXTURE = """
    1. 监测周期覆盖连续观测期，记录口径由汇总表逐格计数得到（quote：记录口径由汇总表逐格计数；段 3） // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
    2. 报警值出自投标阶段方案，未见设计方确认，效力前提已声明（quote：未见设计方确认；段 8） // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
    3. 数据可靠性分级将口径缺口项目单列，仅供量级参考（quote：仅供量级参考；段 12） // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
    4. 建议按优先级排序：先补口径缺口，再补资料缺项（quote：先补口径缺口；段 21） // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
""".trimIndent()

fun main() {
    val apiKey = Config.get("STRAGENT_REAL_LLM_KEY").orEmpty().trim()
    if (apiKey.isEmpty()) {
        println(
            "TA 校准需要真实后端 Key：STRAGENT_REAL_LLM_KEY=... ./gradlew :host:calibrateTA " + // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
                "（可选 STRAGENT_REAL_LLM_MODEL / STRAGENT_REAL_LLM_BASE_URL / STRAGENT_TA_SAMPLES）", // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        )
        return
    }
    val model = Config.get("STRAGENT_REAL_LLM_MODEL")?.trim()?.takeIf { it.isNotEmpty() } ?: "deepseek-flash"
    val baseUrl = Config.get("STRAGENT_REAL_LLM_BASE_URL")?.trim()?.takeIf { it.isNotEmpty() }
    val samplesPerBand = (Config.get("STRAGENT_TA_SAMPLES")?.toIntOrNull() ?: 3).coerceIn(1, 5)

    val prefs = MemoryPrefs()
    prefs.set(StepfunConfig.VENDOR_STORAGE, "deepseek")
    prefs.set(StepfunConfig.CUSTOM_MODEL_STORAGE, model)
    prefs.set(StepfunConfig.KEY_STORAGE, apiKey)
    val client = HttpLlmClient(prefs, baseUrlOverride = baseUrl)

    // 最小握手（与 realLlmTest 同纪律）
    val probe = client.chat(model, listOf("user" to "Reply with OK only."), 0.0, 512)
    check(probe.text.isNotBlank()) { "真实后端握手失败：content 为空" } // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
    println("[ta-calibration] handshake ok: model=$model tokens=${probe.totalTokens}")

    val system = buildString {
        append("<role>你是资深数据分析师，正在为一份「材料」撰写综述报告。</role>\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        append("<task>只基于给定要点撰写；每个关键结论标注出处（段 N）。</task>\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        append(humanWritingBlock())
        append("\n<zero_fabrication>数字只许来自要点清单；要点没有覆盖的内容不要写。</zero_fabrication>") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
    }

    val rows = mutableListOf<String>()
    val samples = mutableListOf<TaSample>()
    for (band in BANDS) {
        repeat(samplesPerBand) { si ->
            val user = buildString {
                append("要点清单（quote 逐字摘自原文）：\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
                append(POINTS_FIXTURE)
                append("\n\n本章只写「综合概述」部分。\n\n本章字数上限：约 ").append(band).append(" 字。输出本章正文。") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
            }
            val result = client.chat(
                model,
                listOf("system" to system, "user" to user),
                0.7,
                (band * 3).coerceAtLeast(900),
            )
            val actual = reportChars(result.text)
            samples.add(TaSample(band, actual))
            rows += "| $band | ${si + 1} | $actual | ${"%.2f".format(actual.toDouble() / band)} | ${result.totalTokens} |"
            println("[ta-calibration] band=$band sample=${si + 1} actual=$actual ratio=${"%.2f".format(actual.toDouble() / band)}")
        }
    }

    val ratio = medianRatio(samples)
    val factor = recommendFactor(ratio)
    val current = parseTaFactor(Config.get("STRAGENT_LENGTH_TA"))
    val decision = buildString {
        append("# TA 校准报告（T-109）\n\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        append("- model: $model\n- bands: $BANDS × $samplesPerBand 样本\n- 当前 STRAGENT_LENGTH_TA: ${current ?: "关闭"}\n\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        append("| 目标字数 | 样本 | 实测字数 | 实测比 | tokens |\n|---|---|---|---|---|\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        rows.forEach { append(it).append('\n') }
        append("\n- 全档中位比：").append("%.3f".format(ratio)).append('\n') // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        if (factor <= 1.0) {
            append("- 判定：中位比 ≥ 0.8，长度依从足够——**TA 保持关闭**（STRAGENT_LENGTH_TA 不设置）。\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        } else {
            append("- 判定：中位比 < 0.8，存在系统性欠产——**建议 STRAGENT_LENGTH_TA=").append("%.2f".format(factor)) // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
                .append("**（1/中位比，夹 [1.0, 1.5]）。\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
        }
        append("- 复核口径：换模型/换服务商后重跑；样本量不足时先加 STRAGENT_TA_SAMPLES。\n") // lint:allow zh-first（用户面中文输出/校准报告，D19/D21 语言定位）
    }
    val out = File("build/reports/ta-calibration.md")
    out.parentFile.mkdirs()
    out.writeText(decision, Charsets.UTF_8)
    println(decision)
    println("[ta-calibration] written: ${out.absolutePath}")
}
