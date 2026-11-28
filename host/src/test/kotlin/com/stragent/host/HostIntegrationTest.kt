package com.stragent.host

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.datasetFromParsed
import com.stragent.data.McpStdioServer
import com.stragent.data.datasetIdOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **宿主集成验收**（D7 T-020）—— 纯 agent 插件形态的「存在性证明」。
 *
 * 四项验收（与 D7 T-020 的清单一一对应）：
 *  ① 同进程宿主装载 → 生成报告；
 *  ② 无头 MCP 桥端到端（导入 → 报告 → 拿回报告文本）；
 *  ③ 清单可发现且各份清单字段一致；
 *  ④ 失败路径是中文提示而不是裸栈。
 *
 * 全部用确定性假模型（[FakeLlmClient]）跑，**不需要真实模型 Key**，可离线可重复。
 */
class HostIntegrationTest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n华东,200,8\n"

    private fun tempHome(): File = createTempDirectory("str-agent-host-it").toFile()

    private fun datasetOf(csv: String) = datasetFromParsed("验收样本", 1, Csv.parseDelimited(csv))

    /** 仓库根目录（从模块工作目录往上找 settings.gradle.kts） */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录")
    }

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    @Test
    fun `① 同进程宿主装载并生成报告——数据摘要确实到达了模型`() {
        val fake = FakeLlmClient(report = "## 总体概览\n\n总销售额 1500 元。\n")
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = fake)
        try {
            val result = bootstrap.plugin.generateInsight(datasetOf(csv))

            assertTrue(result.report.contains("总销售额 1500"), "报告应原样带回模型产物：${result.report}")
            assertEquals("classic", result.engineId, "默认引擎应是经典流水线")
            assertTrue(fake.calls > 0, "应至少调用模型一次")
            // 关键断言：数据摘要（brief）真的到了模型 —— 这是「数字可溯源」的链路起点
            assertTrue(
                fake.prompts.any { it.contains("销售额") && it.contains("华东") },
                "数据摘要（列名与样本值）应出现在发给模型的 prompt 里",
            )
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `② 无头 MCP 桥端到端——导入表格后经工具调出报告`() {
        val fake = FakeLlmClient(report = "## 总体概览\n\n总销售额 1500 元。\n")
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = fake)
        try {
            val expectedId = datasetIdOf(csv)
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":${jsonString(csv)},"name":"验收样本"}}}""",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_generate_report","arguments":{"datasetId":"$expectedId"}}}""",
                ),
            )
            assertEquals(2, out.size, "两次 tools/call 应有两帧响应：$out")

            val imported = Json.parseToJsonElement(out[0]).jsonObject.getValue("result")
            assertTrue(imported.toString().contains(expectedId), "导入结果应含 datasetId=$expectedId")

            val generated = Json.parseToJsonElement(out[1]).jsonObject.getValue("result")
            assertTrue(generated.toString().contains("总销售额 1500"), "MCP 应把报告原文带回：$generated")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `③ 清单可发现——代码清单、打包资源、ZCode 清单三者一致`() {
        val bootstrap = StrAgentHostBootstrap.create(tempHome())
        try {
            val manifest = bootstrap.plugin.manifest.toJson()
            assertEquals("str-agent", manifest.getValue("id").jsonPrimitive.content)
            assertEquals("1.0.0", manifest.getValue("version").jsonPrimitive.content)
            assertTrue(manifest.containsKey("inject"), "清单应声明宿主缝 inject")
            assertTrue(manifest.containsKey("contributes"), "清单应声明能力面 contributes")

            // 随包资源（外部 harness 读它发现能力面）
            val packaged = Json.parseToJsonElement(
                resourceText("str-agent/src/main/resources/com/stragent/agent/plugin/str-agent.plugin.json"),
            ).jsonObject
            assertEquals(manifest.getValue("id").jsonPrimitive.content, packaged.getValue("id").jsonPrimitive.content)
            assertEquals(manifest.getValue("version").jsonPrimitive.content, packaged.getValue("version").jsonPrimitive.content)

            // ZCode 插件清单（宿主用它接线）——注意 ZCode 形态用 `name` 而非 `id`
            val zcode = Json.parseToJsonElement(
                File(repoRoot(), ".zcode-plugin/plugin.json").readText(Charsets.UTF_8),
            ).jsonObject
            assertEquals("str-agent", zcode.getValue("name").jsonPrimitive.content)
            assertEquals("1.0.0", zcode.getValue("version").jsonPrimitive.content)
            assertTrue(zcode.containsKey("mcpServers"), "ZCode 清单应声明 mcpServers")
            assertTrue(zcode.containsKey("skills"), "ZCode 清单应声明 skills")
            assertTrue(zcode.containsKey("commands"), "ZCode 清单应声明 commands")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `④ 没有模型 Key 时给中文提示而不是裸栈`() {
        // 真实 HttpLlmClient + 没有任何 Key
        val bootstrap = StrAgentHostBootstrap.create(tempHome())
        try {
            val error = runCatching { bootstrap.plugin.generateInsight(datasetOf(csv)) }.exceptionOrNull()
            assertNotNull(error, "没有 Key 时应报错")
            val message = error!!.message ?: ""
            assertTrue(message.contains("API Key"), "应是中文可读提示，实际：$message")
        } finally {
            bootstrap.close()
        }
    }

    /** 仓库内文本资源（按仓库根相对路径读） */
    private fun resourceText(relative: String): String =
        File(repoRoot(), relative).readText(Charsets.UTF_8)

    /** JSON 字符串字面量（用于把 CSV 塞进 JSON-RPC 帧） */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
