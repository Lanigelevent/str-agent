package com.stragent.data

import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-136 · 传输层不变量：**「关掉响应流会打断已经阻塞的读」**。
 *
 * 看门狗（`WatchedInputStream`）的全部价值建立在这条契约上——它靠「强制 close 委托流」
 * 把一个卡死的读唤醒。若某条流**不**满足这条契约（close 只置标志位、读线程继续挂着），
 * 看门狗就是哑的：流会永远挂着，而症状是「测试卡住」而非「报错」——最难查的那类。
 *
 * 本件把这条契约**独立成件**（不经 HTTP 也能证），并**双向**记录：
 * ① 哪些流**满足**契约（可作看门狗替身）；
 * ② 哪些流**不**满足（PipedInputStream 是反例——**禁止**拿它当看门狗替身）。
 *
 * 为什么要有 ②：反例比正例更能防住下一个人再踩——上一轮 `StreamWatchdogTest` 就是因为
 * 用了 `PipedInputStream` 而把整个构建挂死（实测：构建卡在 `:data:test` 直到被强杀）。
 */
class StreamCloseContractTest {

    /**
     * 断言：close() 能打断**已经阻塞**的 read()。
     *
     * 判据用「读线程在 close 之后**有界时间内**结束」——不是「最终结束」：
     * 一个不满足契约的流会让读线程永远挂着（测试本身挂死），所以必须**有界**。
     */
    private fun assertCloseInterruptsBlockedRead(
        label: String,
        open: () -> InputStream,
    ) {
        val stream = open()
        val reader = Thread {
            runCatching { stream.read() }   // 阻塞在这里
        }
        reader.isDaemon = true
        reader.start()
        Thread.sleep(200)   // 让读线程确实阻塞进去
        val before = reader.isAlive
        stream.close()
        reader.join(3_000)
        assertTrue(before, "$label：读线程应已阻塞在 read() 里")
        assertTrue(!reader.isAlive, "$label：close() 后读线程应在 3 秒内结束（不满足则看门狗无效）")
    }

    @Test
    fun `① HTTP 响应体 close() 打断阻塞读（看门狗可用的形状）`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = server.localPort
        val worker = Thread {
            try {
                val socket = server.accept()
                val input = socket.getInputStream()
                val sink = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    sink.write(b)
                    if (sink.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) break
                }
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 100000\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                Thread.sleep(30_000)   // 发完头就沉默 → 读端阻塞
            } catch (e: Exception) {
                // 对端关流时的正常收尾
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:$port/x"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString("{}", Charsets.UTF_8))
                .build()
            val body = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()
            assertCloseInterruptsBlockedRead("java.net.http 响应体") { body }
        } finally {
            server.close()
        }
    }

    @Test
    fun `② PipedInputStream close() 不打断阻塞读（禁止作看门狗替身）`() {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val reader = Thread {
            runCatching { pipeIn.read() }
        }
        reader.isDaemon = true
        reader.start()
        // 确定性阻塞确认（D25 审查）：PipedInputStream.read() 空缓冲下阻塞在 wait() → 线程态
        // 必为 WAITING——用状态轮询替代固定 sleep，消除「未阻塞即 close」的假阴性窗口。
        // （HTTP 用例不用此法：socket 阻塞读的线程态是 RUNNABLE，与未入 read 不可区分——
        //   该用例保留 sleep(200)+close 前的 isAlive 前置断言，已知边界如实登记。）
        val blockedStates = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
        val deadline = System.nanoTime() + 5_000_000_000L
        // PipedInputStream.read() 阻塞在 wait(1000)（带超时轮询）→ 线程态 TIMED_WAITING（实测：
        // 只认 WAITING 会误判未阻塞——cleanTest 当场抓出，D25 修复实录）
        while (reader.state !in blockedStates && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(reader.state in blockedStates, "读线程应已阻塞在 wait()（5s 内未阻塞则环境异常）")
        pipeIn.close()
        reader.join(2_000)
        // 反例断言：读线程**仍然活着**——这正是上一轮把构建挂死的原因。
        // 若哪天 JDK 改了 PipedInputStream 语义（close 变得能打断读），本断言会失败，
        // 那时这条禁令可以撤销——故意钉住这个行为，别让它无声漂移。
        assertTrue(
            reader.isAlive,
            "PipedInputStream.close() 仍不打断阻塞读（若 JDK 行为已变，本断言会红 → 可撤销该禁令）",
        )
        runCatching { pipeOut.close() }
    }

    @Test
    fun `③ 端到端 看门狗对满足契约的流有效（关流后读端被唤醒）`() {
        // ① 的性质 + 看门狗的机制，两者合起来才是「停滞流会被强制中断」这个结论。
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = server.localPort
        val worker = Thread {
            try {
                val socket = server.accept()
                val input = socket.getInputStream()
                val sink = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    sink.write(b)
                    if (sink.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) break
                }
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 100000\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                Thread.sleep(30_000)
            } catch (e: Exception) {
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:$port/x"))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString("{}", Charsets.UTF_8))
                .build()
            val body = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()
            val watchdog = StreamWatchdog(patrolIntervalMs = 50)
            val watched = WatchedInputStream(body, inactivityMs = 200, watchdog = watchdog)
            val reader = Thread {
                runCatching { watched.read() }
            }
            reader.isDaemon = true
            reader.start()
            Thread.sleep(1_500)   // 远超不活跃窗口 → 巡检应已关流
            if (reader.isAlive) fail("看门狗未在窗口后强制中断阻塞读（流会永远挂着）")
        } finally {
            server.close()
        }
    }
}
