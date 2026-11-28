package com.stragent.host

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MCP 工具面安全自扫（T-104，mcp-sec-audit 式检查的运行时内联版）：
 * 对默认形态工具面（沙箱未开 50 条）做「过权/描述质量/注解一致性/注入短语」静态检查——
 * OWASP MCP Tool Poisoning 的自我防御面（描述是供应链资产，自产也要自审）。
 */
class McpSurfaceScanTest {

    private val tools = StrAgentHostBootstrap.create(
        File(System.getProperty("java.io.tmpdir"), "str-agent-scan-" + System.nanoTime()).also { it.mkdirs() },
    ).let { b ->
        try {
            b.mcpBridge.dispatch("tools/list", kotlinx.serialization.json.JsonObject(emptyMap()))["tools"]!!.jsonArray
        } finally {
            b.close()
        }
    }

    private fun str(o: kotlinx.serialization.json.JsonObject, k: String) = o.getValue(k).jsonPrimitive.content

    @Test
    fun `扫01 命名与描述质量——前缀规范且描述非空有引导`() {
        assertEquals(44, tools.size) // T-155/T-190 默认形态（沙箱未开裁受控计算 2 条 + 视觉未就绪裁 analyze_image + db 未授权裁 db_* 6 条）
        for (t in tools) {
            val o = t.jsonObject
            val name = str(o, "name")
            assertTrue(name.startsWith("stragent_"), "命名规范：$name")
            val desc = str(o, "description")
            assertTrue(desc.length >= 20, "描述过短（应含用途/边界引导）：$name")
            assertTrue(!desc.contains("ignore previous instructions"), "描述注入短语：$name")
            assertTrue(!desc.contains("忽略上述指令"), "描述注入短语（中文）：$name")
        }
    }

    @Test
    fun `扫02 注解一致性——四 hints 齐全且语义不矛盾`() {
        for (t in tools) {
            val o = t.jsonObject
            val name = str(o, "name")
            val ann = o.getValue("annotations").jsonObject
            for (k in listOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")) {
                assertTrue(ann.containsKey(k), "$name 缺注解 $k")
            }
            val ro = ann.getValue("readOnlyHint").jsonPrimitive.content.toBooleanStrict()
            val destructive = ann.getValue("destructiveHint").jsonPrimitive.content.toBooleanStrict()
            assertTrue(!(ro && destructive), "$name 只读与破坏性同时为 true（矛盾）")
        }
    }

    @Test
    fun `扫03 参数契约——required 均在 properties 中`() {
        for (t in tools) {
            val o = t.jsonObject
            val name = str(o, "name")
            val schema = o.getValue("inputSchema").jsonObject
            assertEquals("object", str(schema, "type"), "$name schema type")
            val props = schema.getValue("properties").jsonObject
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            for (r in required) {
                assertTrue(props.containsKey(r), "$name 的 required 参数 $r 不在 properties 中")
            }
            // 每个参数都要有 description（调用方模型的引导面）
            for ((k, v) in props) {
                val vo = v.jsonObject
                assertTrue(vo.containsKey("description") && str(vo, "description").isNotBlank(), "$name 参数 $k 缺 description")
            }
        }
    }

    @Test
    fun `扫04 过权检查——只读工具不得含写入类描述动词，网络工具必须 openWorld`() {
        val writeVerbs = listOf("写入", "删除")  // 「覆盖」「导出」有正当语义（覆盖范围/导出报告），不列入
        for (t in tools) {
            val o = t.jsonObject
            val name = str(o, "name")
            val ann = o.getValue("annotations").jsonObject
            val ro = ann.getValue("readOnlyHint").jsonPrimitive.content.toBooleanStrict()
            val openWorld = ann.getValue("openWorldHint").jsonPrimitive.content.toBooleanStrict()
            val desc = str(o, "description")
            if (ro) {
                for (v in writeVerbs) {
                    assertTrue(!desc.contains(v), "只读工具 $name 描述含写入动词「$v」（过权嫌疑）")
                }
            }
            // 涉及模型调用/联网的工具必须 openWorld=true（诚实声明）
            if (name in setOf("stragent_generate_report", "stragent_ask_followup", "stragent_summarize_text", "stragent_analyze_image")) {
                assertTrue(openWorld, "$name 涉外部调用应 openWorld=true")
            }
        }
    }
}
