package com.stragent.bench.suites

import com.stragent.core.tools.buildAnalysisTools
import com.stragent.bench.fixtures.MapPrefs
import com.stragent.bench.fixtures.findFixtures
import com.stragent.bench.model.SuiteResult
import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.stats.datasetOf
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 套件四 · tool-surface（工具自描述完备性 / MCP 暴露就绪度）
 *
 * 这是 D10 阶段 4「全 17 工具经 MCP 暴露」的前置量化：评估现有策展工具是否
 * 每个都带有 name / description / JSON-Schema 参数，从而可被自动映射为 MCP tool。
 * 复用 `buildAnalysisTools` 作为工具清单的单一事实源（杜绝手抄漂移）。
 *
 * D11 v3 说明：数据库/导出/统计计算等新工具注册在 host 模块，bench 不依赖 host，
 * **看不到它们的真实注册状态**。此前这里抄了一份「D11 工具名清单」做前缀/唯一性检查，
 * 但那份清单既检查不了注册（自证式断言永远为真），又已与实际名字漂移
 * （如写成 `stragent_regression_analysis`，真实为 `stragent_regression`）——已删除。
 * 工具面的真实口径 = host 运行时 `tools/list`（`--list-tools`，见 docs/mcp-tool-surface.md）。
 *
 * 评分：self-describability = 自带完整元数据的工具数 / 工具总数；
 * 名称唯一性作为硬约束（重复即失败）。
 */
fun runToolSurface(): SuiteResult {
    val fx = findFixtures()
    val sampleDir = File(fx, "sample").absolutePath
    val ds = datasetOf("superstore", sampleDir)
    val brief = buildDatasetBrief(ds, MapPrefs())
    val tools = buildAnalysisTools(ds, brief)

    val duplicated = tools.map { it.name }.groupBy { it }.filter { it.value.size > 1 }.keys
    val valid = tools.count { t ->
        t.name.isNotBlank() &&
            t.description.isNotBlank() &&
            (t.parametersSchema["type"] as? JsonPrimitive)?.content == "object"
    }

    // D11 v3 新增工具注册在 host 模块，bench 不依赖 host —— 这里不再做「自证式」命名检查，
    // 真实注册面由 host 运行时 tools/list 验证（见 docs/mcp-tool-surface.md）。

    val failures = mutableListOf<String>()
    if (duplicated.isNotEmpty()) failures.add("工具名重复: ${duplicated.joinToString()}")
    val expectedMin = 16
    if (tools.size < expectedMin) failures.add("工具总数 ${tools.size} < $expectedMin")

    return SuiteResult(
        name = "tool-surface",
        passed = valid,
        // 只统计**真正被检查过元数据**的工具；host 侧注册的 D11 工具不在此处分母
        // （此前把仅做命名检查的 13 个名字算进 total，导致 17/30 的假失败）。
        total = tools.size,
        failures = failures,
        notes = "策展工具自描述完备性（name/description/JSON-Schema 齐全即可自动映射 MCP tool）。" +
            "当前 ${tools.size} 个策展工具，自描述率 ${if (tools.isEmpty()) 100 else valid * 100 / tools.size}%。" +
            "D11/host 工具的真实注册面以运行时 tools/list 为准（bench 不依赖 host，不在此断言）。",
    )
}
