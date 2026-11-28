package com.stragent.data

import com.stragent.core.ports.PrefsStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-030 验收：会话注册表 + HTTP 并发。
 *
 * 计划验收口径两条：
 *   1. 并发两请求互不串数据（会话 A 换数据集不影响会话 B 的当前指针）；
 *   2. 长任务期间短请求不再排队（固定线程池替代单线程 executor）。
 */
class McpSessionConcurrencyTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun rpc(
        port: Int,
        token: String,
        method: String,
        params: String?,
        sessionId: String? = null,
    ): JsonObject {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (sessionId != null) conn.setRequestProperty(McpBridge.SESSION_HEADER, sessionId)
        conn.doOutput = true
        val body = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\"")
            if (params != null) append(",\"params\":$params")
            append("}")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        assertEquals(200, conn.responseCode)
        val text = conn.inputStream.readBytes().decodeToString()
        conn.disconnect()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun importCsv(port: Int, token: String, sessionId: String, csv: String, name: String): String {
        val call = rpc(
            port, token, "tools/call",
            """{"name":"$ABILITY_IMPORT_TABLE","arguments":{"csv":"$csv","name":"$name"}}""",
            sessionId,
        )
        val resultText = (((call["result"] as JsonObject)["content"]) as JsonArray)
            .first().let { (it as JsonObject)["text"] as JsonPrimitive }.content
        return Json.parseToJsonElement(resultText).jsonObject["datasetId"]!!
            .let { (it as JsonPrimitive).content }
    }

    // ---------- 注册表单元层 ----------

    @Test
    fun `注册表——具名会话严格分桶，默认会话回退全局`() {
        val datasets = AbilityDatasets()
        val registry = SessionRegistry(datasets)

        // 具名会话：只认自己设过的指针，不回退全局
        registry.setCurrent("A", "ds_aaa")
        assertEquals("ds_aaa", registry.currentId("A"))
        assertEquals(null, registry.currentId("B"), "B 会话不该看到 A 的指针")

        // 默认会话：registry 没有时回退 AbilityDatasets 全局「最近导入」
        datasets.put("ds_global", datasetOf("ds_global"))
        assertEquals("ds_global", registry.currentId(SessionRegistry.DEFAULT_SESSION))

        // A 换数据集不影响 B
        registry.setCurrent("B", "ds_bbb")
        registry.setCurrent("A", "ds_aaa2")
        assertEquals("ds_bbb", registry.currentId("B"))
        assertEquals("ds_aaa2", registry.currentId("A"))
    }

    @Test
    fun `会话上下文——ThreadLocal 隔离与恢复`() {
        assertEquals(SessionRegistry.DEFAULT_SESSION, McpSessionContext.current())
        McpSessionContext.with("sess-1") {
            assertEquals("sess-1", McpSessionContext.current())
            McpSessionContext.with("sess-2") {
                assertEquals("sess-2", McpSessionContext.current())
            }
            assertEquals("sess-1", McpSessionContext.current(), "内层退出后应恢复外层")
        }
        assertEquals(SessionRegistry.DEFAULT_SESSION, McpSessionContext.current())
    }

    @Test
    fun `分析工具会话——注册表接入后跨会话不串`() {
        val datasets = AbilityDatasets()
        val registry = SessionRegistry(datasets)
        val session = AnalysisToolSession(datasets, MapPrefs(), registry = registry)

        datasets.put("ds_a", datasetOf("ds_a"))
        registry.setCurrent("sess-a", "ds_a")

        McpSessionContext.with("sess-a") {
            assertEquals("ds_a", session.currentDataset().name)
        }
        // sess-b 没导入过：具名会话不回退全局，必须报「尚未导入」
        McpSessionContext.with("sess-b") {
            assertFailsWith<IllegalArgumentException> { session.currentDataset() }
        }
        // 默认会话：回退全局「最近导入」（UI 导入 → MCP 可见的既有行为）
        McpSessionContext.with(SessionRegistry.DEFAULT_SESSION) {
            assertEquals("ds_a", session.currentDataset().name)
        }
    }

    // ---------- HTTP 集成层 ----------

    @Test
    fun `HTTP 两会话并发导入互不串指针`() {
        val prefs = MapPrefs()
        val datasets = AbilityDatasets()
        val registry = SessionRegistry(datasets)
        val bridge = McpBridge(datasets, prefs, registry = registry)
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            // 会话 A 导入门店销售，会话 B 导入城市气温
            val idA = importCsv(port, token, "sess-A", "门店,销售额\\n华东,1200\\n华南,800", "门店销售")
            val idB = importCsv(port, token, "sess-B", "城市,气温\\n北京,31\\n上海,29", "城市气温")
            assertNotEquals(idA, idB)
            assertEquals(idA, registry.currentId("sess-A"))
            assertEquals(idB, registry.currentId("sess-B"))

            // 会话 A 再导入一份 → 只动 A 的指针，B 不受影响（计划验收 ①）
            val idA2 = importCsv(port, token, "sess-A", "区域,库存\\n北区,10\\n南区,20", "区域库存")
            assertEquals(idA2, registry.currentId("sess-A"))
            assertEquals(idB, registry.currentId("sess-B"), "A 换数据集不应影响 B")

            // 无会话头的请求 = default 会话：回退全局「最近导入」= A 刚导入的 A2
            assertEquals(idA2, registry.currentId(SessionRegistry.DEFAULT_SESSION))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `HTTP 并发——多线程并行请求全部完成且指针各自正确`() {
        val prefs = MapPrefs()
        val datasets = AbilityDatasets()
        val registry = SessionRegistry(datasets)
        val bridge = McpBridge(datasets, prefs, registry = registry)
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(0)
        try {
            val sessions = listOf("p1", "p2", "p3", "p4")
            val latch = CountDownLatch(1)
            val done = AtomicInteger(0)
            val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val threads = sessions.map { sid ->
                Thread {
                    latch.await()
                    repeat(3) { round ->
                        runCatching {
                            val id = importCsv(
                                port, token, sid,
                                "k,v\\na,$round\\nb,${round + 1}", "t-$sid-$round",
                            )
                            // 同会话串行：最后一轮导入后指针应指向最后一轮的数据
                            if (round == 2 && registry.currentId(sid) != id) {
                                errors.add("$sid 指针错位: ${registry.currentId(sid)} != $id")
                            }
                        }.onFailure { errors.add("$sid: ${it.message}") }
                    }
                    done.incrementAndGet()
                }.apply { start() }
            }
            latch.countDown() // 同时放行
            threads.forEach { it.join(30_000) }
            assertEquals(sessions.size, done.get(), "有线程没跑完: ${errors.joinToString()}")
            assertTrue(errors.isEmpty(), "并发导入出错: ${errors.joinToString()}")
            sessions.forEach { assertNotNull(registry.currentId(it)) }
        } finally {
            bridge.stop()
        }
    }

    private fun datasetOf(id: String) = com.stragent.core.dataset.Dataset(
        name = id,
        importedAt = 0L,
        columns = listOf("c1"),
        rows = listOf(listOf("v1")),
        columnTypes = listOf(com.stragent.core.csv.ColumnType.TEXT),
    )
}
