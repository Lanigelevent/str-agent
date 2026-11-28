package com.stragent.data

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.PrefsStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-048 验收：HTTP 层「长任务期间短请求不排队」（D17 G-06 的并发口径）。
 *
 * 手法是**闸门**而不是 sleep：慢工具进门先报数、然后等放行；四条慢调用全部
 * 进入工具体（= 四个核心线程都被占住）后才发 `initialize`。若执行器是单线程/
 * 有界队列，initialize 只能等慢调用结束——本测试会以读超时失败；弹性线程池
 * （核心 4、上限 64）则当场返回。最后放行，四条调用都必须正常完成。
 */
class McpConcurrencyTest {

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 慢工具：进门即 countDown，随后阻塞到放行（最多 10s 防测试悬挂） */
    private class SlowTool(
        private val started: CountDownLatch,
        private val release: CountDownLatch,
    ) : AgentTool {
        override val name: String = "test_slow"
        override val description: String = "测试工具：阻塞到放行"
        override val parametersSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val cacheable: Boolean = false
        val invocations = AtomicInteger(0)

        override fun invoke(args: JsonObject): JsonObject {
            invocations.incrementAndGet()
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            return buildJsonObject { put("ok", true) }
        }
    }

    private fun rpc(
        port: Int,
        token: String,
        body: String,
        readTimeoutMs: Int,
    ): JsonObject {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 2_000
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        assertEquals(200, conn.responseCode)
        val text = conn.inputStream.readBytes().decodeToString()
        conn.disconnect()
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `并发——四条慢调用占满核心线程时 initialize 不排队`() {
        val prefs = MapPrefs()
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val tool = SlowTool(started, release)
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(tool))
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(port = 0)
        val results = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<String>()
        val callers = (1..4).map { index ->
            Thread {
                runCatching {
                    val reply = rpc(
                        port, token,
                        """{"jsonrpc":"2.0","id":$index,"method":"tools/call","params":{"name":"test_slow","arguments":{}}}""",
                        readTimeoutMs = 15_000,
                    )
                    results.add(reply.toString())
                }.onFailure { failures.add("caller-$index: ${it.message}") }
            }.apply {
                isDaemon = true
                name = "slow-caller-$index"
                start()
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "四条慢调用应在 10s 内全部进入工具体")

            // 四个核心线程此时全部卡在慢工具里；短请求必须由弹性线程当场服务
            val begin = System.nanoTime()
            val init = rpc(
                port, token,
                """{"jsonrpc":"2.0","id":99,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
                readTimeoutMs = 2_500,
            )
            val elapsedMs = (System.nanoTime() - begin) / 1_000_000
            val serverInfo = (init["result"] as JsonObject)["serverInfo"] as JsonObject
            assertTrue(serverInfo.toString().contains("Str Agent"), "initialize 应正常应答：$init")
            assertTrue(elapsedMs < 1_500, "initialize 被慢调用排队了：${elapsedMs}ms（弹性线程池失效）")
        } finally {
            release.countDown()
            callers.forEach { it.join(15_000) }
            bridge.stop()
        }

        assertEquals(4, results.size, "四条慢调用都应完成；失败：${failures.joinToString()}")
        assertTrue(failures.isEmpty(), "慢调用出错：${failures.joinToString()}")
        assertEquals(4, tool.invocations.get(), "慢工具应被调用 4 次（缓存不得吞掉调用）")
        assertEquals(0, bridge.droppedSseFrames(), "没有 /events 订阅者时不应有丢帧")
    }

    /**
     * T-048 边界：弹性池上限 64（`SynchronousQueue` 不缓冲）。
     * 64 条长任务占满后，第 65 条**立刻被关连接**而不是无限排队——有界并发的代价；
     * 放行后服务必须照常（不能因为一次拒绝把 dispatcher 带走）。
     */
    @Test
    fun `并发上限——第 65 条快速失败而不是排队，服务不被打死`() {
        val prefs = MapPrefs()
        val started = CountDownLatch(64)
        val release = CountDownLatch(1)
        val tool = SlowTool(started, release)
        val bridge = McpBridge(AbilityDatasets(), prefs, extraTools = listOf(tool))
        val token = McpBridge.currentToken(prefs)
        val port = bridge.start(port = 0)
        val ok = ConcurrentLinkedQueue<String>()
        val failed = ConcurrentLinkedQueue<String>()
        val callers = (1..64).map { index ->
            Thread {
                runCatching {
                    val reply = rpc(
                        port, token,
                        """{"jsonrpc":"2.0","id":$index,"method":"tools/call","params":{"name":"test_slow","arguments":{}}}""",
                        readTimeoutMs = 20_000,
                    )
                    ok.add(reply.toString())
                }.onFailure { failed.add("caller-$index: ${it.message}") }
            }.apply {
                isDaemon = true
                start()
            }
        }
        try {
            try {
                assertTrue(started.await(20, TimeUnit.SECONDS), "64 条慢调用应全部进入工具体（当前还差 ${started.count} 条）")

                val begin = System.nanoTime()
                val overflow = runCatching {
                    rpc(
                        port, token,
                        """{"jsonrpc":"2.0","id":65,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
                        readTimeoutMs = 1_500,
                    )
                }
                val elapsedMs = (System.nanoTime() - begin) / 1_000_000
                val overflowStatus = overflow.getOrNull()?.toString() ?: "被拒绝（${overflow.exceptionOrNull()?.message}）"
                assertTrue(elapsedMs < 1_000, "第 65 条应快速失败，实际等了 ${elapsedMs}ms（疑似排队）")
                assertTrue(!overflowStatus.contains("Str Agent"), "第 65 条不应被受理：$overflowStatus")

                assertEquals(0, ok.size, "放行前不应有调用提前完成；失败：${failed.joinToString()}")
            } finally {
                release.countDown()
                callers.forEach { it.join(20_000) }
            }
            // 拒绝走的是「关掉那一条连接」，dispatcher 必须继续工作
            val init = rpc(
                port, token,
                """{"jsonrpc":"2.0","id":66,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
                readTimeoutMs = 3_000,
            )
            assertTrue(init.toString().contains("Str Agent"), "拒绝一条连接后服务应照常：$init")
            assertEquals(64, ok.size, "64 条慢调用都应完成；失败：${failed.joinToString()}")
            assertTrue(failed.isEmpty(), "慢调用出错：${failed.joinToString()}")
        } finally {
            bridge.stop()
        }
    }
}
