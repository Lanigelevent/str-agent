package com.stragent.data

import com.stragent.core.ai.StepError
import com.stragent.core.ports.PrefsStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-133 看门狗行为测试（传输层迁移的可靠性补强）。
 *
 * **为什么用真实 HTTP 而不是内存管道**：看门狗的全部价值建立在一条契约上——
 * 「关掉响应流会打断**已经阻塞**的读」。这条契约由传输层实现（java.net.http 的响应体
 * close() → 阻塞读抛 IOException），而**不是**所有 InputStream 都有的性质：
 * PipedInputStream.close() 只置标志位，阻塞中的 read() 不会被唤醒（本地实测：读线程
 * 永远挂着）——用它当替身，测的就不是生产语义，还会把整个构建挂死。
 * 所以这里一律用真实的 java.net.http 响应体（本地 socket 桩）。
 *
 * 五例：
 * ① 停滞流——不活跃窗口到点被强制关流（阻塞读随即抛出）；
 * ② 活跃流——持续吐帧时 deadline 每读刷新，不被误杀；
 * ③ 中途停滞（已出片段、无 finish_reason）——如实报中断，不挂死；
 * ④ 契约⑤（网关发完 finish_reason 却不发 [DONE] 且把连接挂着）——有界收尾，不挂到整段超时；
 * ⑤ 尾帧迟到（usage 在 finish_reason 之后才到）——usage 仍要读到（token 账丢帧 = 假账）。
 */
class StreamWatchdogTest {

    private class MapPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /**
     * 本地 SSE 桩：读完请求后发响应头，再按 [frames] 发帧，随后**保持连接不发**。
     *
     * 用裸 socket 而非 HttpServer：要的是「服务端发完就不再发、连接还开着」——正是
     * 「网关不发 [DONE] 却把连接挂着」那种网关的形状。响应体长度报一个远大于实发量的值，
     * 客户端读端因此会一直等后续字节（这正是「停滞」的形状）。
     */
    private class StallingStub(private val frames: List<String>, private val keepAliveMs: Long = 0L) :
        AutoCloseable {

        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            val acceptor = Thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    val worker = Thread {
                        try {
                            readRequest(socket)
                            val out = socket.getOutputStream()
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                    "Content-Length: 1000000\r\n\r\n").toByteArray(Charsets.UTF_8)
                            )
                            out.flush()
                            frames.forEach { out.write(it.toByteArray(Charsets.UTF_8)); out.flush() }
                            if (keepAliveMs > 0) {
                                // 持续吐心跳：读端永远有数据，deadline 每读刷新 → 看门狗不得误杀
                                while (!socket.isClosed) {
                                    Thread.sleep(keepAliveMs)
                                    out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\".\"}}]}\n\n"
                                        .toByteArray(Charsets.UTF_8))
                                    out.flush()
                                }
                            }
                            // 不再发、也不关：连接挂着 → 客户端读端阻塞（停滞形状）
                        } catch (e: Exception) {
                            // 客户端（看门狗）提前关流时的正常收尾
                        }
                    }
                    worker.isDaemon = true
                    worker.start()
                }
            }
            acceptor.isDaemon = true
            acceptor.start()
        }

        /** 读掉请求（头 + 体）：不读干净的话客户端写请求时会阻塞 */
        private fun readRequest(socket: Socket) {
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return
                header.write(b)
                if (header.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) break
            }
            val text = header.toString(Charsets.ISO_8859_1)
            val m = Regex("(?i)content-length:\\s*(\\d+)").find(text)
            val len = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            repeat(len) { input.read() }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /** 一个 SSE 增量帧 */
    private fun chunk(content: String?, finishReason: String? = null): String {
        val delta = if (content == null) "{}" else "{\"content\":\"$content\"}"
        val reason = if (finishReason == null) "null" else "\"$finishReason\""
        return "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":$reason}]}\n\n"
    }

    /** usage 帧（choices 为空，只带 token 账） */
    private fun usage(totalTokens: Int): String =
        "data: {\"choices\":[],\"usage\":{\"total_tokens\":$totalTokens,\"completion_tokens\":$totalTokens}}\n\n"

    /** 真实 java.net.http 响应体（生产同款 delegate） */
    private fun responseBody(port: Int): InputStream {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/chat/completions"))
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true}", Charsets.UTF_8))
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofInputStream()).body()
    }

    /** 走生产客户端的流式通道（本地桩 + 注入的小不活跃窗口与尾帧上限） */
    private fun streamChat(
        port: Int,
        inactivityMs: Long,
        tailWaitMs: Long = 30_000L,
        onToken: (String) -> Unit = {},
    ) = HttpLlmClient(
        MapPrefs().apply { set(KEY, "sk-test") },
        baseUrlOverride = "http://127.0.0.1:$port",
        streamInactivityMs = inactivityMs,
        finishTailWaitMs = tailWaitMs,
    ).streamChat("m", listOf("user" to "写"), 0.3, 512, onToken)

    @Test
    fun `① 停滞流——不活跃窗口到点被强制关流，阻塞读随即抛出`() {
        // 不发任何帧：读端从第一次 read 起就阻塞（这才是「停滞」的形状）
        val stub = StallingStub(emptyList())
        val watchdog = StreamWatchdog(patrolIntervalMs = 50)
        try {
            val watched = WatchedInputStream(responseBody(stub.port), inactivityMs = 300, watchdog = watchdog)
            val start = System.currentTimeMillis()
            val error = runCatching { watched.read() }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - start
            assertTrue(error is java.io.IOException, "看门狗关流后阻塞读必须抛出（实得 ${error?.let { it::class.simpleName }}）")
            assertTrue(elapsed in 300..20_000, "关流须在不活跃窗口之后（耗时 ${elapsed}ms）")
        } finally {
            stub.close()
        }
    }

    @Test
    fun `② 活跃流——持续吐帧时 deadline 每读刷新，不被误杀`() {
        val stub = StallingStub(listOf(chunk("开头")), keepAliveMs = 50)
        val watchdog = StreamWatchdog(patrolIntervalMs = 50)
        try {
            val watched = WatchedInputStream(responseBody(stub.port), inactivityMs = 2_000, watchdog = watchdog)
            // 连续读若干字节：每读都刷新 deadline → 巡检不得把它关掉
            var read = 0
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1_500) {
                val b = watched.read()
                if (b < 0) fail("活跃流不该被读端关闭")
                read++
            }
            assertTrue(read > 10, "活跃流应持续可读（读了 $read 字节）")
            // 正常收尾即从巡检表摘除——否则长进程里每次流式调用都留一条滞留
            assertEquals(1, watchdog.trackedCount(), "在读期间应在册")
            watched.close()
            assertEquals(0, watchdog.trackedCount(), "正常收尾后不得滞留")
        } finally {
            stub.close()
        }
    }

    @Test
    fun `③ 中途停滞（已出片段、无 finish_reason）——如实报中断，不挂死`() {
        val stub = StallingStub(listOf(chunk("写了一半")))
        try {
            val tokens = mutableListOf<String>()
            val error = runCatching { streamChat(stub.port, 300) { tokens += it } }.exceptionOrNull()
            assertTrue(error is StepError, "停滞必须如实报错（实得 ${error?.let { it::class.simpleName }}）")
            assertTrue(
                error!!.message!!.contains("流式响应中断"),
                "错误要写明是流中断：${error.message}",
            )
            assertEquals(listOf("写了一半"), tokens, "中断前收到的片段不得吞掉")
        } finally {
            stub.close()
        }
    }

    @Test
    fun `④ 契约⑤——网关发完 finish_reason 却不发 DONE 且把连接挂着：有界收尾`() {
        val stub = StallingStub(listOf(chunk("收尾完整", "stop")))
        try {
            val start = System.currentTimeMillis()
            val result = streamChat(stub.port, 300, tailWaitMs = 2_000)
            val elapsed = System.currentTimeMillis() - start
            assertEquals("收尾完整", result.text, "finish_reason 即正文完成（契约⑤）")
            assertTrue(elapsed < 30_000, "有界收尾，不得挂到整段超时（耗时 ${elapsed}ms）")
        } finally {
            stub.close()
        }
    }

    @Test
    fun `⑤ 尾帧迟到——usage 在 finish_reason 之后才到，token 账仍要读到`() {
        // 桩在 finish_reason 之后隔一会儿才补 usage 帧（迟到的尾帧不得被砍掉：丢帧 = 假账）
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = server.localPort
        val worker = Thread {
            try {
                val socket = server.accept()
                val input = socket.getInputStream()
                val header = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    header.write(b)
                    if (header.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) break
                }
                val out: OutputStream = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 1000000\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.write(chunk("正文", "stop").toByteArray(Charsets.UTF_8))
                out.flush()
                Thread.sleep(2_500)          // usage 迟到 2.5 秒
                out.write(usage(321).toByteArray(Charsets.UTF_8))
                out.flush()
                Thread.sleep(30_000)         // 之后保持连接不发（DONE 缺席的网关形状）
            } catch (e: Exception) {
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val result = streamChat(port, 300, tailWaitMs = 30_000)
            assertEquals("正文", result.text)
            assertEquals(321, result.totalTokens, "迟到的 usage 帧仍要计入 token 账（丢帧 = 假账）")
        } finally {
            runCatching { server.close() }
        }
    }

    private companion object {
        const val KEY = "stepfun.apiKey"
    }
}
