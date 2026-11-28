package com.stragent.data

import com.stragent.core.i18n.Messages
import com.stragent.core.observability.StructuredLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 桥的 UI 端点单元（T-094 拆分单元，自 [McpBridge] 迁出）：`/ui` 单页应用、
 * `/events` SSE 事件流与 `/ui/data/<name>` 驾驶舱数据端点的处理器与注册。
 *
 * SSE 资源预算（连接额度/活跃数/拒绝数/丢帧计数）由桥持有，注入共享——
 * 与 /mcp 服务端消息流同池记账；`running` 由桥的传输状态注入（stop() 后 SSE 循环退出）。
 *
 * **行为零变化**：成员自 McpBridge 逐字迁出（T-094 第 4 步），单元边界记录见 docs/D7-tasks.md T-094。
 */

/** /ui/call 的体上限与固定会话（T-123） */
private const val MAX_UI_CALL_BYTES = 16 * 1024 * 1024
private const val UI_SESSION_ID = "ui"

internal class McpUiEndpoints(
    private val webUiHtml: String?,
    private val uiDataProvider: UiDataProvider?,
    private val authGate: McpAuthGate,
    private val running: () -> Boolean,
    /** T-123 写侧闭环：UI 发起 tools/call 的分发器（桥注入；null = /ui/call 不注册） */
    private val uiCallDispatcher: ((method: String, params: JsonObject) -> JsonObject)?,
    private val sseSlots: Semaphore,
    private val sseActive: AtomicInteger,
    private val sseRejected: AtomicLong,
    private val sseDroppedFrames: AtomicLong,
    private val log: StructuredLogger,
) {
    /** 注册 /ui、/events、/ui/data 系列三个端点（webUiHtml 为 null = 未启用 UI，零注册） */
    internal fun registerInto(http: HttpServer, token: String) {
        if (webUiHtml == null) return
        http.createContext("/ui") { exchange -> handleUi(exchange, token) }
        http.createContext("/events") { exchange -> handleEvents(exchange, token) }
        // T-070：驾驶舱数据端点（鉴权同 /events——数据集名与报告正文是敏感面）
        uiDataProvider?.let { provider ->
            http.createContext("/ui/data/overview") { exchange ->
                handleUiData(exchange, token) { provider.overview() }
            }
            http.createContext("/ui/data/datasets") { exchange ->
                handleUiData(exchange, token) { provider.datasets() }
            }
            http.createContext("/ui/data/reports") { exchange ->
                handleUiData(exchange, token) { provider.reports() }
            }
        }
        // T-123 写侧闭环：浏览器直接驱动 导入→报告→追问（固定 ui 会话——SSE ?sessionId=ui 可看进度）
        if (uiCallDispatcher != null) {
            http.createContext("/ui/call") { exchange ->
                handleUiCall(exchange, token)
            }
        }
    }

    /** GET /ui —— 返回 Web UI 单页应用（无需 MCP token，浏览器直接访问） */
    private fun handleUi(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "GET") {
            respond(exchange, 405, buildJsonObject { put("error", Messages.get("error.get_only")) })
            return
        }
        val html = webUiHtml ?: ""
        val bytes = html.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        // T-156：单文件仪表盘（零外部资源，T-070）——CSP 收紧到内联例外
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("X-Frame-Options", "DENY")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    /**
     * POST /ui/call —— 写侧闭环（T-123）：浏览器以同一套 Bearer 鉴权直接发起 tools/call。
     * 固定 `ui` 会话分发（进度经 UiEventBroadcaster → `/events?sessionId=ui` 可见）；
     * 请求体 `{name, arguments}`，响应为 tools/call 的完整回执信封（isError 形制随工具语义）。
     * 体上限 16MB（与文本导入同级）；未知工具/参数错误走 dispatch 的标准错误信封（200 isError）。
     */
    private fun handleUiCall(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, buildJsonObject { put("error", Messages.get("error.get_only")) })
            return
        }
        if (authGate.ensureAuthorized(exchange, token, allowQueryToken = true) == null) {
            return
        }
        val dispatcher = uiCallDispatcher ?: run {
            respond(exchange, 503, buildJsonObject { put("error", "not_wired") })
            return
        }
        val raw = exchange.requestBody.use { it.readNBytes(MAX_UI_CALL_BYTES) }
        if (raw.size >= MAX_UI_CALL_BYTES) {
            respond(exchange, 413, buildJsonObject { put("error", "body_too_large"); put("limitBytes", MAX_UI_CALL_BYTES) })
            return
        }
        val parsed = runCatching { Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
        if (parsed == null || parsed["name"] !is JsonPrimitive) {
            respond(exchange, 400, buildJsonObject { put("error", "invalid_body"); put("expect", "{name, arguments}") })
            return
        }
        val toolName = (parsed["name"] as JsonPrimitive).content
        val args = parsed["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val envelope = McpSessionContext.with(UI_SESSION_ID) {
            dispatcher("tools/call", buildJsonObject {
                put("name", toolName)
                put("arguments", args)
            })
        }
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    /**
     * GET /events —— SSE 事件流（订阅 ProgressBus 经 UiEventBroadcaster 推送的事件）。
     *
     * 鉴权（T-045）：强制 Bearer。浏览器的 `EventSource` 不能设请求头，故额外接受
     * `?token=`（事件查看器的迁移代价，AD-029）。无 token / token 错误 → 401。
     *
     * 隔离：事件流绑定一个会话（`?sessionId=` 或 `Mcp-Session-Id`，缺省 default）；
     * 只转发该会话的事件，A 会话的进度正文不会出现在 B 的流里。
     *
     * 资源：连接额度 [McpBridge.MAX_SSE_CONNECTIONS]，满则 429（带 Retry-After）而不是排队；
     * 每连接有界队列，满时丢帧计数——生成主链永远不因慢消费者阻塞。
     */
    /**
     * GET /ui/data/<name>（T-070）：驾驶舱数据端点。鉴权同 /events
     * （Bearer 或 `?token=`——页面 fetch 用与 /ui 相同的 token 查询参数）。
     */
    private fun handleUiData(exchange: HttpExchange, token: String, data: () -> JsonObject) {
        if (exchange.requestMethod != "GET") {
            respond(exchange, 405, buildJsonObject { put("error", Messages.get("error.get_only")) })
            return
        }
        if (authGate.ensureAuthorized(exchange, token, allowQueryToken = true) == null) {
            return
        }
        val body = data()
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun handleEvents(exchange: HttpExchange, token: String) {
        if (exchange.requestMethod != "GET") {
            respond(exchange, 405, buildJsonObject { put("error", Messages.get("error.get_only")) })
            return
        }
        if (authGate.ensureAuthorized(exchange, token, allowQueryToken = true) == null) {
            return
        }
        if (!sseSlots.tryAcquire()) {
            sseRejected.incrementAndGet()
            exchange.responseHeaders.set("Retry-After", "1")
            respond(exchange, 429, buildJsonObject {
                put("error", Messages.get("error.rate_limit.exceeded"))
                put("retryAfter", "1")
                put("limit", McpBridge.MAX_SSE_CONNECTIONS)
            })
            return
        }
        sseActive.incrementAndGet()
        try {
            val sessionId = eventSessionOf(exchange)
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.responseHeaders.set("Cache-Control", "no-cache")
            exchange.responseHeaders.set("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0L)
            val out = exchange.responseBody
            val queue = SseEventQueue(SSE_QUEUE_CAPACITY)
            val unsubscribe = com.stragent.core.ports.UiEventBroadcaster.subscribe(sessionId) { payload ->
                // 有界队列 + 非阻塞 offer：慢消费者只丢自己的帧，不拖生成主链
                queue.offer(payload)
            }
            try {
                var lastHeartbeat = System.nanoTime()
                while (running()) {
                    val payload = queue.poll(SSE_POLL_MS)
                    if (payload == null) {
                        val now = System.nanoTime()
                        if (now - lastHeartbeat >= SSE_HEARTBEAT_MS * 1_000_000) {
                            out.write(HEARTBEAT_FRAME.toByteArray(Charsets.UTF_8))
                            out.flush()
                            lastHeartbeat = now
                        }
                    } else {
                        out.write(("data: " + payload + "\n\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            } catch (_: java.io.IOException) {
                // Client disconnected — normal for SSE
            } catch (_: InterruptedException) {
                // 服务停止（stop() 中断轮询）——正常退出
                Thread.currentThread().interrupt()
            } finally {
                unsubscribe()
                sseDroppedFrames.addAndGet(queue.droppedCount())
                if (queue.droppedCount() > 0) {
                    log.warn(
                        "SSE 慢消费者丢帧",  // lint:allow zh-first（用户面中文输出，迁自 McpBridge 原基线）
                        "session" to sessionId,
                        "dropped" to queue.droppedCount().toString(),
                    )
                }
                runCatching { exchange.close() }
            }
        } finally {
            sseActive.decrementAndGet()
            sseSlots.release()
        }
    }
}
