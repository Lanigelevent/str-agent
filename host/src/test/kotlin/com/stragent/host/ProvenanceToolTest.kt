package com.stragent.host

import com.stragent.data.McpStdioServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 溯源清单的端到端验证 —— D10 §阶段2「溯源 manifest（强制）」的前半段落地。
 *
 * 断言：
 *  ① 分析链被记成清单（用了哪份数据、调了哪些工具与参数）；
 *  ② **重放复核通过**：每一步重跑一遍，结果哈希一致；
 *  ③ 数据被换掉后（旧结论对新数据）→ 数据集哈希不匹配 → 判为「不可复核」。
 */
class ProvenanceToolTest {

    private val csvA = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n华东,200,8\n"
    private val csvB = "地区,销售额,订单数\n华东,9999,10\n华北,300,5\n华东,200,8\n"

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "第 $index 帧不是成功响应：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    private fun importFrame(id: Int, csv: String): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":${jsonString(csv)},"name":"样本"}}}"""

    @Test
    fun `① 分析链留痕且重放复核通过`() {
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-prov").toFile())
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(1, csvA),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_session_overview","arguments":{}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_session_group_aggregate","arguments":{"dimension":"地区","metric":"销售额","mode":"sum"}}}""",
                ),
            )
            val datasetId = toolResult(out, 0)["datasetId"]!!.jsonPrimitive.content

            val provOut = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"stragent_provenance","arguments":{"datasetId":"$datasetId"}}}""",
                ),
            )
            val prov = toolResult(provOut, 0)

            assertEquals(datasetId, prov["datasetId"]!!.jsonPrimitive.content)
            assertTrue(prov["datasetHash"]!!.jsonPrimitive.content.isNotBlank(), "应有数据集哈希")
            assertEquals(true, prov["datasetHashMatches"]!!.jsonPrimitive.content.toBoolean(), "数据未变，哈希应一致")

            val steps = prov["steps"]!!.jsonArray
            assertTrue(steps.size >= 3, "应记录 import + 两次分析调用，实际 ${steps.size} 步")
            assertTrue(steps.any { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_session_group_aggregate" })
            assertTrue(steps.all { it.jsonObject["argsHash"]!!.jsonPrimitive.content.isNotBlank() })

            // 重放：每一步都应一致
            val replay = prov["replay"]!!.jsonArray
            assertEquals(steps.size, replay.size)
            replay.forEach { step ->
                assertEquals(
                    true,
                    step.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean(),
                    "重放应一致：${step.jsonObject["tool"]!!.jsonPrimitive.content} / ${step.jsonObject["detail"]!!.jsonPrimitive.content}",
                )
            }
            assertTrue(prov["verdict"]!!.jsonPrimitive.content.contains("可复核"))
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `② 数据被换掉后旧清单判为不可复核`() {
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-prov2").toFile())
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(1, csvA),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_session_overview","arguments":{}}}""",
                ),
            )
            val datasetId = toolResult(out, 0)["datasetId"]!!.jsonPrimitive.content

            // 换成内容不同的另一份数据 → 同一 datasetId？不���：datasetId 是内容哈希 → 会变。
            // 所以这里验证的是「数据集内容变了 → 哈希不匹配」这条路径：直接改当前数据集。
            val datasets = bootstrap.abilityDatasets
            val original = datasets.byId(datasetId)!!
            datasets.put(datasetId, original.copy(rows = original.rows.map { row ->
                if (row.isEmpty()) row else row.dropLast(1) + "999"
            }))

            val provOut = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_provenance","arguments":{"datasetId":"$datasetId"}}}""",
                ),
            )
            val prov = toolResult(provOut, 0)
            assertEquals(
                false,
                prov["datasetHashMatches"]!!.jsonPrimitive.content.toBoolean(),
                "数据变了，旧清单的哈希不应再匹配",
            )
            assertTrue(prov["verdict"]!!.jsonPrimitive.content.contains("不可复核"))
        } finally {
            bootstrap.close()
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
        append('"')
    }
}
