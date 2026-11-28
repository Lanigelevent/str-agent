package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.i18n.Messages
import com.stragent.core.ports.PrefsStore
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-079（D21 U-2/W-3）· 工具错误回执的契约测试。
 *
 * 判据（D21 U-2 原案）：工具执行错误 = **结果层 isError**（MCP 规范：执行错误进结果、
 * 协议错误走 JSON-RPC error），且所有 isError 路径都带非空 nextAction。
 * 同时守住既有语义不动（负控）：未知工具仍协议错误、取消仍外抛映射 -32800、
 * 失败任务快照带 nextAction、成功任务快照不带该键。
 */
class McpToolErrorTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 执行即抛 IllegalArgumentException 的探针工具（消息取自真实工具的错误族样本） */
    private class ThrowingTool(
        override val name: String = "throwing_tool",
        private val message: String = "datasetId 不能为空（先用 stragent_import_table 导入）",
    ) : AgentTool {
        override val description = "测试用：执行即抛"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject = throw IllegalArgumentException(message)
    }

    /** 执行即抛 AgentCancelled 的探针工具（取消语义负控） */
    private class CancellingTool(
        override val name: String = "cancelling_tool",
    ) : AgentTool {
        override val description = "测试用：执行即取消"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override fun invoke(args: JsonObject): JsonObject = throw com.stragent.core.ports.AgentCancelled()
    }

    /**
     * 期望值与**生产同源**取文案（跟随宿主 locale）——i18n 表中英齐全，
     * 生产侧 `Messages.get(key)` 本就按系统 locale 选表；断言若绑死中文，
     * 在非中文宿主上必红（Linux 容器实测 17 例假失败，T-139）。
     * 鉴别力不减：族判错 → key 错 → 文案不同 → 仍红（42 个 key 文案互不相同）。
     */
    private fun zh(key: String): String = Messages.get(key)

    /** 直连 dispatch 的 tools/call（无 HTTP），返回结果对象（执行错误已包装成 isError 结果） */
    private fun callTool(bridge: McpBridge, name: String, taskMode: Boolean = false): JsonObject =
        bridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", buildJsonObject { })
                if (taskMode) put("_meta", buildJsonObject { put("task", buildJsonObject { }) })
            },
        )

    private fun bridge(vararg tools: AgentTool): McpBridge =
        McpBridge(AbilityDatasets(), MapPrefs(), extraTools = tools.toList())

    /** 轮询任务到指定终态（10s 截止），返回终态快照 */
    private fun awaitStatus(bridge: McpBridge, taskId: String, expected: String): JsonObject {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val snap = bridge.dispatch("tasks/get", buildJsonObject { put("taskId", taskId) })
            if (snap["status"]!!.jsonPrimitive.content == expected) return snap
            Thread.sleep(50)
        }
        error("任务 10s 内未到 $expected 终态")
    }

    @Test
    fun `工具执行错误——isError 结果回执，三件套齐全且 nextAction 非空`() {
        val bridge = bridge(ThrowingTool())
        val result = callTool(bridge, "throwing_tool")

        assertEquals("true", result["isError"]!!.jsonPrimitive.content, "执行错误必须是结果层 isError：$result")
        val structured = result["structuredContent"]!!.jsonObject
        assertEquals("false", structured["ok"]!!.jsonPrimitive.content)
        assertTrue(
            structured["error"]!!.jsonPrimitive.content.contains("datasetId"),
            "error 应原样保留工具消息：$structured",
        )
        assertTrue(
            structured["nextAction"]!!.jsonPrimitive.content.isNotBlank(),
            "nextAction 必须非空：$structured",
        )
        // 消息命中数据集族 → nextAction 必须是数据集族的动作（不是兜底）
        assertEquals(zh("error.action.dataset"), structured["nextAction"]!!.jsonPrimitive.content)

        // content[0].text 同步附带动作（客户端只展示 text 也能看到下一步）
        val content = result["content"] as JsonArray
        val textPart = content.single().jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        val text = textPart["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("datasetId"), "text 应保留原始错误消息：$text")
        // 前缀取同源 key（生产用 `error.next_action_prefix`）：断言「前缀与生产同源」，
        // 不绑死中文——双语表的文案互不相同，断言仍能区分「有没有带下一步前缀」。
        val prefix = Messages.get("error.next_action_prefix").substringBefore("{0}")
        assertTrue(text.contains(prefix), "text 应附带下一步动作：$text")
    }

    @Test
    fun `消息命中列名族——nextAction 是列清单核对动作`() {
        val bridge = bridge(ThrowingTool(name = "column_tool", message = "列不存在: n（该数据集的列: a、b）"))
        val result = callTool(bridge, "column_tool")
        val structured = result["structuredContent"]!!.jsonObject
        assertEquals(zh("error.action.column"), structured["nextAction"]!!.jsonPrimitive.content)
    }

    @Test
    fun `取消语义负控——AgentCancelled 仍外抛给传输层映射 -32800，不进 isError 结果`() {
        val bridge = bridge(CancellingTool())
        val thrown = runCatching {
            callTool(bridge, "cancelling_tool")
        }.exceptionOrNull() ?: error("AgentCancelled 应作为异常外抛（-32800 映射在传输层）")
        assertTrue(
            thrown is com.stragent.core.ports.AgentCancelled || thrown.message?.contains("取消") == true,
            "取消异常应原样外抛：$thrown",
        )
    }

    @Test
    fun `未知工具——仍是协议错误（负控：isError 转换不越界吞掉协议错误）`() {
        val bridge = bridge()
        val thrown = runCatching {
            callTool(bridge, "no_such_tool")
        }.exceptionOrNull() ?: error("未知工具应抛 McpInvalidParamsException")
        assertTrue(
            thrown is McpInvalidParamsException,
            "未知工具必须还是 McpInvalidParamsException（-32602）：$thrown",
        )
    }

    @Test
    fun `异步任务失败——快照带 error 与 nextAction，成功任务快照不带 nextAction 键`() {
        // 失败路径：任务落 failed，快照 error + nextAction 齐全
        val bridge = bridge(ThrowingTool())
        val handle = callTool(bridge, "throwing_tool", taskMode = true)
        val taskId = handle["taskId"]!!.jsonPrimitive.content
        val failed = awaitStatus(bridge, taskId, "failed")
        // 官方口径（T-166）：error 是 JSON-RPC 错误对象（code + message）
        assertEquals(
            "datasetId 不能为空（先用 stragent_import_table 导入）",
            failed["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
        )
        assertTrue(failed["error"]!!.jsonObject.containsKey("code"), "error 必须带 code：$failed")
        assertEquals(zh("error.action.dataset"), failed["nextAction"]!!.jsonPrimitive.content)
        assertNull(failed["result"], "失败任务不应带结果：$failed")

        // 负控：成功路径的任务快照没有 nextAction 键（字段只在失败时带出）
        val okTool = object : AgentTool {
            override val name = "ok_tool"
            override val description = "测试用：立即成功"
            override val parametersSchema = buildJsonObject { put("type", "object") }
            override fun invoke(args: JsonObject): JsonObject = buildJsonObject { put("ok", true) }
        }
        val bridge2 = bridge(okTool)
        val handle2 = callTool(bridge2, "ok_tool", taskMode = true)
        val taskId2 = handle2["taskId"]!!.jsonPrimitive.content
        val completed = awaitStatus(bridge2, taskId2, "completed")
        assertNull(completed["nextAction"], "成功任务快照不应带 nextAction 键：$completed")
    }
}
