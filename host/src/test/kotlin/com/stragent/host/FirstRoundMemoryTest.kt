package com.stragent.host

import com.stragent.data.abilityTools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-028 · 记忆进**首轮报告**（宿主层端到端）。
 *
 * T-008 只给了追问路径记忆；首轮报告此前是「写了也白写」——同一份数据换个会话再出报告，
 * 模型看不见上一次的结论。本测试证明的**用户能感知的那件事**：
 * 上一轮报告的结论原文进了本轮生成提示词，且其数字进不了 `<allowed_numbers>`（纪律 a）。
 */
class FirstRoundMemoryTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-t028-${System.nanoTime()}").also { it.mkdirs() }

    private fun bootstrap(home: File, llm: FakeLlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(home, llm = llm)

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        (abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory))
            .first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv) }
        return (tool(b, "stragent_import_table").invoke(args)["datasetId"] as JsonPrimitive).content
    }

    private fun report(b: StrAgentHostBootstrap, datasetId: String): JsonObject {
        val args = buildJsonObject { put("datasetId", datasetId) }
        return tool(b, "stragent_generate_report").invoke(args)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private fun JsonObject.int(key: String): Int = str(key).toInt()

    private val csvA = "地区,销售额\n华东,100\n华南,80\n华北,60\n"
    private val csvB = "地区,销售额\n华东,120\n华南,90\n"

    /** 上一轮报告里带一个本轮数据里不存在的数字 45.3（正向对照 + 白名单隔离都用它） */
    private val priorReport = "## 总体概览\n\n华北销售额 45.3 万，占比最低。\n"

    @Test
    fun `同指纹新会话出报告——上一轮报告结论进提示词且数字进不了白名单`() {
        val home = tempHome()
        // 会话 1：出报告（报告产出自动留记忆，T-008 已有行为）
        bootstrap(home, FakeLlmClient(report = priorReport)).use { b1 ->
            val id = import(b1, csvA)
            report(b1, id)
        }
        // 会话 2：同 home 同 csv = 内容指纹一致；全新 bootstrap = 新进程
        val llm2 = FakeLlmClient()
        bootstrap(home, llm2).use { b2 ->
            val id2 = import(b2, csvA)
            val result = report(b2, id2)
            assertEquals(1, result.int("memoryFactsUsed"), "首轮报告必须召回上一轮报告：$result")
            // 报告路径有两次模型调用：生成在前、语义审查在后；取生成那次
            val prompt = llm2.prompts.first()
            assertTrue(prompt.contains("<session_memory>"), "记忆块要进提示词")
            assertTrue(prompt.contains("45.3"), "上一轮报告的结论原文（45.3）必须进提示词，否则本轮等于重写")
            // 纪律 a 反向验证：上轮报告的数字是上一轮模型写的，不是本次统计——进不了白名单
            val allowed = prompt.substringAfter("<allowed_numbers>", "").substringBefore("</allowed_numbers>")
            assertTrue(allowed.isNotEmpty(), "夹具 brief 应带可引用数字白名单")
            assertFalse(allowed.contains("45.3"), "上轮报告数字混进白名单 = 上轮随口结论变成可引用统计值")
        }
    }

    @Test
    fun `内容指纹不同不互召——首轮报告不带别的数据集的记忆`() {
        val home = tempHome()
        bootstrap(home, FakeLlmClient(report = priorReport)).use { b1 ->
            val id = import(b1, csvA)
            report(b1, id)
        }
        val llm2 = FakeLlmClient()
        bootstrap(home, llm2).use { b2 ->
            val otherId = import(b2, csvB)
            val result = report(b2, otherId)
            assertEquals(0, result.int("memoryFactsUsed"), "不同内容指纹不得互召：$result")
            assertFalse(llm2.prompts.first().contains("<session_memory>"))
        }
    }

    @Test
    fun `无记忆时回执字段归零且提示词与改动前同形`() {
        val llm = FakeLlmClient()
        bootstrap(tempHome(), llm).use { b ->
            val id = import(b, csvA)
            val result = report(b, id)
            assertEquals(0, result.int("memoryFactsUsed"))
            assertFalse(llm.prompts.first().contains("<session_memory>"), "无记忆不得留空块占篇幅")
        }
    }
}
