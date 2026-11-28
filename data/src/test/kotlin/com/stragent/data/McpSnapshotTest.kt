package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ProgressBus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * T-189 · MCP 协议互操作快照夹具（D24 P1-7）。
 *
 * 把 stdio 与 HTTP 两条传输的**真实会话**录成金标夹具（`fixtures/mcp-transcripts/`），
 * 回归时逐字节对拍：协议序列化（帧形/字段序/头部）从此有自动门禁——改 [McpFrames]、
 * [McpTaskDispatch] 或信封装配的帧形 → 快照测试红，不再靠人工发现。
 *
 * ## 双模式（同一套驱动，保证录制与回放同源）
 *
 * - **录制**：`STRAGENT_RECORD_MCP_TRANSCRIPTS=1` 时把会话输出写进夹具目录
 *   （入口：`python scripts/record-mcp-transcripts.py`；重录后必须
 *   `git diff fixtures/mcp-transcripts` 逐行 review——金标纪律沿用 fixtures/）。
 * - **回放**（默认）：同一驱动重跑会话，与夹具逐字节比对；漂移即红。
 *
 * ## 确定性纪律（快照必须跨机跨次稳定）
 *
 * - 全部用**确定性自造工具**（固定 CSV、固定进度帧、固定 elicitation 消息）而不是
 *   真报告链——要钉的是**传输帧形**，不是模型输出（与 [McpHttpSseTest] 同一取向）；
 * - 易变字段在录制与回放两侧统一过 [sanitize]：task id / elicitation id（随机 128-bit）、
 *   serverInfo.version（随发版变化）、HTTP token（每次安装随机）→ 占位符；
 * - datasetId 是内容指纹（同一 CSV 恒同 id），保留字面量——这正是要钉住的契约；
 * - 行分隔符按平台归一（stdio 的 PrintWriter.println 在 Windows 下是 CRLF）——
 *   归一后**行内字节**仍逐字节比对。
 *
 * 边界（诚实）：夹具录在 data 模块装配（4 条能力契约 + 快照探针工具）——宿主装配的
 * 全量工具面（50 条）由 host 侧 McpToolContractTest 钉；elicitation 钉的是无通道
 * cancel 降级帧形（elicitation/create 请求帧形由 ElicitationBroker 的既有测试钉）；
 * MRTR input_required 信封由 McpMrtrTest 钉。
 */
class McpSnapshotTest {

    // ---------- 录制/回放开关与落盘 ----------

    private val recordMode = System.getenv("STRAGENT_RECORD_MCP_TRANSCRIPTS") == "1"

    /** 仓库根（测试工作目录是模块目录 data/，向上找到 settings.gradle.kts 为止） */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    private fun fixtureDir(channel: String): File = File(repoRoot(), "fixtures/mcp-transcripts/$channel")

    // ---------- 公共小件 ----------

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 确定性进度源：固定 3 帧 + 固定结果（进度帧形被 SSE 快照钉住） */
    private class FramesTool : AgentTool {
        override val name = "sse_probe"
        override val description = "测试用：按固定顺序发 3 帧进度后返回固定结果"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject {
            ProgressBus.emit("快照进度第1帧")
            ProgressBus.emit("快照进度第2帧")
            ProgressBus.emit("快照进度第3帧")
            return buildJsonObject { put("text", "sse-probe-done") }
        }
    }

    /**
     * elicitation 探针：走真 [ElicitationBroker]——无通道时如实 cancel（降级路径，
     * 「客户端不支持 elicitation」）。快照钉住的是这个结局的 tools/call 信封帧形。
     */
    private class ElicitProbeTool(private val broker: ElicitationBroker) : AgentTool {
        override val name = "stragent_elicit_probe"
        override val description = "测试用：经真 broker 发起一次 elicitation 提问并如实回报结局"
        override val parametersSchema = buildJsonObject { put("type", "object") }
        override val cacheable = false
        override fun invoke(args: JsonObject): JsonObject {
            val sessionId = McpSessionContext.current()
            val outcome = broker.request(
                sessionId,
                "请确认数据口径（快照探针）",
                buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("confirm", buildJsonObject { put("type", "boolean") })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("confirm"))))
                },
                timeoutMs = 15_000,
            )
            return buildJsonObject { put("text", outcome.toJson().toString()) }
        }
    }

    // ---------- sanitize：易变字段占位符化（录制与回放两侧统一过） ----------

    private fun sanitize(text: String, token: String?): String {
        var out = text
        out = out.replace(Regex("task_[0-9a-f]{32}"), "__TASK_ID__")
        out = out.replace(Regex("elicit_[0-9a-f]{32}"), "__ELICIT_ID__")
        out = out.replace(BuildInfo.version, "__SERVER_VERSION__")
        out = out.replace(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z"""), "__ISO_TS__")
        out = out.replace(Regex("""("ttlMs":)\d{6,}"""), "$1__TTL_MS__")
        if (token != null) out = out.replace(token, "__MCP_TOKEN__")
        return out
    }

    private fun normalizeEol(text: String): String = text.replace("\r\n", "\n")

    // ---------- 请求构造 ----------

    /**
     * JSON-RPC 请求行构造器（构造+序列化一体）：请求文本由同一序列化器生成——
     * 中文/换行转义确定，跨录制/回放两侧字节一致。
     */
    private fun rpc(id: Int, method: String, params: JsonObject? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            params?.let { put("params", it) }
            put("method", method)
        }
        return obj.toString()
    }

    private fun paramsOf(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { for ((k, v) in pairs) put(k, v) }

    /** 4 行 CSV（与 McpBridgeTest 同源）：华东 1200.5 + 300 = 1500.5，确定性聚合结果 */
    private val snapshotCsv = "门店,销售额\n华东,1200.5\n华南,800\n华东,300"

    /** 直接分发一次 import 拿 datasetId（内容指纹，跨次恒定；后续段引用它） */
    private fun resolveDatasetId(bridge: McpBridge): String {
        val resp = bridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", ABILITY_IMPORT_TABLE)
                put("arguments", buildJsonObject {
                    put("csv", snapshotCsv)
                    put("name", "门店销售")
                })
            },
            requestId = null,
        )
        val text = (resp["content"] as JsonArray)
            .first().let { (it as JsonObject)["text"] as JsonPrimitive }.content
        return Json.parseToJsonElement(text).jsonObject["datasetId"]!!
            .let { it as JsonPrimitive }.content
    }

    // ---------- stdio 会话驱动与夹具 ----------

    private class StdioStep(val name: String, val request: String)

    /** stdio 会话段：initialize → 目录 → 导入 → 画像 → 聚合 → 回看行 → 未知方法错误帧 */
    private fun stdioSteps(dsId: String): List<StdioStep> = listOf(
        StdioStep("01-initialize", rpc(1, "initialize", paramsOf(
            "protocolVersion" to JsonPrimitive("2025-03-26"),
            "capabilities" to buildJsonObject { },
            "clientInfo" to buildJsonObject {
                put("name", "snapshot")
                put("version", "1.0")
            },
        ))),
        StdioStep("02-tools-list", rpc(2, "tools/list")),
        StdioStep("03-import", rpc(3, "tools/call", paramsOf(
            "name" to JsonPrimitive(ABILITY_IMPORT_TABLE),
            "arguments" to buildJsonObject {
                put("csv", snapshotCsv)
                put("name", "门店销售")
            },
        ))),
        StdioStep("04-column-profile", rpc(4, "tools/call", paramsOf(
            "name" to JsonPrimitive(ABILITY_COLUMN_PROFILE),
            "arguments" to buildJsonObject { put("datasetId", dsId) },
        ))),
        StdioStep("05-group-aggregate", rpc(5, "tools/call", paramsOf(
            "name" to JsonPrimitive(ABILITY_GROUP_AGGREGATE),
            "arguments" to buildJsonObject {
                put("datasetId", dsId)
                put("dimension", "门店")
                put("metric", "销售额")
            },
        ))),
        StdioStep("06-list-rows", rpc(6, "tools/call", paramsOf(
            "name" to JsonPrimitive(ABILITY_LIST_ROWS),
            "arguments" to buildJsonObject {
                put("datasetId", dsId)
                put("offset", 0)
                put("limit", 2)
            },
        ))),
        StdioStep("07-unknown-method", rpc(7, "tools/unknown")),
    )

    /** 跑一次完整 stdio 会话（真 [McpStdioServer] + 内存流），返回按行切分的响应行 */
    private fun runStdioSession(steps: List<StdioStep>, bridge: McpBridge): List<String> {
        val input = ByteArrayInputStream((steps.joinToString("\n") { it.request } + "\n").toByteArray(Charsets.UTF_8))
        val output = ByteArrayOutputStream()
        McpStdioServer(bridge, input, output).serve()
        return normalizeEol(output.toString("UTF-8")).split("\n").filter { it.isNotEmpty() }
    }

    // ---------- HTTP 会话驱动 ----------

    private class HttpStep(
        val name: String,
        val headers: List<Pair<String, String>>,
        val body: String,
    )

    private class HttpCapture(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    /** 发一个真 HTTP 请求并捕获（状态码 + 归一化头部 + 原始体） */
    private fun postHttp(
        port: Int,
        token: String,
        headers: List<Pair<String, String>>,
        body: String,
        timeoutMs: Int = 30_000,
    ): HttpCapture {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        for ((k, v) in headers) conn.setRequestProperty(k, v.replace("__MCP_TOKEN__", token))
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status < 400) conn.inputStream else conn.errorStream
        val bodyText = stream?.readBytes()?.decodeToString() ?: ""
        val headerMap = conn.headerFields.entries
            .filter { it.key != null }
            .associate { it.key.lowercase() to it.value.joinToString(", ") }
        conn.disconnect()
        return HttpCapture(status, headerMap, bodyText)
    }

    /** 头部比较集：排除传输层易变头（Date/Content-Length/Transfer-Encoding） */
    private fun comparableHeaders(cap: HttpCapture): Map<String, String> = cap.headers.filterKeys {
        it !in setOf("date", "content-length", "transfer-encoding")
    }

    /** HTTP 基线段：initialize → 目录 → 导入 → 画像 → 聚合 → 回看行 → 未知方法（同一桥，顺序会话） */
    private fun httpBaselineSteps(dsId: String, sessionId: String): List<HttpStep> {
        val baseHeaders = listOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer __MCP_TOKEN__",
            "Mcp-Session-Id" to sessionId,
        )
        return listOf(
            HttpStep("01-initialize", baseHeaders, rpc(1, "initialize", paramsOf(
                "protocolVersion" to JsonPrimitive("2025-03-26"),
                "capabilities" to buildJsonObject { },
                "clientInfo" to buildJsonObject {
                    put("name", "snapshot")
                    put("version", "1.0")
                },
            ))),
            HttpStep("02-tools-list", baseHeaders, rpc(2, "tools/list")),
            HttpStep("03-import", baseHeaders, rpc(3, "tools/call", paramsOf(
                "name" to JsonPrimitive(ABILITY_IMPORT_TABLE),
                "arguments" to buildJsonObject {
                    put("csv", snapshotCsv)
                    put("name", "门店销售")
                },
            ))),
            HttpStep("04-column-profile", baseHeaders, rpc(4, "tools/call", paramsOf(
                "name" to JsonPrimitive(ABILITY_COLUMN_PROFILE),
                "arguments" to buildJsonObject { put("datasetId", dsId) },
            ))),
            HttpStep("05-group-aggregate", baseHeaders, rpc(5, "tools/call", paramsOf(
                "name" to JsonPrimitive(ABILITY_GROUP_AGGREGATE),
                "arguments" to buildJsonObject {
                    put("datasetId", dsId)
                    put("dimension", "门店")
                    put("metric", "销售额")
                },
            ))),
            HttpStep("06-list-rows", baseHeaders, rpc(6, "tools/call", paramsOf(
                "name" to JsonPrimitive(ABILITY_LIST_ROWS),
                "arguments" to buildJsonObject {
                    put("datasetId", dsId)
                    put("offset", 0)
                    put("limit", 2)
                },
            ))),
            HttpStep("07-unknown-method", baseHeaders, rpc(7, "tools/unknown")),
        )
    }

    /**
     * HTTP 段的夹具文本（录制与回放共用同一构造器——两侧同源）：
     * 请求块（路径/头部/请求体）+ 响应块（状态码/比较头部/原始体）。
     */
    private fun httpCaptureText(step: HttpStep, cap: HttpCapture, token: String?): String {
        val sb = StringBuilder()
        sb.append("> POST /mcp\n")
        for ((k, v) in step.headers) sb.append("> ").append(k.lowercase()).append(": ").append(v).append("\n")
        sb.append("> request-body <<<<\n").append(step.body).append("\n")
        sb.append("< status: ").append(cap.status).append("\n")
        for ((k, v) in comparableHeaders(cap).toSortedMap()) {
            sb.append("< ").append(k).append(": ").append(sanitize(v, token)).append("\n")
        }
        sb.append("< body <<<<\n").append(normalizeEol(sanitize(cap.body, token)))
        return sb.toString()
    }

    /** stdio 段的夹具文本（同上——两侧同源） */
    private fun stdioCaptureText(step: StdioStep, responseLine: String): String =
        "> " + step.request + "\n< " + sanitize(responseLine, null) + "\n"

    // ---------- 录制与回放（双模式共用驱动） ----------

    /** 录制：把全部段写进夹具目录（录完打印提示：git diff 逐行 review） */
    private fun recordSegments(kind: String, texts: Map<String, String>) {
        val dir = fixtureDir(kind)
        dir.mkdirs()
        for ((name, text) in texts) {
            File(dir, "$name.txt").writeText(text, Charsets.UTF_8)
        }
        println("已录制 $kind ${texts.size} 段 → ${dir.absolutePath}；请 git diff fixtures/mcp-transcripts 逐行 review") // lint:allow zh-first（录制模式提示，非产品输出）
    }

    /** 回放：逐段与夹具比对；漂移即 fail（给出首个差异行） */
    private fun replaySegments(kind: String, texts: Map<String, String>) {
        val dir = fixtureDir(kind)
        assertTrue(dir.isDirectory, "夹具目录缺失：${dir.absolutePath}——先跑 python scripts/record-mcp-transcripts.py 录制") // lint:allow zh-first（测试断言消息）
        for ((name, live) in texts) {
            val file = File(dir, "$name.txt")
            assertTrue(file.isFile, "夹具缺失：$name.txt——先录制再回放") // lint:allow zh-first（测试断言消息）
            val expected = normalizeEol(file.readText(Charsets.UTF_8))
            if (expected != live) {
                val expLines = expected.lines()
                val liveLines = live.lines()
                var i = 0
                while (i < minOf(expLines.size, liveLines.size) && expLines[i] == liveLines[i]) i++
                fail("快照漂移 [$kind/$name.txt] 第 ${i + 1} 行：\n  期望: ${expLines.getOrNull(i)?.take(300)}\n  实际: ${liveLines.getOrNull(i)?.take(300)}") // lint:allow zh-first（测试断言消息）
            }
        }
    }

    /**
     * 单段比较器（负控与潜在重构共用）：漂移即 fail，给出首个差异行。
     * 负控测试直接调用它验证「漂移真的会被抓住」。
     */
    private fun assertSnapshotEqual(name: String, expected: String, live: String) {
        if (expected == live) return
        val expLines = expected.lines()
        val liveLines = live.lines()
        var i = 0
        while (i < minOf(expLines.size, liveLines.size) && expLines[i] == liveLines[i]) i++
        fail("快照漂移 [$name] 第 ${i + 1} 行：\n  期望: ${expLines.getOrNull(i)?.take(300)}\n  实际: ${liveLines.getOrNull(i)?.take(300)}") // lint:allow zh-first（测试断言消息）
    }

    // ---------- 测试 ----------

    @Test
    fun `stdio 会话快照——逐字节对拍`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs)
        val dsId = resolveDatasetId(bridge)
        val steps = stdioSteps(dsId)
        val outputs = runStdioSession(steps, bridge)
        assertEquals(steps.size, outputs.size, "stdio 每请求一响应行：实际 ${outputs.size} 行") // lint:allow zh-first（测试断言消息）
        val texts = steps.zip(outputs).associate { (step, resp) -> step.name to stdioCaptureText(step, resp) }
        if (recordMode) recordSegments("stdio", texts) else replaySegments("stdio", texts)
    }

    @Test
    fun `http 基线快照——逐字节对拍`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs)
        val token = McpBridge.currentToken(prefs)
        val dsId = resolveDatasetId(bridge)
        val port = bridge.start(0)
        try {
            val steps = httpBaselineSteps(dsId, "sess-snapshot-baseline")
            val texts = buildMap {
                for (step in steps) {
                    val cap = postHttp(port, token, step.headers, step.body)
                    put(step.name, httpCaptureText(step, cap, token))
                }
            }
            if (recordMode) recordSegments("http", texts) else replaySegments("http", texts)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `http SSE 渐进快照——逐字节对拍`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(FramesTool()))
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            val step = HttpStep(
                "07-sse-progress",
                listOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer __MCP_TOKEN__",
                    "Mcp-Session-Id" to "sess-snapshot-sse",
                    "Accept" to "text/event-stream",
                ),
                rpc(21, "tools/call", paramsOf(
                    "name" to JsonPrimitive("sse_probe"),
                    "arguments" to buildJsonObject { },
                    "_meta" to buildJsonObject { put("progressToken", "tok-snap") },
                )),
            )
            val cap = postHttp(port, token, step.headers, step.body)
            val texts = mapOf(step.name to httpCaptureText(step, cap, token))
            if (recordMode) recordSegments("http", texts) else replaySegments("http", texts)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `http elicitation 无通道降级快照——逐字节对拍`() {
        val prefs = MapPrefs()
        val broker = ElicitationBroker()
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(ElicitProbeTool(broker)), elicitation = broker)
        val token = McpBridge.currentToken(prefs)
        val dsId = resolveDatasetId(bridge)
        val port = bridge.start(0)
        try {
            val step = HttpStep(
                "08-elicitation-cancel",
                listOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer __MCP_TOKEN__",
                    "Mcp-Session-Id" to "sess-snapshot-elicit",
                ),
                rpc(31, "tools/call", paramsOf(
                    "name" to JsonPrimitive("stragent_elicit_probe"),
                    "arguments" to buildJsonObject { },
                )),
            )
            val cap = postHttp(port, token, step.headers, step.body)
            val texts = mapOf(step.name to httpCaptureText(step, cap, token))
            if (recordMode) recordSegments("http", texts) else replaySegments("http", texts)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `http tasks 快照——创建句柄与未知 id 错误帧`() {
        val prefs = MapPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs)
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            val baseHeaders = listOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer __MCP_TOKEN__",
                "Mcp-Session-Id" to "sess-snapshot-tasks",
            )
            val steps = listOf(
                HttpStep("09-task-create", baseHeaders, rpc(41, "tools/call", paramsOf(
                    "name" to JsonPrimitive(ABILITY_IMPORT_TABLE),
                    "arguments" to buildJsonObject {
                        put("csv", snapshotCsv)
                        put("name", "任务导入")
                    },
                    "_meta" to buildJsonObject {
                        put("task", buildJsonObject { })
                        put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {
                            put("extensions", buildJsonObject {
                                put("io.modelcontextprotocol/tasks", buildJsonObject { })
                            })
                        })
                    },
                ))),
                HttpStep("10-task-get-unknown", baseHeaders, rpc(42, "tasks/get", paramsOf(
                    "taskId" to JsonPrimitive("task_notexist0000000000000000000000000000000000000")
                ))),
                HttpStep("11-task-cancel-unknown", baseHeaders, rpc(43, "tasks/cancel", paramsOf(
                    "taskId" to JsonPrimitive("task_notexist0000000000000000000000000000000000000")
                ))),
            )
            val texts = buildMap {
                for (step in steps) {
                    val cap = postHttp(port, token, step.headers, step.body)
                    put(step.name, httpCaptureText(step, cap, token))
                }
            }
            if (recordMode) recordSegments("http", texts) else replaySegments("http", texts)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `负控——帧形漂移被比较器抓住`() {
        val live = "> " + rpc(2, "tools/list") + "\n< " + "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"resultType\":\"complete\"}}" + "\n"
        val drifted = live.replace("\"resultType\":\"complete\"", "\"resultType\":\"complete\",\"x-injected\":true")
        assertTrue(drifted != live, "注入字段后期望必须变化") // lint:allow zh-first（测试断言消息）
        assertFailsWith<AssertionError> { assertSnapshotEqual("负控/合成段", drifted, live) }
    }
}
