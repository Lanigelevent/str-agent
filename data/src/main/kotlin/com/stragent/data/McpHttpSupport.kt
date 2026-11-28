package com.stragent.data

import com.stragent.core.i18n.Messages
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonObject

/**
 * MCP 桥的 HTTP 支撑层（T-094 拆分单元）：传输主体（McpBridge 剩余成员）与鉴权、UI 协作单元
 * 共用的无状态 HTTP 写出与请求辅助。
 *
 * **行为零变化**：成员自 McpBridge 逐字迁出（T-094 第 2 步），单元边界记录见 docs/D7-tasks.md T-094。
 */

/** 每连接事件队列容量：满则丢帧计数，不阻塞生成主链 */
internal const val SSE_QUEUE_CAPACITY = 256

/** 心跳间隔（客户端/代理保活） */
internal const val SSE_HEARTBEAT_MS = 15_000L

/** 数据库/客户端断开检测的轮询粒度：stop() 可在 ~0.5s 内收掉 SSE 循环线程 */
internal const val SSE_POLL_MS = 500L

/** SSE 心跳帧（/mcp 服务端消息流与 /events 事件流共用） */
internal const val HEARTBEAT_FRAME = "data: {\"type\":\"heartbeat\"}\n\n"

/**
 * JSON 响应写出（鉴权/UI/传输三单元共用）：204 无响应体，其余 `application/json; charset=utf-8`。
 *
 * 204 No Content 按 RFC 9110 不带响应体（-1 = 不写 body）。通知本来就没有回包内容，
 * 多写一个 `{}` 会让严格客户端把「通知的确认」误解成一条 JSON-RPC 回执。
 */
internal fun respond(exchange: HttpExchange, status: Int, body: JsonObject) {
    if (status == 204) {
        exchange.sendResponseHeaders(204, -1)
        exchange.responseBody.close()
        return
    }
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    // T-156：安全响应头（D24 审查 P2-14）——全 JSON 响应统一 nosniff + 禁 iframe；
    // /ui 的 HTML 响应在 McpUiEndpoints 单独带 CSP（内联脚本/样式例外）。
    exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
    exchange.responseHeaders.set("X-Frame-Options", "DENY")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

/** 事件流所属会话：头优先、查询参数兜底（浏览器）、缺省 default */
internal fun eventSessionOf(exchange: HttpExchange): String =
    exchange.requestHeaders.getFirst(McpBridge.SESSION_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
        ?: queryParam(exchange, "sessionId")?.trim()?.takeIf { it.isNotEmpty() }
        ?: SessionRegistry.DEFAULT_SESSION

/** 解析一个查询参数（URL 解码；不存在返回 null） */
internal fun queryParam(exchange: HttpExchange, name: String): String? {
    val raw = exchange.requestURI.rawQuery ?: return null
    for (part in raw.split('&')) {
        val index = part.indexOf('=')
        val key = if (index < 0) part else part.substring(0, index)
        if (key != name) continue
        val value = if (index < 0) "" else part.substring(index + 1)
        return runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrNull()
    }
    return null
}
