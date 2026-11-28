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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-033 · 语义层端到端（宿主层）。
 *
 * 要证明的是**用户能感知的那件事**：在一份数据上定义了「销售额指的是不含退货的净销售」，
 * 到另一份也有「销售额」列的数据上，这条定义仍然进提示词。
 * 三条纪律各自有断言锚：
 *  a) 语义不是数据 —— 语义块不进 brief 的 allowed_numbers；
 *  b) 不做提炼 —— 注入的是用户原话截断；
 *  c) 跨数据集持久 —— 换数据不丢语义（与记忆的核心区别：记忆按指纹失效，语义不）。
 */
class SemanticLayerTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-sem-${System.nanoTime()}").also { it.mkdirs() }

    private fun bootstrap(home: File, llm: LlmClient): StrAgentHostBootstrap =
        StrAgentHostBootstrap.create(home, llm = llm)

    /** 全部工具面（数据契约 + 插件能力 + 语义层） */
    private fun allTools(b: StrAgentHostBootstrap) =
        abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory, b.semantic)

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        allTools(b).first { it.name == name }

    private fun import(b: StrAgentHostBootstrap, csv: String): String {
        val args = buildJsonObject { put("csv", csv); put("name", "测试数据") }
        return tool(b, "stragent_import_table").invoke(args)
            .let { (it["datasetId"] as? JsonPrimitive)?.content ?: error("缺 datasetId") }
    }

    private fun report(b: StrAgentHostBootstrap, datasetId: String): Pair<String, JsonObject> {
        val args = buildJsonObject { put("datasetId", datasetId) }
        val result = tool(b, "stragent_generate_report").invoke(args)
        val reportText = (result["report"] as? JsonPrimitive)?.content ?: error("缺 report")
        return reportText to result
    }

    private fun ask(b: StrAgentHostBootstrap, datasetId: String, question: String): JsonObject {
        val args = buildJsonObject {
            put("datasetId", datasetId)
            put("question", question)
        }
        return tool(b, "stragent_ask_followup").invoke(args)
    }

    private val csvA = "地区,销售额\n华东,100\n华南,80\n华北,60\n"
    private val csvB = "地区,销售额\n华东,120\n华南,90\n"

    @Test
    fun `定义术语后报告提示词含语义块`() {
        val home = tempHome()
        val llm = FakeLlmClient()
        bootstrap(home, llm).use { b ->
            b.semantic.addTerm("销售额", definition = "不含退货的净销售金额", source = "manual")
            val id = import(b, csvA)
            val (reportText, result) = report(b, id)

            val termsUsed = (result["semanticTermsUsed"] as? JsonPrimitive)?.content?.toInt()
            assertEquals(1, termsUsed, "应有 1 条语义术语被注入")

            val promptJoined = llm.prompts.joinToString("\n")
            assertTrue(promptJoined.contains("<semantic_layer>"), "提示词应含 <semantic_layer> 块")
            assertTrue(promptJoined.contains("销售额"), "提示词应含术语名")
            assertTrue(promptJoined.contains("不含退货"), "提示词应含术语定义")
        }
    }

    @Test
    fun `无术语时提示词不含语义块——零行为差异`() {
        val home = tempHome()
        val llm = FakeLlmClient()
        bootstrap(home, llm).use { b ->
            val id = import(b, csvA)
            val (reportText, result) = report(b, id)

            val termsUsed = (result["semanticTermsUsed"] as? JsonPrimitive)?.content?.toInt()
            assertEquals(0, termsUsed, "无术语时应为 0")

            val promptJoined = llm.prompts.joinToString("\n")
            assertTrue(!promptJoined.contains("<semantic_layer>"), "无术语时不应注入语义块")
        }
    }

    @Test
    fun `跨数据集持久——换数据不丢语义`() {
        val home = tempHome()
        // 会话 1：定义术语 + 导入数据 A
        bootstrap(home, FakeLlmClient()).use { b1 ->
            b1.semantic.addTerm("销售额", definition = "不含退货的净销售金额")
            import(b1, csvA)
        }
        // 会话 2：全新 bootstrap，导入数据 B（也有「销售额」列）
        val llm2 = FakeLlmClient()
        bootstrap(home, llm2).use { b2 ->
            val terms = b2.semantic.listTerms()
            assertEquals(1, terms.size)
            assertEquals("销售额", terms[0].name)

            val id = import(b2, csvB)
            report(b2, id)

            val promptJoined = llm2.prompts.joinToString("\n")
            assertTrue(promptJoined.contains("<semantic_layer>"), "跨数据集仍应注入语义块")
            assertTrue(promptJoined.contains("不含退货"))
        }
    }

    @Test
    fun `追问里说定义——自动抽取候选入库`() {
        val home = tempHome()
        val llm = FakeLlmClient()
        bootstrap(home, llm).use { b ->
            val id = import(b, csvA)
            ask(b, id, "销售额指的是不含退货的净销售金额")

            val term = b.semantic.findTerm("销售额")
            assertNotNull(term, "追问里的术语定义应自动抽取入库")
            assertEquals("不含退货的净销售金额", term!!.definition)
            assertEquals("followup", term.source)
        }
    }

    @Test
    fun `追问提示词含语义块`() {
        val home = tempHome()
        val llm = FakeLlmClient()
        bootstrap(home, llm).use { b ->
            b.semantic.addTerm("销售额", definition = "不含退货的净销售金额")
            val id = import(b, csvA)
            ask(b, id, "华东占比多少？")

            val promptJoined = llm.prompts.joinToString("\n")
            assertTrue(promptJoined.contains("<semantic_layer>"), "追问提示词也应含语义块")
        }
    }

    @Test
    fun `list_semantic_terms 工具只读可枚举`() {
        val home = tempHome()
        bootstrap(home, FakeLlmClient()).use { b ->
            b.semantic.addTerm("销售额", definition = "不含退货", aliases = listOf("sales"))
            b.semantic.addTerm("利润", definition = "净利润")

            val listTool = listSemanticTermsTool(b.semantic)

            val allResult = listTool.invoke(buildJsonObject {})
            val count = (allResult["count"] as? JsonPrimitive)?.content?.toInt()
            assertEquals(2, count)

            val nameResult = listTool.invoke(buildJsonObject { put("name", "销售额") })
            val nameCount = (nameResult["count"] as? JsonPrimitive)?.content?.toInt()
            assertEquals(1, nameCount)
        }
    }

    @Test
    fun `语义块在 allowed_numbers 之外——纪律 a`() {
        val home = tempHome()
        val llm = FakeLlmClient()
        bootstrap(home, llm).use { b ->
            b.semantic.addTerm("客单价", definition = "销售额除以订单数约 500 元")
            val id = import(b, csvA)
            report(b, id)

            val promptJoined = llm.prompts.joinToString("\n")
            val allowedStart = promptJoined.indexOf("<allowed_numbers>")
            val allowedEnd = promptJoined.indexOf("</allowed_numbers>")
            assertTrue(allowedStart < allowedEnd, "应有 allowed_numbers 块")
            val semanticStart = promptJoined.indexOf("<semantic_layer>")
            // 语义块在 allowed_numbers 之外（在其之前或之后）
            assertTrue(semanticStart < allowedStart || semanticStart > allowedEnd,
                "语义块应在 allowed_numbers 之外")
        }
    }
}
