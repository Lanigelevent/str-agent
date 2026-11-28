package com.stragent.host

import com.stragent.core.ai.GenerationCancelled
import com.stragent.core.ports.ChatResult
import com.stragent.core.ports.LlmClient
import com.stragent.data.McpRequestCancellation
import com.stragent.data.abilityTools
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-039 · host 报告工具真的把 MCP 取消信号交给引擎。
 * T-040 · 追问工具同口径：截断续写轮也要能在取消后停手。
 *
 * data 层已用真 HTTP 验过「通知 → -32800」的传输语义；这里补的是**接线**：
 * `stragent_generate_report` / `stragent_ask_followup` 若还传恒 false 的信号，
 * 表现会和「取消没做」一模一样。用一个在生成期间自己置取消位的假模型，
 * 证明取消后不再花第二次审查 / 续写调用。
 */
class HostCancellationTest {

    private class CancelDuringChat(
        private val requestId: String = "42",
        private val truncated: Boolean = false,
    ) : LlmClient {
        var calls = 0
            private set

        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult {
            calls += 1
            // 模拟「客户端在本次生成期间发来 notifications/cancelled」
            McpRequestCancellation.cancel("host-session", requestId)
            val joined = messages.joinToString("\n") { it.second }
            val content = if (joined.contains("\"issues\"")) "{\"issues\":[]}" else "## 总体概览\n正文。"
            return ChatResult(text = content, totalTokens = 10, truncated = truncated)
        }
    }

    private fun tempHome(): File =
        Files.createTempDirectory("str-agent-t039-").toFile()

    private fun tool(b: StrAgentHostBootstrap, name: String) =
        (abilityTools(b.abilityDatasets) + pluginTools(b.plugin, b.abilityDatasets, b.memory))
            .first { it.name == name }

    @Test
    fun `报告工具接入取消——生成期间取消后不再继续，令牌随即清理`() {
        val llm = CancelDuringChat()
        StrAgentHostBootstrap.create(tempHome(), llm = llm).use { b ->
            val datasetId = (
                tool(b, "stragent_import_table")
                    .invoke(buildJsonObject { put("csv", "地区,销售额\n华东,100\n华南,80\n") })["datasetId"]
                    as JsonPrimitive
                ).content
            assertFailsWith<GenerationCancelled> {
                // 与 MCP dispatch 同形制：登记「会话 + 请求 id」后再调用工具
                McpRequestCancellation.withRequest("host-session", "42") {
                    tool(b, "stragent_generate_report")
                        .invoke(buildJsonObject { put("datasetId", datasetId) })
                }
            }
            // 只发了成文那一次；语义审查被取消信号拦下
            assertEquals(1, llm.calls)
            // 块结束即清理：不会误伤同 id 的下一次请求
            assertFalse(McpRequestCancellation.cancel("host-session", "42"))
        }
    }

    @Test
    fun `追问工具接入取消——首轮被截断时取消后不再续写`() {
        // truncated 首轮本会触发 chatStep 续写（最多 3 轮）；取消必须在这一轮之前生效
        val llm = CancelDuringChat(requestId = "43", truncated = true)
        StrAgentHostBootstrap.create(tempHome(), llm = llm).use { b ->
            val datasetId = (
                tool(b, "stragent_import_table")
                    .invoke(buildJsonObject { put("csv", "地区,销售额\n华东,100\n华南,80\n") })["datasetId"]
                    as JsonPrimitive
                ).content
            assertFailsWith<GenerationCancelled> {
                McpRequestCancellation.withRequest("host-session", "43") {
                    tool(b, "stragent_ask_followup").invoke(
                        buildJsonObject {
                            put("datasetId", datasetId)
                            put("question", "华东的销售额是多少")
                        },
                    )
                }
            }
            assertEquals(1, llm.calls, "取消后不该再为截断续写买单")
            assertFalse(McpRequestCancellation.cancel("host-session", "43"))
        }
    }
}
