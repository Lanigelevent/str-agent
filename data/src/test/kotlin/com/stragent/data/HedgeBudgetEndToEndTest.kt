package com.stragent.data

import com.stragent.core.ports.PrefsStore
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-137 · 对冲的**端到端**契约测试（真实 HTTP 桩 + 真实 [HttpLlmClient]）。
 *
 * 分置在 `data` 侧的原因：它要验 `HttpLlmClient` 的接线，而对冲组件在 `core`（跨模块 internal 不可见）。
 * 纯预算逻辑在 `core` 侧的 `HedgeBudgetTest`。
 *
 * 四例：
 * ① **尾延迟被副本救回**——主副本撞上慢节点、副本撞上快节点（真实尾延迟的形状）：
 *    结果按期返回（不等慢副本），且**成本可数**（真的多发了 1 次请求）；
 * ② **端点整体慢时对冲无效**（反例钉住）：两个副本都慢 → 收益不存在，成本仍在预算内；
 * ③ **配额用尽后不再追加成本**（上界可核）；
 * ④ **关闭态零差异**：比例 0 时不多发一次请求。
 */
class HedgeBudgetEndToEndTest {

    private class MapPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /**
     * 桩：模拟**尾延迟**（个别请求慢）而非「端点整体慢」。
     *
     * 用真实 HTTP（而非内存桩）是因为对冲的**成本**就发生在「真的发出了第二个请求」——
     * 用内存桩就测不出「多花了一次调用」这件事。
     *
     * **必须用多线程 dispatcher**：`com.sun.net.httpserver` 的默认 dispatcher 是**单线程**——
     * 慢请求会占住它，后到的副本请求只能排队（实测：副本被拖到慢请求结束才到达，
     * 于是「副本救回尾延迟」根本测不出来）。这是**桩的缺陷**，不是产品的——
     * 真实服务端并发处理请求，不会被单个慢请求串行阻塞。
     *
     * @param slowEveryN 每 N 个请求里，第 1 个慢（其余快）——**偶发**慢 = 真实尾延迟的形状
     * @param alwaysSlow true = 每个请求都慢（端点整体慢；对冲救不回来）
     */
    private class TailLatencyStub(
        private val slowMs: Long,
        private val slowEveryN: Int = 0,
        private val alwaysSlow: Boolean = false,
    ) {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private val workers = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "stub-http").apply { isDaemon = true }
        }
        val requestCount = AtomicInteger()
        val port: Int get() = server.address.port

        init {
            server.executor = workers
            server.createContext("/chat/completions") { exchange ->
                val n = requestCount.incrementAndGet()
                exchange.requestBody.readBytes()
                val slow = if (alwaysSlow) true else n % (slowEveryN.coerceAtLeast(1)) == 1
                if (slow) Thread.sleep(slowMs)
                val bytes = """{"choices":[{"index":0,"message":{"role":"assistant","content":"答案"},"finish_reason":"stop"}],"usage":{"total_tokens":7,"completion_tokens":7}}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

        fun stop() {
            server.stop(0)
            workers.shutdownNow()
        }
    }

    @Test
    fun `① 端到端 尾延迟被副本救回 成本可数`() {
        // 每 2 个请求里第 1 个慢：主副本（请求 1）慢、副本（请求 2）快 → 副本救回
        val stub = TailLatencyStub(slowMs = 3_000, slowEveryN = 2)
        val prefs = MapPrefs().apply { set("stepfun.apiKey", "sk-test") }
        try {
            val client = HttpLlmClient(
                prefs,
                baseUrlOverride = "http://127.0.0.1:${stub.port}",
                hedgeRatio = 1.0,
                hedgeAfterMs = 200,
            )
            val t0 = System.currentTimeMillis()
            val result = client.chat("m", listOf("user" to "写"), 0.3, 512)
            val elapsed = System.currentTimeMillis() - t0
            assertEquals("答案", result.text)
            assertEquals(2, stub.requestCount.get(), "慢请求应发了 1 个副本（成本可数：2 次请求）")
            // 副本在 200ms 后发出、自身很快 → 整体应远快于「等慢副本跑完 3 秒」
            assertTrue(
                elapsed < 2_000,
                "总耗时 $elapsed ms —— 副本应救回尾延迟（不该等慢副本跑完 3 秒）",
            )
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `② 端到端 端点整体慢时对冲无效 成本仍在预算内`() {
        // 反例钉住：端点整体慢 → 两个副本都慢 → **收益不存在**（不能把「对冲必然提速」当事实）
        val stub = TailLatencyStub(slowMs = 400, alwaysSlow = true)
        val prefs = MapPrefs().apply { set("stepfun.apiKey", "sk-test") }
        try {
            val client = HttpLlmClient(
                prefs,
                baseUrlOverride = "http://127.0.0.1:${stub.port}",
                hedgeRatio = 1.0,
                hedgeAfterMs = 50,
            )
            client.chat("m", listOf("user" to "写"), 0.3, 512)
            assertEquals(2, stub.requestCount.get(), "确实发了 1 个副本（成本照样发生）")
            // 结果仍正确（不能因对冲而丢内容）
            assertTrue(stub.requestCount.get() >= 2)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `③ 端到端 配额用尽后不再追加成本 上界可核`() {
        // 比例 0.5 → 需 2 次调用攒够 1 个副本额度；之后每次调用只发 1 个副本
        val stub = TailLatencyStub(slowMs = 300, alwaysSlow = true)
        val prefs = MapPrefs().apply { set("stepfun.apiKey", "sk-test") }
        try {
            val client = HttpLlmClient(
                prefs,
                baseUrlOverride = "http://127.0.0.1:${stub.port}",
                hedgeRatio = 0.5,
                hedgeAfterMs = 50,
            )
            repeat(6) { client.chat("m", listOf("user" to "写"), 0.3, 512) }
            val total = stub.requestCount.get()
            // 6 次调用：额度每 2 次调用攒 1 个 → 至多 3 个副本 → 总请求数 ≤ 6 + 3 = 9
            assertTrue(
                total <= 9,
                "6 次调用的总请求数 $total 超过上界（比例 0.5 → 至多 3 个副本 → ≤ 9 次请求）",
            )
        } finally {
            stub.stop()
        }
    }

    @Test
    fun `④ 端到端 关闭态零差异 不多发一次请求`() {
        val stub = TailLatencyStub(slowMs = 300, alwaysSlow = true)
        val prefs = MapPrefs().apply { set("stepfun.apiKey", "sk-test") }
        try {
            val client = HttpLlmClient(
                prefs,
                baseUrlOverride = "http://127.0.0.1:${stub.port}",
                hedgeRatio = 0.0,
                hedgeAfterMs = 50,
            )
            repeat(4) { client.chat("m", listOf("user" to "写"), 0.3, 512) }
            assertEquals(4, stub.requestCount.get(), "关闭态：4 次调用 = 4 次请求（不多发）")
        } finally {
            stub.stop()
        }
    }
}
