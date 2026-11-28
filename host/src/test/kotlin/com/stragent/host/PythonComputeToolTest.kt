package com.stragent.host

import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpSessionContext
import com.stragent.data.McpStdioServer
import com.stragent.data.SessionRegistry
import com.stragent.core.ports.PrefsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真 Python 计算工具（T-031，阶段 2 后半段 sandbox 档第二落地）的端到端验证。
 *
 * 断言：
 *  ① **默认关闭**：与受控表达式共用同一把人类授权闸；
 *  ② 解释器不可用：显式注入不存在的路径 → 中文错误（无 Python 的机器走这条）；
 *  ③ 授权后可执行：数值与解析解一致 + 清单完整（engineId/env/inputHash/outputHash）+ 进溯源清单可重放；
 *  ④ 缺 result 变量 → 中文错误；
 *  ⑤ 超时强杀 → 中文错误；
 *  ⑥ 会话感知：具名会话不带 datasetId 时用**会话指针**，default 会话回退全局最近导入。
 *
 * 无 Python 环境（CI 裸机等）下 ③④⑤ 显式转验「不可用路径」（不是静默跳过——那也是正确行为）。
 */
class PythonComputeToolTest {

    private val csv = "地区,销售额,订单数\n华东,1000,10\n华北,300,5\n"

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun runStdio(bridge: com.stragent.data.McpBridge, lines: List<String>): List<String> {
        val input = ByteArrayInputStream(lines.joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return output.toString(Charsets.UTF_8).trim().lines().filter { it.isNotBlank() }
    }

    private fun errorMessage(out: List<String>): String {
        val frame = Json.parseToJsonElement(out.last()).jsonObject
        return frame["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }

    private fun toolResult(out: List<String>, index: Int): JsonObject {
        val frame = Json.parseToJsonElement(out[index]).jsonObject
        assertTrue(frame.containsKey("result"), "第 $index 帧不是成功响应：$frame")
        val content = frame["result"]!!.jsonObject
        return Json.parseToJsonElement(content["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }

    private fun importFrame(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"stragent_import_table","arguments":{"csv":${jsonString(csv)},"name":"样本"}}}"""

    private fun pythonCallFrame(id: Int, code: String): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"stragent_compute_python","arguments":{"code":${jsonString(code)}}}}"""

    private fun authorizedDatasets(): AbilityDatasets = AbilityDatasets()

    private fun datasetOf(id: String, csvText: String): Dataset {
        val table = com.stragent.core.csv.Csv.parseDelimited(csvText)
        return Dataset(name = id, importedAt = 0L, columns = table.columns, rows = table.rows, columnTypes = table.columnTypes)
    }

    @Test
    fun `① 未授权时拒绝并给中文说明（与受控表达式同一把闸）`() {
        val tool = pythonComputeTool(authorizedDatasets(), MapPrefs())
        val error = runCatching {
            tool.invoke(buildJsonObject { put("code", "result = 1") })
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "应为 IllegalStateException：$error")
        assertTrue(error.message!!.contains("默认关闭"), "应说明默认关闭：${error.message}")
        assertTrue(error.message!!.contains("STRAGENT_SANDBOX"), "应说明如何开启：${error.message}")
    }

    @Test
    fun `② 解释器不可用时给中文错误（注入不存在的路径）`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs, pythonExe = "str-agent-no-such-python-xyz")
        val error = runCatching {
            tool.invoke(buildJsonObject { put("code", "result = 1") })
        }.exceptionOrNull()
        assertTrue(error != null, "应失败：不存在解释器时不能假装成功")
        assertTrue(error!!.message!!.contains("Python 解释器不可用"), "应给中文错误：${error.message}")
    }

    @Test
    fun `③ 授权后可执行——客单价与解析解一致、清单完整、进溯源可重放`() {
        val provider = SubprocessPythonProvider()
        if (!provider.isAvailable()) {
            // 无 Python 的机器：不可用路径本身也要给中文错误，而不是装作通过。
            // **必须先造数据集**：工具的调用序是「授权闸 → 取 code → 解析 datasetId →
            // 执行」，数据集缺失会先炸在「尚无数据集」，压根到不了解释器检查
            // （实测：Linux 容器无 Python 时本分支原本永远红——同族④⑤都造了数据集，只有③漏了）。
            val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
            val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
            val tool = pythonComputeTool(datasets, prefs)
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }

        val home = createTempDirectory("str-agent-pycomp-on").toFile()
        val bootstrap = StrAgentHostBootstrap.create(home)
        try {
            bootstrap.prefs.set("compute.sandbox.enabled", "true")
            val code = "result = round(sum(float(r[1]) for r in rows) / sum(float(r[2]) for r in rows), 6)"
            val out = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    importFrame(1),
                    pythonCallFrame(2, code),
                ),
            )
            val result = toolResult(out, 1)
            // 解析解：总销售额 1300 / 总订单数 15 = 86.666667（行值是字符串，代码里 float() 自行转换）
            assertEquals(86.666667, result["value"]!!.jsonPrimitive.content.toDouble(), 1e-6)

            val manifest = result["manifest"]!!.jsonObject
            assertEquals("python-sandbox", manifest["engineId"]!!.jsonPrimitive.content)
            assertTrue(manifest["env"]!!.jsonPrimitive.content.startsWith("cpython-"), "env 应标 cpython 版本：${manifest["env"]}")
            assertTrue(manifest["inputHash"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(manifest["outputHash"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("number", manifest["outputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(code.trim(), manifest["code"]!!.jsonPrimitive.content)

            val datasetId = toolResult(out, 0)["datasetId"]!!.jsonPrimitive.content
            val provOut = runStdio(
                bootstrap.mcpBridge,
                listOf(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"stragent_provenance","arguments":{"datasetId":"$datasetId"}}}""",
                ),
            )
            val prov = toolResult(provOut, 0)
            assertTrue(
                prov["steps"]!!.jsonArray.any { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_compute_python" },
                "Python 计算步骤应进入溯源清单",
            )
            val replay = prov["replay"]!!.jsonArray
                .first { it.jsonObject["tool"]!!.jsonPrimitive.content == "stragent_compute_python" }
            assertEquals(true, replay.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean(), "Python 计算应可重放：${replay.jsonObject["detail"]}")
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `④ 缺 result 变量时给中文错误`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs)
        if (!SubprocessPythonProvider().isAvailable()) {
            // 无 Python 的机器：不可用路径本身也要给中文错误，而不是装作通过
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }
        val error = runCatching {
            tool.invoke(buildJsonObject { put("code", "x = 1") })
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("result"), "应指出缺 result：${error.message}")
    }

    @Test
    fun `⑤ 超时强杀并给中文错误`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs, timeoutMs = 1_500)
        if (!SubprocessPythonProvider().isAvailable()) {
            // 无 Python 的机器：不可用路径本身也要给中文错误，而不是装作通过
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }
        val error = runCatching {
            tool.invoke(buildJsonObject { put("code", "import time\ntime.sleep(5)\nresult = 1") })
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("超时"), "应说明超时强杀：${error.message}")
    }

    @Test
    fun `⑥ 会话感知——具名会话用会话指针，default 会话回退全局最近导入`() {
        val datasets = AbilityDatasets()
        val registry = SessionRegistry(datasets)
        datasets.put("ds_old", datasetOf("ds_old", "a\n1\n"))
        datasets.put("ds_new", datasetOf("ds_new", "a\n2\n")) // 全局「最近导入」= ds_new
        registry.setCurrent("sess-1", "ds_old")
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val tool = computeTool(datasets, prefs, registry) // 走表达式档即可，与 Python 工具共用同一个解析 helper

        McpSessionContext.with("sess-1") {
            val r = tool.invoke(buildJsonObject { put("code", "sum(a)") })
            assertEquals(1.0, r["value"]!!.jsonPrimitive.content.toDouble(), "具名会话应取会话指针 ds_old")
        }
        McpSessionContext.with(SessionRegistry.DEFAULT_SESSION) {
            val r = tool.invoke(buildJsonObject { put("code", "sum(a)") })
            assertEquals(2.0, r["value"]!!.jsonPrimitive.content.toDouble(), "default 会话应回退全局最近导入 ds_new")
        }
    }

    @Test
    fun `⑦ 导入白名单——os 与 socket 拒绝、math 仍可用`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs)
        if (!SubprocessPythonProvider().isAvailable()) {
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }
        val osError = runCatching {
            tool.invoke(buildJsonObject { put("code", "import os\nresult = 1") })
        }.exceptionOrNull()
        assertTrue(osError!!.message!!.contains("沙箱不允许导入 os"), "应拒绝导入 os：${osError.message}")

        val socketError = runCatching {
            tool.invoke(buildJsonObject { put("code", "import socket\nresult = 1") })
        }.exceptionOrNull()
        assertTrue(socketError!!.message!!.contains("沙箱不允许导入 socket"), "应拒绝导入 socket（禁网络）：${socketError.message}")

        // 白名单不是一刀切：纯计算模块仍可用
        val ok = tool.invoke(buildJsonObject { put("code", "import math\nresult = math.sqrt(9)") })
        assertEquals(3.0, ok["value"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `⑧ 危险内建被封——open 调用即拒绝`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs)
        if (!SubprocessPythonProvider().isAvailable()) {
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }
        val error = runCatching {
            tool.invoke(buildJsonObject { put("code", "result = open('x').read()") })
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("沙箱禁止使用 open()"), "应拒绝 open：${error.message}")
    }

    @Test
    fun `⑨ 用户 print 改道 stderr——协议 stdout 不被污染`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs)
        if (!SubprocessPythonProvider().isAvailable()) {
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }
        val result = tool.invoke(buildJsonObject { put("code", "print('噪声' * 20)\nresult = 42") })
        assertEquals(42.0, result["value"]!!.jsonPrimitive.content.toDouble(), 1e-9, "print 不应破坏结果解析")
    }

    @Test
    fun `⑩ stderr 收集有界——超限继续排空但只记上限`() {
        val capture = OutputCapture(16)
        capture.drain(ByteArrayInputStream(ByteArray(100) { 'a'.code.toByte() }))
        assertTrue(capture.overflow, "超限应标记 overflow")
        assertEquals(16, capture.text().toByteArray(Charsets.UTF_8).size, "只保留上限内的字节")
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

    @Test
    fun `⑪ 内存硬限——超限分配被拒（T-150）`() {
        val prefs = MapPrefs().apply { set("compute.sandbox.enabled", "true") }
        val datasets = authorizedDatasets().apply { put("ds_x", datasetOf("ds_x", "a\n1\n")) }
        val tool = pythonComputeTool(datasets, prefs, timeoutMs = 10_000, maxMemMb = 16)
        if (!SubprocessPythonProvider().isAvailable()) {
            // 无 Python 的机器：不可用路径本身也要给中文错误，而不是装作通过
            val error = runCatching { tool.invoke(buildJsonObject { put("code", "result = 1") }) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("Python 解释器不可用"), "无 Python 环境应给中文错误：${error.message}")
            return
        }
        val error = runCatching {
            tool.invoke(buildJsonObject { put("code", "x = bytearray(200*1024*1024)") })
        }.exceptionOrNull()
        assertTrue(error != null, "200MB 分配在 16MB 硬限下必须失败")
        val msg = (error?.message ?: "") + (error?.cause?.message ?: "")
        assertTrue(
            msg.contains("MemoryError") || msg.contains("内存") || msg.contains("超限"),
            "错误要指明内存问题：$msg",
        )
    }
}
