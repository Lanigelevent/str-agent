package com.stragent.host

import com.stragent.agent.plugin.StrAgentPlugin
import com.stragent.core.observability.OtelSpan
import com.stragent.core.observability.OtelTraces
import com.stragent.core.observability.SpanExporter
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.PrefsStore
import com.stragent.data.AbilityDatasets
import com.stragent.data.AbilityDatasetStore
import com.stragent.data.FileDatasetStore
import com.stragent.data.McpBridge
import com.stragent.data.McpStdioServer
import com.stragent.data.SessionMemoryStore
import com.stragent.data.abilityTools
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.stragent.core.i18n.Messages

/**
 * T-074 · 故障注入套件（D21 §2.5 R-1）——把散落在各任务的负控系统化成五个场景。
 *
 * 每个场景都断言同一组性质（混沌工程的最小验收）：
 *  ①**业务不崩**——故障只降级服务，不炸进程/不炸调用方；
 *  ②**有可读中文诊断**——失败要么有中文告警（日志/回执），要么「正常结局」有明说；
 *  ③**状态可恢复**——故障移除或重试后，业务回到可用状态（不是永久残废）。
 *
 * 五场景：
 *  1. 导出器挂/慢（OTel SpanExporter 抛异常 / 阻塞）：业务照常、告警一次、恢复后正常导出；
 *  2. kv 条目损坏：恢复跳过坏条目、中文告警（键名照实）、正常业务不受牵连；
 *  3. 磁盘写失败（kv `put` 抛 IOException 注入）：`persisted=false` + 中文说明如实回执、
 *     记忆写失败静默降级、故障移除后落盘与记忆恢复；
 *  4. SSE 客户端半途断开 + 慢消费者：服务不崩、丢帧有中文告警 + 累计计数、重连可用；
 *  5. stdin 半行 EOF：坏帧得 -32700 中文解析错误、serve() 优雅退出、新连接可用。
 *
 * 诚实边界：
 *  - 诊断文本断言用 stderr 捕获（StructuredLogger 落 stderr），窗口罩住异步告警落点；
 *  - 场景 3 用确定性 `put` 抛错注入而非「目录只读」：Windows 上目录只读位拦不住建文件、
 *    只读**文件**也会被 rename 越权替换（本套件首跑实证）——文件系统位注入在 Windows 上
 *    不可靠，换成分明、可跨平台的注入点（行为等价：put 抛 IOException = 磁盘写失败）。
 */
class FaultInjectionTest {

    // ---------- 共用工具 ----------

    /** 捕获窗口内的 stderr（StructuredLogger 的默认出口）；窗口必须罩住异步告警的落点 */
    private fun <T> captureErr(block: () -> T): Pair<T, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
        return try {
            val result = block()
            result to buffer.toString("UTF-8")
        } finally {
            System.setErr(original)
        }
    }

    private fun tempHome(): File = createTempDirectory("str-agent-fault").toFile()

    private fun span(): OtelSpan =
        OtelSpan(traceId = "1".repeat(32), spanId = "2".repeat(16), parentSpanId = null, name = "fault-test")

    private fun importCsv(datasets: AbilityDatasets, csv: String): JsonObject =
        abilityTools(datasets)
            .first { it.name == "stragent_import_table" }
            .invoke(buildJsonObject { put("csv", csv); put("name", "故障注入样本") })

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private val csvA = "地区,销售额\n华东,100\n华南,80\n华北,60\n"

    /** 磁盘写失败注入：put 抛 IOException（真实磁盘故障的等价形状），get/keys/remove 照常 */
    private class FaultKv(private val delegate: DatasetStore, fail: Boolean = true) : DatasetStore {
        @Volatile
        var fail: Boolean = fail

        override fun put(key: String, value: String) {
            if (fail) throw IOException("模拟磁盘写失败（故障注入）")
            delegate.put(key, value)
        }

        override fun get(key: String): String? = delegate.get(key)

        override fun remove(key: String) = delegate.remove(key)

        override fun keys(): List<String> = delegate.keys()
    }

    private fun testPrefs(): PrefsStore = object : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    // ---------- 场景 1：导出器挂/慢 ----------

    @Test
    fun `场景1 导出器挂与慢——业务照常、中文告警一次、恢复后正常导出`() {
        // 挂：按**调用次数**失败（第 1 次抛，之后成功）——标志位会在 worker 处理前被翻转，
        // 按次数失败才能确定性地让「第一批失败、恢复后的批次送达」
        val calls = AtomicInteger(0)
        val delivered = mutableListOf<List<OtelSpan>>()
        val flaky = object : SpanExporter {
            override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("导出器挂了")
                delivered.add(spans)
                return true
            }
        }
        val traces = OtelTraces(flaky, queueCapacity = 8)
        val (_, err) = captureErr {
            traces.export(listOf(span())) // → worker 异步导出 → 抛异常 → 中文告警一次
            traces.export(listOf(span())) // 恢复后的导出（worker 处理时已是第 2 次调用）
            traces.close() // drain：worker 跑完已提交批次，告警在窗口内落盘
        }
        assertTrue(err.contains("OTLP 导出异常"), "导出器抛异常必须有中文告警：$err")
        assertEquals(1, delivered.size, "恢复后的批次必须送达（挂掉的那批不重试，如实）")
        assertTrue(delivered[0].isNotEmpty())

        // 慢：导出器每批 60ms，队列容量 4——60 次提交必须不被拖住（非阻塞纪律）
        val slow = object : SpanExporter {
            override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean {
                Thread.sleep(60)
                return true
            }
        }
        val slowTraces = OtelTraces(slow, queueCapacity = 4)
        val t0 = System.nanoTime()
        repeat(60) { slowTraces.export(listOf(span())) }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs < 1200, "export 必须非阻塞：60 次 60ms 导出器却耗时 ${elapsedMs}ms")
        assertTrue(slowTraces.droppedBatches() > 0, "容量 4 装不下 60 批，必须溢出丢弃（丢批有计数）")
        slowTraces.close() // 等 worker 跑完，不悬挂

        // host 级：报告链路挂着「永远抛」的导出器，报告照样出
        // （plugin 必须取 bootstrap 装好的实例——裸 new 未 install，工具会拒）
        StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient()).use { b ->
            val datasetId = importCsv(b.abilityDatasets, csvA).str("datasetId")
            val broken = OtelTraces(object : SpanExporter {
                override fun export(resource: Map<String, String>, spans: List<OtelSpan>): Boolean =
                    throw IllegalStateException("导出器永久挂了")
            })
            val reportTool = pluginTools(b.plugin, b.abilityDatasets, otel = broken)
                .first { it.name == "stragent_generate_report" }
            val receipt = reportTool.invoke(buildJsonObject { put("datasetId", datasetId) })
            assertTrue(receipt.containsKey("report"), "导出器挂死不得影响报告生成：${receipt.keys}")
        }
    }

    // ---------- 场景 2：kv 条目损坏 ----------

    @Test
    fun `场景2 kv条目损坏——恢复跳过坏条目、中文告警、正常业务不受牵连`() {
        val home = tempHome()
        // 预埋一个损坏条目（模拟盘上手改 / 版本不兼容）；kv 目录由 create() 建，先手动建好
        File(home, "kv").mkdirs()
        val corruptKey = "ability-dataset.ds_corrupt000001"
        val name = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(corruptKey.toByteArray(Charsets.UTF_8))
        File(File(home, "kv"), "$name.kv").writeText("这不是JSON{{{", Charsets.UTF_8)

        // 告警落在 create() 的启动恢复里（restore → loadAll）——捕获窗口必须罩住 create
        val (b, err) = captureErr { StrAgentHostBootstrap.create(home, llm = FakeLlmClient()) }
        b.use {
            assertTrue(err.contains(Messages.get("datasetstore.corrupt_entry_skipped")), "坏条目必须有中文告警（键名照实）：$err")
            assertFalse(it.abilityDatasets.ids().contains("ds_corrupt000001"), "坏条目不得进入数据集列表")

            // 正常业务不受牵连：导入照常落盘
            val receipt = importCsv(it.abilityDatasets, csvA)
            assertEquals("true", receipt.str("persisted"), "坏条目存在时正常导入必须照常落盘：$receipt")
        }
        // 恢复面：同一 home 重启（新实例）——坏条目仍被跳过，进程照常起来
        StrAgentHostBootstrap.create(home, llm = FakeLlmClient()).use { b ->
            assertFalse(b.abilityDatasets.ids().contains("ds_corrupt000001"), "重启后坏条目依旧被跳过")
        }
    }

    // ---------- 场景 3：磁盘写失败 ----------

    @Test
    fun `场景3 磁盘写失败——persisted false 如实回执、追问照常、故障移除后恢复`() {
        val kv = FaultKv(FileDatasetStore(tempHome()), fail = true)
        val datasets = AbilityDatasets(AbilityDatasetStore(kv))
        val memKv = FaultKv(FileDatasetStore(tempHome()), fail = true)
        val memory = SessionMemoryStore(memKv)
        // 追问链路需要已安装的插件（含假模型）；数据集与记忆用故障注入的实例
        val pluginHost = StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

        // 导入照常成功（数据在内存），只是落盘失败——persisted=false + 中文说明
        val failed = importCsv(datasets, csvA)
        assertEquals("false", failed.str("persisted"), "写失败必须如实回执：$failed")
        assertEquals(
            Messages.get("import.persisted_note"),
            failed.str("persistedNote"),
            "写失败的诊断要指明恢复动作：${failed.str("persistedNote")}",
        )
        val datasetId = failed.str("datasetId")

        // 业务不崩：数据本次会话内可用（追问照常回答，记忆写失败静默降级是既定口径）
        val ask = pluginTools(pluginHost.plugin, datasets, memory = memory)
            .first { it.name == "stragent_ask_followup" }
        val receipt1 = ask.invoke(buildJsonObject { put("datasetId", datasetId); put("question", "华东销售额多少？") })
        assertTrue(receipt1.containsKey("answer"), "落盘失败不得影响追问回答：${receipt1.keys}")

        // 恢复：故障移除 → 落盘恢复 + 第二轮问答应能存进记忆（第一轮写失败如实丢失）
        kv.fail = false
        memKv.fail = false
        assertEquals("true", importCsv(datasets, csvA).str("persisted"), "故障移除后落盘必须恢复")
        val receipt2 = ask.invoke(buildJsonObject { put("datasetId", datasetId); put("question", "华南销售额多少？") })
        assertTrue(receipt2.containsKey("answer"), "恢复后追问照常：${receipt2.keys}")
        val recall = pluginTools(pluginHost.plugin, datasets, memory = memory)
            .first { it.name == "stragent_recall_memory" }
        assertEquals("1", recall.invoke(buildJsonObject { put("datasetId", datasetId) }).str("count"),
            "恢复后第二轮问答应能存进记忆（第一轮写失败如实丢失）")
        pluginHost.close()
    }

    // ---------- 场景 4：SSE 半途断开 ----------

    @Test
    fun `场景4 SSE半途断开与慢消费者——服务不崩、丢帧有中文告警、重连可用`() {
        // /events 只在提供 webUiHtml 时注册（/ui 套件）；bridge 与 currentToken 必须共用同一 prefs
        val prefs = testPrefs()
        val bridge = McpBridge(AbilityDatasets(), prefs, webUiHtml = WebUiPage.html)
        try {
            // 临时端口（0=系统分配）：CI 曾因固定 7787 被 TIME_WAIT 残留/外部进程占用而连续
            // 两轮 BindException（run 66/2166 实证）——测试不绑固定端口是根治
            val port = bridge.start(0)
            val token = McpBridge.currentToken(prefs)

            val (_, err) = captureErr {
                // 裸 socket 建连、只读到 connected 帧、之后**不读**：服务端把 TCP 缓冲写满后
                // 阻塞在 write，广播持续灌入 → 每连接队列溢出 → 丢帧计数；
                // 最后 close() socket 强制断开（HttpURLConnection.disconnect 不保证立刻断，
                // 服务端会一直阻塞在 write 等不到 IOException——首跑实证），write 抛 IOException
                val socket = java.net.Socket("127.0.0.1", port)
                socket.getOutputStream().write(
                    ("GET /events?token=$token&sessionId=default HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Accept: text/event-stream\r\n\r\n").toByteArray(Charsets.UTF_8),
                )
                val reader = socket.getInputStream().bufferedReader()
                var line: String?
                do {
                    line = reader.readLine()
                } while (!line.isNullOrEmpty()) // 吃掉响应头（空行结束）
                assertTrue(reader.readLine() != null, "SSE 流应已建立（connected 帧可达）")

                repeat(5000) { com.stragent.agent.UiEventBroadcaster.broadcast("flood_$it", "default") }
                Thread.sleep(800) // 服务端阻塞在 write、队列灌满并溢出
                socket.close() // 半途断开 → 服务端 write 抛 IOException → finally 落丢帧告警
                // 告警在循环退出的 finally：写阻塞在半途时立刻退；若恰好在空闲 poll，
                // 要等下一次心跳写失败（SSE_HEARTBEAT_MS=15s）才退出——等待必须罩住心跳周期
                Thread.sleep(16_000)
            }

            assertTrue(err.contains("丢帧"), "慢消费者丢帧必须有中文告警：${err.take(400)}")
            assertTrue(bridge.droppedSseFrames() > 0, "丢弃必须进累计计数（不静默）")

            // 可恢复：断开后立即重连 + MCP 调用照常
            val reconnect = URL("http://127.0.0.1:$port/events?token=$token&sessionId=default")
                .openConnection() as HttpURLConnection
            assertEquals(200, reconnect.responseCode, "断开后重连必须被接受（槽位已回收）")
            reconnect.inputStream.bufferedReader().readLine() // connected 帧
            reconnect.disconnect()

            val ping = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            ping.requestMethod = "POST"
            ping.doOutput = true
            ping.setRequestProperty("Authorization", "Bearer $token")
            ping.setRequestProperty("Content-Type", "application/json")
            ping.outputStream.write(
                """{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray(Charsets.UTF_8),
            )
            assertEquals(200, ping.responseCode, "故障后 MCP 调用必须照常")
            val pingBody = ping.inputStream.bufferedReader().readText()
            assertTrue(pingBody.contains("\"result\""), "ping 必须得到正常响应：$pingBody")
        } finally {
            bridge.stop()
        }
    }

    // ---------- 场景 5：stdin 半行 EOF ----------

    @Test
    fun `场景5 stdin半行EOF——坏帧得中文解析错误、优雅退出、新连接可用`() {
        // 合法帧 + 半行（无换行符直达 EOF）
        val input = ByteArrayInputStream(
            ("""{"jsonrpc":"2.0","id":1,"method":"ping"}""" + "\n" + "{broken")
                .toByteArray(Charsets.UTF_8),
        )
        val output = ByteArrayOutputStream()
        McpStdioServer(newBridge(), input, output).serve() // 必须正常返回（不崩不悬挂）
        val text = output.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"result\""), "合法帧必须先得到正常响应：$text")
        assertTrue(text.contains("-32700"), "半行坏帧必须得到 -32700：$text")
        assertTrue(text.contains("解析错误"), "坏帧诊断必须是中文：$text")

        // 可恢复：全新连接（新 server + 新输入）照常服务
        val input2 = ByteArrayInputStream("""{"jsonrpc":"2.0","id":2,"method":"ping"}""".toByteArray(Charsets.UTF_8))
        val output2 = ByteArrayOutputStream()
        McpStdioServer(newBridge(), input2, output2).serve()
        assertTrue(output2.toString(Charsets.UTF_8).contains("\"result\""), "新连接必须可用")
    }

    private fun newBridge(): McpBridge = McpBridge(AbilityDatasets(), testPrefs())
}
