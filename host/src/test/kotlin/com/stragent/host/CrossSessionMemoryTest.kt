package com.stragent.host

import com.stragent.core.ports.LlmClient
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
 * T-008 · 跨会话记忆端到端（宿主层）。
 *
 * 要证明的是**用户能感知的那件事**：换个进程、重新导入同一份 csv，追问仍然看得见
 * 上一次会话问过什么、答过什么。三条纪律各自有反向验证：
 *  a) 记忆不是数据 —— 记忆文本里的数字不许进 `<allowed_numbers>`；
 *  b) 不做提炼 —— 存的是原话，召回原文可逐字对拍；
 *  c) 按内容指纹失效 —— 换一个 csv（指纹不同）就召不回来。
 */
class CrossSessionMemoryTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-mem-${System.nanoTime()}").also { it.mkdirs() }

    /** 真接线的最小宿主：假模型（确定性）+ 真 kv 存储 */
    private fun bootstrap(home: File, llm: LlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(home, llm = llm)

    private fun pluginToolsOf(b: StrAgentHostBootstrap) = pluginTools(b.plugin, b.abilityDatasets, b.memory)

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        (abilityTools(b.abilityDatasets) + pluginToolsOf(b)).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv) }
        return tool(b, "stragent_import_table").invoke(args).str("datasetId")
    }

    private fun report(b: StrAgentHostBootstrap, datasetId: String): String {
        val args = buildJsonObject { put("datasetId", datasetId) }
        return tool(b, "stragent_generate_report").invoke(args).str("report")
    }

    private fun ask(b: StrAgentHostBootstrap, datasetId: String, question: String): JsonObject {
        val args = buildJsonObject {
            put("datasetId", datasetId)
            put("question", question)
        }
        return tool(b, "stragent_ask_followup").invoke(args)
    }

    private fun recall(b: StrAgentHostBootstrap, args: JsonObject): JsonObject =
        tool(b, "stragent_recall_memory").invoke(args)

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private fun JsonObject.int(key: String): Int = str(key).toInt()

    private val csvA = "地区,销售额\n华东,100\n华南,80\n华北,60\n"
    private val csvB = "地区,销售额\n华东,120\n华南,90\n"

    @Test
    fun `新会话追问能召回上一次会话的问答（跨进程）`() {
        val home = tempHome()
        // 会话 1：导入 → 追问一个具体问题（这条问答进记忆）
        val llm1 = FakeLlmClient()
        bootstrap(home, llm1).use { b1 ->
            val id = import(b1, csvA)
            ask(b1, id, "华东占比是多少？")
        }
        // 会话 2：同 home 同 csv（内容指纹一致）→ 全新 bootstrap 实例 = 新进程
        val llm2 = FakeLlmClient()
        bootstrap(home, llm2).use { b2 ->
            val id2 = import(b2, csvA)
            val result = ask(b2, id2, "那华南呢？")
            // 召回到了 1 条（会话 1 的那轮问答）
            assertEquals(1, result.int("memoryFactsUsed"), "追问必须带上上一轮问答：$result")
            val prompt = llm2.prompts.last()
            assertTrue(prompt.contains("<session_memory>"), "记忆块要进提示词")
            assertTrue(prompt.contains("华东占比是多少？"), "记的是用户原话（纪律 b：不提炼）")
            assertTrue(prompt.contains(FakeLlmClient.DEFAULT_ANSWER), "答过的正文也要带上")
            assertTrue(prompt.contains("本次未复核"), "纪律：旧结论不得冒充本次结果")
        }
    }

    @Test
    fun `换一个 csv（内容指纹不同）就召不回旧记忆`() {
        val home = tempHome()
        bootstrap(home, FakeLlmClient()).use { b1 ->
            val id = import(b1, csvA)
            ask(b1, id, "华东占比是多少？")
        }
        bootstrap(home, FakeLlmClient()).use { b2 ->
            val otherId = import(b2, csvB)
            val result = ask(b2, otherId, "华东占比是多少？")
            // 数据一改，旧记忆就是另一个数据集的话——绝不能串过来
            assertEquals(0, result.int("memoryFactsUsed"), "不同内容指纹不得互召：$result")
            // 但旧记忆没被误删：换回原 csv 还能召回（失效是「召不回」，不是「清空」）
            val backId = import(b2, csvA)
            assertEquals(1, ask(b2, backId, "再来一轮").int("memoryFactsUsed"), "同指纹应仍能召回既有记忆")
        }
    }

    @Test
    fun `纪律 a 反向验证——记忆里的数字进不了可引用白名单`() {
        val llm = FakeLlmClient()
        bootstrap(tempHome(), llm).use { b ->
            val id = import(b, csvA)
            report(b, id)
            // 记忆里的这条数字（45.3）只存在于上一轮结论，本次数据里没有
            b.memory.remember(id, "followup", "上一轮结论：华东占比 45.3%", "test")
            assertTrue(b.memory.recall(id).any { f -> f.text.contains("45.3") }, "先确认记忆确实召得回")
            ask(b, id, "华东占比多少？")
            val prompt = llm.prompts.last()
            // 正向对照：记忆真的进了提示词（否则「白名单里没有它」是假绿）
            assertTrue(prompt.contains("<session_memory>") && prompt.contains("45.3"), "记忆未注入，后面的隔离断言就无意义")
            val allowed = prompt.substringAfter("<allowed_numbers>", "").substringBefore("</allowed_numbers>")
            assertTrue(allowed.isNotEmpty(), "夹具 brief 应带可引用数字白名单")
            assertFalse(allowed.contains("45.3"), "记忆数字混进白名单 = 上轮随口一说变成可引用统计值")
            // 两个块互不包含：记忆块在白名单之外整段闭合（纪律 a 的结构面）
            assertTrue(prompt.indexOf("</session_memory>") < prompt.indexOf("<allowed_numbers>"))
        }
    }

    @Test
    fun `召回工具只读可枚举——不写库、报告后自动留一条`() {
        val b = bootstrap(tempHome(), FakeLlmClient())
        b.use {
            val id = import(it, csvA)
            val empty = recall(it, buildJsonObject { put("datasetId", id) })
            assertEquals(0, empty.int("count"))
            assertTrue(empty.str("notice").contains("不可直接引用"), "回包要自带纪律 a 的提醒")
            assertTrue(
                tool(it, "stragent_recall_memory").description.contains("记忆不是数据"),
                "工具描述也要把纪律 a 说给调用方",
            )
            report(it, id)
            val after = recall(it, buildJsonObject { put("datasetId", id) })
            assertEquals(1, after.int("count"), "报告产出应自动留一条记忆")
            assertTrue(after.toString().contains("首轮报告："))
            // 不传 datasetId：用当前数据集，且不得改写存储（只读）
            val before = it.memory.recall(id).size
            recall(it, buildJsonObject {})
            assertEquals(before, it.memory.recall(id).size, "召回工具不得写库")
        }
    }

    @Test
    fun `记忆层缺席时工具面回到改动前的形状`() {
        val b = bootstrap(tempHome(), FakeLlmClient())
        b.use {
            val without = pluginTools(it.plugin, it.abilityDatasets).map { t -> t.name }
            assertFalse(without.contains("stragent_recall_memory"), "不传 memory 就不该有这个工具")
            val with = pluginToolsOf(b).map { t -> t.name }
            assertEquals(without.size + 1, with.size, "接上记忆层后正好多一个工具，不夹带")
            assertTrue(with.contains("stragent_recall_memory"))
        }
    }
}
