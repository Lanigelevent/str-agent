package com.stragent.data

import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import kotlinx.serialization.json.JsonElement
import com.stragent.core.observability.StructuredLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 帧的**唯一定义源**——stdio 与 HTTP(SSE) 两条传输共用这里的构造函数。
 *
 * 为什么要有这个文件（C-12 单一权威源）：进度通知的语义（token 回显、progress 单调、
 * message 原样）是客户端解析的依据。两条传输各写一遍 JSON 骨架，迟早漂成两种形状，
 * 出现「stdio 能用、http 不能用」这类最难查的假故障；现在只有一份，且有一条
 * 跨传输对拍测试钉住它。
 */
internal object McpFrames {

    /**
     * 服务端主动发起的进度通知（客户端按 progressToken 归并到对应请求）。
     *
     * [estimatedTotalMs]（T-082 / D21 W-4）：本次调用的预计总耗时（ms），由传输层在
     * **首个进度帧**带出（后续帧不再重复——估计只在开始时有信息量）。null = 无历史
     * 可估（如实不发，不编造一个数）。
     */
    fun progress(
        token: JsonElement,
        progress: Long,
        message: String,
        estimatedTotalMs: Long? = null,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/progress")
        put(
            "params",
            buildJsonObject {
                put("progressToken", token)
                put("progress", progress)
                put("message", message)
                estimatedTotalMs?.let { put("estimatedTotalMs", it) }
            },
        )
    }

    /** 请求的结果帧（id 原样回显） */
    fun result(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    /**
     * 错误帧（id 可为 null——坏帧场景）。[data] 承载协议错误的扩展字段
     * （-32022 的 supported/requested 等），进 `error.data`——JSON-RPC 的标准位置。
     */
    fun error(id: JsonElement?, code: Int, message: String, data: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            },
        )
    }
}

/**
 * HTTP 传输的 **SSE 渐进响应写出器**（MCP Streamable HTTP：POST 的响应可以是
 * `text/event-stream`，服务端在同一条流上先发若干通知帧、最后发该请求的结果帧）。
 *
 * 三条纪律：
 *   1. **一帧一 `data:` 行**：JSON 序列化把换行转义成 `\n` 字面量，正文流式片段里
 *      的真换行不会破坏帧边界；
 *   2. **每帧即刷**：客户端要的是实时性，攒批等于没流式；
 *   3. **断开不致命**：客户端提前关连接只影响「后面的帧发不出去」，
 *      工具照常执行完、溯源照常记录——进度是增强，不是依赖（与 ProgressBus 同口径）。
 */
internal class SseProgressWriter(exchange: HttpExchange) {
    private val out = exchange.responseBody
    private val log = StructuredLogger.getLogger("SseProgressWriter")

    /** 通道是否已断（写失败后为 true，后续帧静默丢弃而不是抛穿执行链） */
    var broken: Boolean = false
        private set

    /** 已写出帧数（诊断用；断开后的静默帧不计） */
    var framesWritten: Int = 0
        private set

    init {
        exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        // length=0 → chunked：没有固定长度，才能边算边发
        exchange.sendResponseHeaders(200, 0)
    }

    /** 写一帧（SSE 的一条消息 = `data: <json>` + 空行） */
    fun frame(message: JsonObject) {
        if (broken) return
        val payload = "data: $message\n\n".toByteArray(Charsets.UTF_8)
        try {
            out.write(payload)
            out.flush()
            framesWritten += 1
        } catch (error: IOException) {
            broken = true
            log.warn("SSE 通道断开，后续帧不再发送", "error" to (error.message ?: error.javaClass.simpleName))
        }
    }

    /** 结束流（真正的关闭由调用方的 exchange.close() 完成，这里只做语义标记） */
    fun finish() {
        runCatching { out.close() }
    }
}
