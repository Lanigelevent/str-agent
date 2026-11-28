package com.stragent.data

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** elicitation 的三种结局（MCP elicitation/create 的 `action`） */
enum class ElicitAction(val wire: String) {
    ACCEPT("accept"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

/**
 * 一次 elicitation 的结果。`reason` 在 [ElicitAction.CANCEL] 时说明为什么没能拿到回答
 * （无通道 / 超时 / 客户端报错），让模型能向用户解释而不是干等。
 */
data class ElicitOutcome(
    val action: ElicitAction,
    val content: JsonObject? = null,
    val reason: String? = null,
    /**
     * 本次提问的请求句柄（T-077：多轮续问用它复用同一 pending id）
     */
    val requestId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action.wire)
        content?.let { put("content", it) }
        reason?.let { put("reason", it) }
    }
}

/**
 * MCP **elicitation** 的双向通道（T-056 / D17 G-15）：服务端 → 客户端提问，
 * 客户端回 `accept` / `decline` / `cancel`。
 *
 * 为什么需要它：长任务中途缺一个只有人知道的信息（报告标题、口径确认、是否覆盖），
 * 现在的做法只能是猜或者失败；2026-07-28 档案的 elicitation 让服务端**问一句**，
 * 客户端决定弹不弹 UI、用户答不答。
 *
 * 传输接线（两条与 `tools/call` 共用会话语义）：
 *  - **stdio**：发送器把 JSON-RPC 请求写 stdout，读线程识别客户端回包并 [resolve]；
 *  - **HTTP**：客户端先 `GET /mcp`（Accept: text/event-stream）建立服务端消息流，
 *    发送器往 SSE 写请求；客户端把回包 POST 回 `/mcp`，由 [resolve] 收口。
 *
 * 纪律：
 *  1. **没有通道就如实降级**：返回 `cancel` + 原因（「客户端不支持 elicitation」），
 *     不阻塞、不假装成功；
 *  2. **超时即取消**（默认 [defaultTimeoutMs]）：人来不来是客户端的事，服务端不能
 *     把工作线程无限期钉住；
 *  3. **按会话隔离**：响应只能匹配本会话在途的请求（id 随机 128-bit + 会话校验）；
 *     跨会话 id 猜测既撞不上也确实会被拒。
 *
 * 边界（诚实）：这是进程内单机通道，不做跨进程转发；stdio 只有一个客户端，
 * 天然落在 `default` 会话。
 */
class ElicitationBroker(
    private val defaultTimeoutMs: Long = 60_000,
    private val generateId: () -> String = { "elicit_" + UUID.randomUUID().toString().replace("-", "") },
) {
    private class Pending(val sessionId: String, val future: CompletableFuture<JsonObject>)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** sessionId → 服务端消息发送器（返回 false = 写失败） */
    private val channels = ConcurrentHashMap<String, (String) -> Boolean>()

    /** 注册/替换会话的服务端消息发送器（stdio 工作线程 / HTTP GET 流） */
    fun register(sessionId: String, sender: (String) -> Boolean) {
        channels[sessionId] = sender
    }

    /** 注销：只移除**同一个**发送器（避免把新连接顶掉） */
    fun unregister(sessionId: String, sender: (String) -> Boolean) {
        channels.remove(sessionId, sender)
    }

    /** 该会话是否已建立服务端消息通道（诊断/测试用） */
    fun hasChannel(sessionId: String): Boolean = channels.containsKey(sessionId)

    /** 在途提问数（诊断/测试用） */
    fun pendingCount(): Int = pending.size

    /**
     * 向客户端提一个问题并等待回答（阻塞当前工具线程，最多 [timeoutMs]）。
     *
     * @param requestedSchema MCP elicitation 的 JSON Schema（`type=object`，字段平铺）
     */
    fun request(
        sessionId: String,
        message: String,
        requestedSchema: JsonObject,
        timeoutMs: Long = defaultTimeoutMs,
        /**
         * 多轮续问（T-077）：传入上一轮的 [ElicitOutcome.requestId]，第二轮复用同一 pending id
         */
        relatedId: String? = null,
    ): ElicitOutcome {
        // ---- T-165 MRTR 分支：modern 请求由 dispatch 开上下文，提问点就地转换控制流 ----
        //  1. 能力闸：客户端未声明 elicitation → 按 cancel 降级（spec：MUST NOT 发未声明
        //     能力的 inputRequests——不发，也不装作能问）；
        //  2. 有答案（本轮 inputResponses 或历史轮）→ 立即消费，不阻塞；
        //  3. 无答案 → 抛 [McpInputRequiredException]，dispatch 转成 InputRequiredResult
        //     信封（key 内容派生：同一问题重执行必命中同一 ask）。
        MrtrContext.current()?.let { ctx ->
            if (!ctx.capabilityAllowed) {
                return ElicitOutcome(
                    ElicitAction.CANCEL,
                    reason = "客户端未声明 elicitation 能力（_meta clientCapabilities），无法发起提问",  // lint:allow zh-first（降级原因文案，与既有 cancel 语义同源）
                )
            }
            val key = askKey(message, requestedSchema)
            val response = ctx.lookup(key)
            if (response != null) {
                ctx.record(key, response)
                return mrtrOutcomeOf(response)
            }
            throw McpInputRequiredException(key, message, requestedSchema)
        }
        val sender = channels[sessionId]
            ?: return ElicitOutcome(
                ElicitAction.CANCEL,
                reason = "客户端未建立服务端消息通道，不支持 elicitation" +
                    "（stdio 传输自动支持；HTTP 需要先 GET /mcp 的 SSE 流）",
            )
        val id = relatedId ?: generateId()
        val future = CompletableFuture<JsonObject>()
        pending[id] = Pending(sessionId, future)
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "elicitation/create")
            put("params", buildJsonObject {
                put("message", message)
                put("requestedSchema", requestedSchema)
            })
        }
        val sent = try {
            sender(frame.toString())
        } catch (_: Throwable) {
            false
        }
        if (!sent) {
            pending.remove(id)
            return ElicitOutcome(ElicitAction.CANCEL, reason = "向客户端发送 elicitation 请求失败", requestId = id)
        }
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val error = (result["_error"] as? JsonPrimitive)?.contentOrNull
            if (error != null) {
                ElicitOutcome(ElicitAction.CANCEL, reason = "客户端返回错误：$error", requestId = id)
            } else {
                when (val action = (result["action"] as? JsonPrimitive)?.contentOrNull) {
                    "accept" -> ElicitOutcome(ElicitAction.ACCEPT, result["content"] as? JsonObject, requestId = id)
                    "decline" -> ElicitOutcome(ElicitAction.DECLINE, result["content"] as? JsonObject, requestId = id)
                    "cancel" -> ElicitOutcome(ElicitAction.CANCEL, reason = "用户取消了提问", requestId = id)
                    else -> ElicitOutcome(ElicitAction.DECLINE, reason = "客户端返回了未知 action：${action ?: "缺失"}", requestId = id)
                }
            }
        } catch (_: TimeoutException) {
            ElicitOutcome(ElicitAction.CANCEL, reason = "客户端在 ${timeoutMs / 1000} 秒内未响应 elicitation", requestId = id)
        } catch (error: Exception) {
            ElicitOutcome(ElicitAction.CANCEL, reason = error.message ?: "等待 elicitation 响应失败", requestId = id)
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 传输层统一入口：把一帧**客户端消息**当作 elicitation 响应处理（T-056）。
     *
     * 两条传输共用这一个判定（HTTP POST /mcp 与 stdio 读线程），避免「一处只认
     * result、一处只认 error」的行为漂移。不是响应帧（带 `method`）返回 false，
     * 由传输层按原逻辑继续处理。
     */
    fun consume(sessionId: String, rpc: JsonObject): Boolean {
        if (rpc.containsKey("method")) return false
        val id = (rpc["id"] as? JsonPrimitive)?.contentOrNull ?: return false
        val result = rpc["result"] as? JsonObject
        val error = rpc["error"] as? JsonObject
        return when {
            result != null -> resolve(sessionId, id, result)
            error != null -> reject(sessionId, id, error)
            else -> false
        }
    }

    /**
     * 传输层收到客户端的 JSON-RPC 响应：命中本会话在途提问则交付并返回 true。
     * 未命中（已超时/别的会话/伪造 id）返回 false，由传输层静默丢弃。
     */
    fun resolve(sessionId: String, id: String, result: JsonObject): Boolean {
        val entry = pending[id] ?: return false
        if (entry.sessionId != sessionId) return false
        return entry.future.complete(result)
    }

    /** 客户端回的是 JSON-RPC error：同样按取消收口，保留原始 message 供解释 */
    fun reject(sessionId: String, id: String, error: JsonObject): Boolean {
        val entry = pending[id] ?: return false
        if (entry.sessionId != sessionId) return false
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "客户端返回错误"
        return entry.future.complete(buildJsonObject {
            put("action", "cancel")
            put("_error", message)
        })
    }
}

/**
 * T-165：MRTR inputResponses 的 ElicitResult → [ElicitOutcome]——与直发路径
 * **同一套结局语义**（accept/decline/cancel/未知 action），只是来源不同。
 */
internal fun mrtrOutcomeOf(response: JsonObject): ElicitOutcome {
    val action = (response["action"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return when (action) {
        "accept" -> ElicitOutcome(ElicitAction.ACCEPT, response["content"] as? JsonObject)
        "decline" -> ElicitOutcome(ElicitAction.DECLINE, response["content"] as? JsonObject)
        "cancel" -> ElicitOutcome(ElicitAction.CANCEL, reason = "用户取消了提问")  // lint:allow zh-first（MRTR 结局文案，与直发路径同源）
        else -> ElicitOutcome(ElicitAction.DECLINE, reason = "客户端返回了未知 action：${action ?: "缺失"}")  // lint:allow zh-first（MRTR 结局文案，与直发路径同源）
    }
}
