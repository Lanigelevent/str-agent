package com.stragent.host

import com.stragent.data.McpStdioServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 流式进度（T-005）：MCP 的 `notifications/progress`。
 *
 * 插件形态下**外部 agent 是唯一使用者** —— 深度报告要几十秒，
 * 客户端必须能看到「进行到哪一步」，否则只能干等、也无法判断是否值得继续等。
 *
 * 断言（编号与下面的用例一一对应）：
 *  ① 客户端在请求里带 `_meta.progressToken` → 服务端在结果帧**之前**回进度帧，且帧形制符合
 *     MCP 规范（jsonrpc / method=notifications/progress / params.progressToken·progress·message）；
 *  ② 不带 token 时**不产生**多余帧（不打扰简单调用）；
 *  ③ 报告**正文**逐段流出（T-013 阶段 2 的 token 级流式）：帧头给出「第几章·第几次生成·累计字数」，
 *     帧体按序拼起来 = 结果帧里那份 report 原文 —— 客户端贴到屏幕上的字必须就是最终正文；
 *  ④ 模型是流式的、但请求没带 progressToken → 仍然一帧不多（流式是增强，不是依赖）。
 */
class McpProgressTest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n华东,200,8\n"

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun importFrame(csv: String): String =
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":${jsonString(csv)},"name":"样本"}}}"""

    @Test
    fun `① 带 progressToken 时先回进度帧再回结果帧`() {
        val fake = FakeLlmClient(report = "## 总体概览\n\n（固定报告）\n")
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-progress").toFile(), llm = fake)
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(csv),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_generate_report","arguments":{"datasetId":"${com.stragent.data.datasetIdOf(csv)}"},"_meta":{"progressToken":7}}}""",
                ),
            )
            assertTrue(out.size >= 2, "应至少有进度帧 + 结果帧，实际 ${out.size} 帧")

            // 第一帧是导入结果（无 token → 无进度帧）
            val first = Json.parseToJsonElement(out[0]).jsonObject
            assertTrue(first.containsKey("result"), "第一帧应是导入结果：$first")

            // 后续帧里应有进度通知，且**在结果帧之前**
            val progressIndex = out.indexOfFirst { frame ->
                val obj = Json.parseToJsonElement(frame).jsonObject
                obj["method"]?.jsonPrimitive?.content == "notifications/progress"
            }
            assertTrue(progressIndex > 0, "应出现 notifications/progress 帧：$out")

            // 进度帧形制符合 MCP 规范
            val progress = Json.parseToJsonElement(out[progressIndex]).jsonObject
            assertEquals("2.0", progress.getValue("jsonrpc").jsonPrimitive.content)
            val params = progress.getValue("params").jsonObject
            assertEquals("7", params.getValue("progressToken").jsonPrimitive.content)
            assertTrue(params.containsKey("progress"), "进度帧应含 progress")
            assertTrue(params.containsKey("message"), "进度帧应含 message")

            // 进度帧必须**先于该请求自己的结果帧**（按请求 id 定位，别被前一帧的 result 误导）
            val reportResultIndex = out.indexOfFirst { frame ->
                Json.parseToJsonElement(frame).jsonObject["id"]?.jsonPrimitive?.content == "2"
            }
            assertTrue(reportResultIndex > 0, "应能定位到报告请求的结果帧：$out")
            assertTrue(progressIndex < reportResultIndex, "进度帧应先于结果帧：$out")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `② 不带 progressToken 时不产生多余帧`() {
        val fake = FakeLlmClient(report = "## 总体概览\n\n（固定报告）\n")
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-progress2").toFile(), llm = fake)
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(csv),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_generate_report","arguments":{"datasetId":"${com.stragent.data.datasetIdOf(csv)}"}}}""",
                ),
            )
            assertEquals(2, out.size, "每次调用只应回一帧，实际 $out")
            out.forEach { frame ->
                assertTrue(
                    !Json.parseToJsonElement(frame).jsonObject.containsKey("method"),
                    "无 token 时不应有通知帧：$frame",
                )
            }
        } finally {
            bootstrap.close()
        }
    }

    /**
     * ③ 报告正文的 token 级流式（T-013 阶段 2）：客户端在等报告时能看到**字在长**，
     * 而不是一直到「写完了第 1 章」才动一下。
     *
     * 关键断言不是「有没有帧」，而是**拼回来的正文 = 结果帧里的 report**：
     * 帧只是提前呈现同一份内容，任何时候都不许是另一份。
     */
    @Test
    fun `③ 报告正文逐帧流出，帧体拼接等于结果里的 report`() {
        val fake = FakeLlmClient(report = longReport, tokenSize = 12)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-body").toFile(), llm = fake)
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(csv),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_generate_report","arguments":{"datasetId":"${com.stragent.data.datasetIdOf(csv)}"},"_meta":{"progressToken":"p-body"}}}""",
                ),
            )
            val body = progressMessages(out).filter { it.startsWith("正文流式") }
            assertTrue(body.size >= 2, "正文应切成多帧，实际 ${body.size} 帧：$body")

            // 帧头：第几章 · 第几次生成 · 累计字数（客户端靠这三项定位与识别重写）
            val header = Regex("^正文流式（第(\\d+)/(\\d+)章 (.+?)·第(\\d+)次生成·累计 (\\d+) 字）\\n([\\s\\S]*)$")
            val parsed = body.map { header.matchEntire(it) ?: error("正文帧文不符合约定：${it.take(80)}") }
            assertTrue(parsed.all { it.groupValues[1] == it.groupValues[2] }, "单次成文：章号应恒等于总章数")
            assertTrue(parsed.all { it.groupValues[4] == "1" }, "一次写成：attempt 应恒为 1")
            val cumulative = parsed.map { it.groupValues[5].toInt() }
            assertEquals(cumulative.sorted(), cumulative, "累计字数必须单调递增")
            assertEquals(longReport.length, cumulative.last(), "末帧累计字数应等于正文总长")
            assertEquals(longReport, parsed.joinToString("") { it.groupValues[6] }, "帧体拼接必须逐字等于报告正文")

            // 与结果帧对账：进度帧呈现的内容与最终返回的是同一份
            val resultFrame = out.first {
                Json.parseToJsonElement(it).jsonObject["id"]?.jsonPrimitive?.content == "2"
            }
            val text = Json.parseToJsonElement(resultFrame).jsonObject["result"]!!
                .jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertEquals(longReport, Json.parseToJsonElement(text).jsonObject["report"]!!.jsonPrimitive.content)

            // 正文帧之前先有阶段帧（analyzing），且全部在结果帧之前
            val firstBody = out.indexOfFirst { frame ->
                val obj = Json.parseToJsonElement(frame).jsonObject
                obj["method"]?.jsonPrimitive?.content == "notifications/progress" &&
                    obj["params"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.startsWith("正文流式") == true
            }
            val resultIndex = out.indexOf(resultFrame)
            assertTrue(firstBody in 0 until resultIndex, "正文帧必须先于结果帧：first=$firstBody result=$resultIndex")
        } finally {
            bootstrap.close()
        }
    }

    /**
     * ④ 反作用锚点：模型侧**具备**流式能力（tokenSize 非空）不等于一定要发帧。
     * 请求没带 progressToken 时必须一帧不多 —— 否则「不带 token 就不产生多余帧」（用例 ②）
     * 会被流式能力悄悄破坏，而客户端只是想要一个安静的一次性结果。
     */
    @Test
    fun `④ 流式模型但请求没带 progressToken：仍不产生多余帧`() {
        val fake = FakeLlmClient(report = longReport, tokenSize = 12)
        val bootstrap = StrAgentHostBootstrap.create(createTempDirectory("str-agent-body2").toFile(), llm = fake)
        try {
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(csv),
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"stragent_generate_report","arguments":{"datasetId":"${com.stragent.data.datasetIdOf(csv)}"}}}""",
                ),
            )
            assertEquals(2, out.size, "无 progressToken 时只应回两帧结果，实际 $out")
            assertTrue(
                out.none { Json.parseToJsonElement(it).jsonObject.containsKey("method") },
                "无 progressToken 时不得出现任何通知帧（含正文流式帧）",
            )
            assertTrue(fake.prompts.any { it.contains("<available_data>") || it.contains("数据") }, "报告确实生成过")
        } finally {
            bootstrap.close()
        }
    }

    /** 造一份足够长的正文：节流器按 120 字成帧，太短只会剩一帧，测不出「流」 */
    private val longReport: String =
        "## 总体概览\n\n" + "华东地区销售额最高，建议加大投放并复核渠道结构。".repeat(10) + "\n"

    /** 帧序列 → 进度帧的 message 文本（非进度帧忽略） */
    private fun progressMessages(frames: List<String>): List<String> = frames.mapNotNull { frame ->
        val obj = Json.parseToJsonElement(frame).jsonObject
        if (obj["method"]?.jsonPrimitive?.content != "notifications/progress") return@mapNotNull null
        obj["params"]?.jsonObject?.get("message")?.jsonPrimitive?.content
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
