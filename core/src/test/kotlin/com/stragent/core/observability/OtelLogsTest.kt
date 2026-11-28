package com.stragent.core.observability

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OTel logs 内核离线单测（T-066）。
 *
 * 钉住的口径：
 *  1. severity 映射（TRACE→1 / DEBUG→5 / INFO→9 / WARN→13 / ERROR→17，未知按 INFO）；
 *  2. 行解析：好行 → logRecord（body/severityText/attributes 带 log.logger 与 log.field.*），
 *     坏行（非 JSON / 缺键 / 坏时间戳）→ null 跳过不抛；
 *  3. envelope 形制：resourceLogs[].scopeLogs[].logRecords[]，64 位时间戳字符串；
 *  4. 异步批量：有界队列丢行计数、drain 用「已提交 == 已完成」、close 同步收尾；
 *  5. 环境装配：LOGS_ENDPOINT ＞ 基础端点（追加 /v1/logs）＞ 文件；都没有 = null；
 *  6. 桥：原通道逐字不变、额外投 OTel、close 还原。
 */
class OtelLogsTest {

    private fun logLine(level: String, message: String, logger: String = "TestLogger") =
        "{\"timestamp\":\"2026-10-30T12:00:00Z\",\"level\":\"$level\",\"logger\":\"$logger\",\"message\":\"$message\"}"

    // ---------------- 1. severity 与行解析 ----------------

    @Test
    fun `severity 映射——五档与未知档`() {
        assertEquals(1, OtelLogsJson.severityNumber("TRACE"))
        assertEquals(5, OtelLogsJson.severityNumber("DEBUG"))
        assertEquals(9, OtelLogsJson.severityNumber("INFO"))
        assertEquals(13, OtelLogsJson.severityNumber("WARN"))
        assertEquals(17, OtelLogsJson.severityNumber("ERROR"))
        assertEquals(9, OtelLogsJson.severityNumber("whatever"), "未知级别按 INFO，不丢行")
        assertEquals(17, OtelLogsJson.severityNumber("error"), "小写也认")
    }

    @Test
    fun `好行解析出 logRecord，坏行返回 null 跳过`() {
        val record = assertNotNull(OtelLogsJson.logRecordFromLine(logLine("WARN", "出问题了")))
        assertEquals("13", record["severityNumber"]!!.jsonPrimitive.content)
        assertEquals("WARN", record["severityText"]!!.jsonPrimitive.content)
        assertEquals("出问题了", record["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        val attrs = (record["attributes"] as JsonArray).associate {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content
        }
        assertEquals("TestLogger", attrs["log.logger"])
        // 带自定义 fields 的行：逐项进 attributes
        val line = "{\"timestamp\":\"2026-10-30T12:00:00Z\",\"level\":\"INFO\",\"logger\":\"L\",\"message\":\"m\",\"fields\":{\"k\":\"v\"}}"
        val rich = assertNotNull(OtelLogsJson.logRecordFromLine(line))
        val richAttrs = (rich["attributes"] as JsonArray).associate {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content
        }
        assertEquals("v", richAttrs["log.field.k"])

        // 坏行：非 JSON、缺键、坏时间戳 —— 一律 null，不抛
        assertNull(OtelLogsJson.logRecordFromLine("not json"))
        assertNull(OtelLogsJson.logRecordFromLine("{\"timestamp\":\"2026-10-30T12:00:00Z\",\"level\":\"INFO\"}"))
        assertNull(OtelLogsJson.logRecordFromLine("{\"timestamp\":\"bad\",\"level\":\"INFO\",\"logger\":\"L\",\"message\":\"m\"}"))
        assertNull(OtelLogsJson.logRecordFromLine("[]"))
    }

    @Test
    fun `envelope 形制——resourceLogs 到 logRecords 的层级与字符串时间戳`() {
        val record = assertNotNull(OtelLogsJson.logRecordFromLine(logLine("ERROR", "boom", logger = "X")))
        val json = OtelLogsJson.envelope(mapOf("service.name" to "str-agent"), listOf(record))
        val rl = (json["resourceLogs"] as JsonArray)[0].jsonObject
        val sl = (rl["scopeLogs"] as JsonArray)[0].jsonObject
        assertEquals(OtelLogsJson.SCOPE_NAME, sl["scope"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val records = sl["logRecords"] as JsonArray
        assertEquals(1, records.size)
        val lr = records[0].jsonObject
        assertEquals("17", lr["severityNumber"]!!.jsonPrimitive.content)
        assertEquals("2026-10-30T12:00:00Z".let {
            java.time.OffsetDateTime.parse(it).toInstant().epochSecond * 1_000_000_000L + 0
        }.toString(), lr["timeUnixNano"]!!.jsonPrimitive.content, "64 位时间戳必须是字符串")
        assertTrue(lr["observedTimeUnixNano"]!!.jsonPrimitive.content.isNotEmpty())
    }

    // ---------------- 2. 异步批量与收尾 ----------------

    /** 卡住第一批导出，让队列被填满——确定地观测「丢行」 */
    private class BlockingLogExporter : OtelLogs.LogsExporter {
        val started = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        private val bodies = mutableListOf<String>()

        override fun export(resource: Map<String, String>, body: ByteArray): Boolean {
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            synchronized(bodies) { bodies.add(String(body, Charsets.UTF_8)) }
            return true
        }

        fun release() = gate.countDown()

        fun receivedCount(): Int = synchronized(bodies) { bodies.sumOf { it.split("\"logRecords\":").size - 1 } }
    }

    @Test
    fun `异步批量不阻塞；队列满丢行计数，drain 等到全部完成`() {
        val exporter = BlockingLogExporter()
        val logs = OtelLogs(exporter, batchSize = 1, queueCapacity = 1)
        try {
            logs.offer(logLine("INFO", "first"))
            assertTrue(exporter.started.await(2, TimeUnit.SECONDS), "导出线程应已处理第一批")

            logs.offer(logLine("INFO", "second")) // 入队
            logs.offer(logLine("INFO", "third")) // 队列满 → 丢
            assertEquals(1, logs.droppedLines(), "容量 1 时第三行必须被丢弃并计数")

            exporter.release()
            assertTrue(logs.drain(5_000), "drain 应等全部提交行完成")
        } finally {
            logs.close()
        }
    }

    @Test
    fun `drain 连续 60 轮都不提前返回`() {
        val exporter = OtelLogs.CollectingLogExporter()
        val logs = OtelLogs(exporter, batchSize = 8)
        try {
            repeat(60) { round ->
                logs.offer(logLine("INFO", "round-$round"))
                assertTrue(logs.drain(2_000), "第 $round 轮 drain 超时")
            }
            val all = exporter.bodies().joinToString("")
            repeat(60) { round ->
                assertTrue(all.contains("round-$round"), "第 $round 行必须已到达导出器")
            }
        } finally {
            logs.close()
        }
    }

    @Test
    fun `close 同步收尾——队列剩余行直接导出`() {
        val exporter = OtelLogs.CollectingLogExporter()
        val logs = OtelLogs(exporter, batchSize = 8)
        logs.offer(logLine("WARN", "last-line"))
        logs.close()
        val all = exporter.bodies().joinToString("")
        assertTrue(all.contains("last-line"), "close 必须把队列剩余行送出去")
        logs.offer(logLine("INFO", "after-close")) // close 后静默忽略，不抛
    }

    // ---------------- 3. 环境装配与桥 ----------------

    @Test
    fun `环境装配——LOGS_ENDPOINT 优先于基础端点与文件`() {
        assertNull(OtelLogsEnvironment.exporterFrom(emptyMap()))

        val direct = assertIs<OtelLogs.OtlpHttpLogExporter>(
            OtelLogsEnvironment.exporterFrom(mapOf(OtelLogsEnvironment.LOGS_ENDPOINT to "http://c:4318/custom/logs")),
        )
        assertEquals("http://c:4318/custom/logs", direct.endpoint)

        val fromBase = assertIs<OtelLogs.OtlpHttpLogExporter>(
            OtelLogsEnvironment.exporterFrom(mapOf(OtelEnvironment.ENDPOINT to "http://c:4318/")),
        )
        assertEquals("http://c:4318/v1/logs", fromBase.endpoint, "基础端点追加 /v1/logs")

        val dir = Files.createTempDirectory("otel-logs-env")
        try {
            assertIs<OtelLogs.FileLogExporter>(
                OtelLogsEnvironment.exporterFrom(mapOf(OtelLogsEnvironment.LOGS_FILE to dir.resolve("l.jsonl").toString())),
            )
            val logs = assertNotNull(
                OtelLogsEnvironment.fromEnvironment(
                    mapOf(OtelLogsEnvironment.LOGS_FILE to dir.resolve("l.jsonl").toString(), OtelEnvironment.SERVICE_NAME to "custom"),
                    serviceVersion = "9.9.9",
                ),
            )
            assertEquals("custom", logs.resource["service.name"])
            assertEquals("9.9.9", logs.resource["service.version"])
            logs.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `桥——原通道逐字不变、额外投 OTel、close 还原`() {
        val collector = OtelLogs.CollectingLogExporter()
        val logs = OtelLogs(collector, batchSize = 8)
        val original = mutableListOf<String>()
        val defaultSink = StructuredLogger.globalOut() // 测试前捕获默认 stderr sink，finally 原样还原
        try {
            // 先替换全局 sink 再构造桥：桥捕获的 previous 必须是原通道
            StructuredLogger.setGlobalOut { line -> original.add(line) }
            val bridge = OtelLogBridge(logs)
            try {
                assertTrue(bridge.install(), "首次安装应成功")
                assertFalse(bridge.install(), "重复安装幂等")
                StructuredLogger.getLogger("BridgeTest").info("桥接测试消息")
                assertTrue(logs.drain(3_000))
                assertEquals(1, original.size, "原通道仍收到该行")
                assertTrue(original.single().contains("桥接测试消息"))
                val body = collector.bodies().joinToString("")
                assertTrue(body.contains("桥接测试消息"), "OTel 侧也收到")
                assertTrue(body.contains("BridgeTest"), "logger 名进 attributes")
            } finally {
                bridge.close() // 还原为 install 前的 sink（即 original 收集器）
            }
            // close 后：原通道继续工作，OTel 不再收
            StructuredLogger.getLogger("BridgeTest").info("还原后的消息")
            assertEquals(2, original.size, "close 还原后原通道继续")
            assertEquals(1, collector.bodies().size, "OTel 侧在 close 后不再收到新行（batch 已导出）")
        } finally {
            StructuredLogger.setGlobalOut(defaultSink)
            logs.close()
        }
    }
}