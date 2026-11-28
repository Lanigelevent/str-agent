package com.stragent.data

import com.stragent.core.ports.ProgressBus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP **stdio** 传输 —— 与 HTTP 传输并列的第二条接线。
 *
 * 为什么要有它（dev-agent 形态的关键）：「**能力只打包一次（MCP 服务器），宿主各自用自家插件机制接线**」。
 * dev-agent 的评审能力是 stdio MCP 服务器：宿主一条命令拉起它、进程随宿主生死，
 * 不需要用户先手动起服务、再去别处抄 token。
 *
 * str-agent 原本只有 HTTP（127.0.0.1:7787 + Bearer），适合「独立长驻服务」；
 * 补上 stdio 后，宿主（例如 ZCode 插件市场、DeepSeek Harness、Claude Code）
 * 可以像 dev-agent 那样直接声明一条命令就把能力面挂进来。
 *
 * 协议：stdin/stdout 上**换行分隔的 JSON-RPC 2.0**（MCP stdio 传输规范：
 * 每条消息一行、行内不含裸换行、UTF-8）。与 HTTP 共用 [McpBridge.dispatch]，
 * 两种传输下的能力面与语义逐字一致。
 *
 * 鉴权：stdio 传输**不做 Bearer 校验** —— 进程边界即信任边界（进程由宿主拉起，
 * 生命周期与宿主一致），这与 MCP 官方 stdio 传输的惯例一致。HTTP 传输仍保留 Bearer。
 *
 * elicitation（T-056）：stdio 天然是双向管道——`serve()` 期间把 stdout 注册为
 * `default` 会话的服务端消息发送器，读线程**就地**路由客户端的 JSON-RPC 回包
 * （`elicitation/create` 的响应）。回包若排进请求队列，就要等正在等待它的那个调用
 * 结束才被读到，双方必然互等超时（与取消通知同一个坑）。
 *
 * ## 线程模型（T-041）
 *
 * - **读线程**（[serve] 的调用线程）：只做三件事——读行、跳过空行、把请求投进队列；
 *   `notifications/cancelled` **就地处理**。取消通知若也排队，就会排在它要取消的那个请求
 *   后面，永远不可能命中在途调用（这正是旧实现「stdio 取消不可用」的根因）。
 * - **单工作线程**：按序执行请求并写 stdout。单线程保留原有的顺序语义，也让
 *   stdout 只有这一个写者（响应与进度帧同源，不需要锁）。
 * - EOF：投毒丸（空串，空行在入队前已跳过）→ 工作线程把已排队的请求跑完再退出，
 *   与旧实现「读到 EOF 结束」一致。
 */
class McpStdioServer(
    private val bridge: McpBridge,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
    /**
     * 预计总耗时提供者（T-082 / D21 W-4）：非 null 时首个进度帧附 `estimatedTotalMs`；
     * null 返回值 = 无历史可估，该帧不带此字段。与 HTTP 的 SSE 传输同语义（跨传输一致）。
     */
    private val estimateTotalMs: (() -> Long?)? = null,
) {
    private val json = Json
    private val reader: BufferedReader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val writer: PrintWriter = PrintWriter(OutputStreamWriter(output, Charsets.UTF_8), true)

    /** 请求队列：无界（stdin 侧由宿主流量自然背压）；空串 = 结束哨兵（空行入队前已过滤） */
    private val requests = LinkedBlockingQueue<String>()

    /**
     * 服务端消息发送器（T-056 elicitation）：把 JSON-RPC 请求写 stdout。
     *
     * 与工作线程的响应/进度同源（都是 [writer]）——按行加锁写，跨线程并发只会
     * 整行交错，不会把一帧劈成两半。写失败返回 false，broker 据此如实降级。
     */
    private val sender: (String) -> Boolean = { payload ->
        synchronized(writer) {
            writer.println(payload)
            writer.flush()
            !writer.checkError()
        }
    }

    /** 阻塞式服务：读到 EOF 后等工作线程把已排队请求跑完再返回（宿主关闭 stdin 即退出） */
    fun serve() {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                while (true) {
                    val line = requests.take()
                    if (line.isEmpty()) break
                    val response = handleLine(line)
                    if (response != null) {
                        writer.println(response)
                        writer.flush()
                    }
                }
            } catch (t: Throwable) {
                // 工作线程死掉时不能假装服务还在：记录后在 serve() 返回处原样抛给调用方
                failure.set(t)
            }
        }, "mcp-stdio-worker")
        // 守护线程：宿主直接杀进程时不会因为它在 take() 上阻塞而拖住退出
        worker.isDaemon = true
        worker.start()

        // elicitation 的服务端消息通道：stdio 只有一个客户端，天然落在 default 会话
        bridge.elicitation.register(SessionRegistry.DEFAULT_SESSION, sender)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (tryHandleCancellation(line)) continue
                if (tryHandleClientResponse(line)) continue
                requests.put(line)
            }
        } finally {
            requests.put("")
            worker.join()
            bridge.elicitation.unregister(SessionRegistry.DEFAULT_SESSION, sender)
        }
        failure.get()?.let { throw it }
    }

    /**
     * 客户端对服务端请求（`elicitation/create`）的响应：就地路由，返回 true 表示已消费。
     *
     * 与取消同理必须**在读线程**处理：若排进请求队列，它要等服务端发问的那个调用
     * 结束才被读到——而那个调用正在等这条响应，必然互等超时。
     */
    private fun tryHandleClientResponse(line: String): Boolean {
        if (!line.contains("\"id\"")) return false
        val rpc = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
        if (rpc.containsKey("method")) return false
        if (!rpc.containsKey("result") && !rpc.containsKey("error")) return false
        // 响应帧一律不回收执（命不命中在途提问都一样），只交给 broker 尽力交付
        bridge.elicitation.consume(SessionRegistry.DEFAULT_SESSION, rpc)
        return true
    }

    /**
     * 取消通知就地处理；返回 true 表示已消费。
     *
     * 先用字符串预筛（绝大多数行不是取消），再解析确认「无 id + method 匹配」——
     * 宁可漏掉格式异常的取消帧（它会走正常流程被当作通知处理），也不把普通请求
     * 误判成通知而吞掉响应。
     */
    private fun tryHandleCancellation(line: String): Boolean {
        if (!line.contains("notifications/cancelled")) return false
        val rpc = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
        val method = (rpc["method"] as? JsonPrimitive)?.content ?: return false
        if (method != "notifications/cancelled" || rpc["id"] != null) return false
        // 与通知的既有语义一致：副作用执行失败不影响服务继续
        runCatching { bridge.dispatch(method, rpc["params"] as? JsonObject ?: JsonObject(emptyMap())) }
        return true
    }

    /** 处理一帧；返回 null 表示这是通知（无 id），按 MCP 语义不回响应 */
    private fun handleLine(line: String): String? {
        val rpc = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            // 坏帧：-32700，且**继续服务**（不因一帧坏数据退出）
            return errorResponse(null, -32700, "解析错误：${e.message ?: "非法 JSON"}")
        }
        val id = rpc["id"]
        val method = rpc["method"]?.let { (it as? JsonPrimitive)?.content } ?: ""
        val params = rpc["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (id == null) {
            // 通知：执行副作用但不回响应
            runCatching { bridge.dispatch(method, params) }
            return null
        }
        return try {
            val result = dispatchWithProgress(method, params, id)
            McpFrames.result(id, result).toString()
        } catch (cancelled: McpRequestCancelledException) {
            errorResponse(id, -32800, cancelled.message ?: "请求已取消")
        } catch (notFound: McpMethodNotFoundException) {
            errorResponse(id, -32601, notFound.message ?: "未知方法")
        } catch (invalid: McpInvalidParamsException) {
            errorResponse(id, -32602, invalid.message ?: "参数非法")
        } catch (e: Exception) {
            errorResponse(id, -32603, e.message ?: "内部错误")
        }
    }

    /**
     * 执行方法；客户端带了 `_meta.progressToken` 时，把过程进度回成 `notifications/progress`。
     *
     * MCP 标准做法：客户端在请求里给一个 token，服务端在过程中用同一 token 回进度帧，
     * 最后再回结果帧。客户端据此显示进度条 / 判断是否继续等。
     * 帧形制走 [McpFrames]，与 HTTP 的 SSE 传输同源（两条接线看到的进度逐字一致）。
     */
    private fun dispatchWithProgress(method: String, params: JsonObject, id: kotlinx.serialization.json.JsonElement): JsonObject {
        val requestId = id.toString()
        val token = (params["_meta"] as? JsonObject)?.get("progressToken")
        if (token == null || method != "tools/call") {
            return bridge.dispatch(method, params, requestId)
        }
        var counter = 0L
        return ProgressBus.with({ message ->
            counter += 1
            // T-082：首个进度帧带预计总耗时（与 HTTP 的 SSE 传输同语义：无历史 = 不发）
            val estimate = if (counter == 1L) estimateTotalMs?.invoke() else null
            writeProgress(token, counter, message, estimate)
        }) {
            bridge.dispatch(method, params, requestId)
        }
    }

    /** 写一帧进度通知（服务端主动发起，客户端按 progressToken 归并到对应请求） */
    private fun writeProgress(
        token: kotlinx.serialization.json.JsonElement,
        progress: Long,
        message: String,
        estimatedTotalMs: Long? = null,
    ) {
        writer.println(McpFrames.progress(token, progress, message, estimatedTotalMs).toString())
        writer.flush()
    }

    private fun errorResponse(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String =
        McpFrames.error(id, code, message).toString()
}
