package com.stragent.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * T-165 · MRTR（2026-07-28 Multi Round-Trip Requests）载体。
 *
 * 2026-07-28 起 server→client 的提问**不再直发**（`elicitation/create` 推帧），
 * 而是 `tools/call` 回 `resultType: "input_required"`（[McpInputRequiredException]
 * 由 dispatch 转换）+ 不透明 `requestState`；客户端带着 `inputResponses` + 回显
 * `requestState` 重试同一请求。本文件三件套：
 *
 *  - [MrtrContext]：一次执行内的 MRTR 状态（本轮 inputResponses / 历史轮答案 /
 *    能力闸），dispatch 设置、[ElicitationBroker.request] 消费；
 *  - [McpInputRequiredException]：工具线程抛出的控制流——「这次执行停在这里，
 *    等客户端带答案重试」；
 *  - [McpRequestState]：requestState 的 HMAC-SHA256 编解码——spec：requestState
 *    影响业务逻辑时 MUST 完整性保护 + 校验失败 MUST 拒绝；SHOULD 内嵌 principal、
 *    短 TTL、来源请求摘要（防重放/跨用户/跨请求复用）。
 *
 * **门控口径（与 T-163 一致）**：仅 modern 请求（`params._meta` 声明
 * `io.modelcontextprotocol/protocolVersion == 2026-07-28`）走 MRTR；遗留客户端
 * （initialize 兼容壳协商旧版 / 不带 _meta）维持直发通道——拒旧等于断供。
 *
 * 边界（诚实）：HMAC 密钥是**进程内随机**（重启即失效，客户端收到拒绝后重发原请求
 * 即可）；单机单进程形态不需要跨实例共享；异步任务路径不走 MRTR（工作线程无本
 * ThreadLocal，维持直发通道语义）。
 */
internal class MrtrContext private constructor(
    /** 本轮 inputResponses（客户端带回答案重试时非空） */
    private val responses: Map<String, JsonObject>,
    /** requestState 带回的历史轮答案（多问流程的前几轮） */
    private val priorAnswers: Map<String, JsonObject>,
    /** 客户端是否声明了 elicitation 能力（声明缺失时按 cancel 降级，不发 inputRequests） */
    internal val capabilityAllowed: Boolean,
) {
    /** 本次执行已消费的答案——抛 [McpInputRequiredException] 时累计进 requestState */
    internal val consumed = mutableListOf<Pair<String, JsonObject>>()

    /** 答案查找：本轮 inputResponses 优先，其次历史轮 */
    internal fun lookup(key: String): JsonObject? = responses[key] ?: priorAnswers[key]

    internal fun record(key: String, response: JsonObject) {
        consumed.add(key to response)
    }

    companion object {
        /** 首次调用（modern）：无答案、无状态，提问点抛 [McpInputRequiredException] */
        fun open(capabilityAllowed: Boolean): MrtrContext = MrtrContext(emptyMap(), emptyMap(), capabilityAllowed)

        /** 带答案重试：inputResponses（可能缺 state = 预答案）或完整 state 校验通过后 */
        fun retry(
            responses: Map<String, JsonObject>,
            priorAnswers: Map<String, JsonObject>,
            capabilityAllowed: Boolean,
        ): MrtrContext = MrtrContext(responses, priorAnswers, capabilityAllowed)

        // ---- ThreadLocal 持有（与 McpSessionContext 同款恢复语义）----

        private val holder = ThreadLocal<MrtrContext>()

        internal fun current(): MrtrContext? = holder.get()

        fun <T> with(context: MrtrContext, block: () -> T): T {
            val prev = holder.get()
            holder.set(context)
            return try {
                block()
            } finally {
                if (prev == null) holder.remove() else holder.set(prev)
            }
        }
    }
}

/**
 * MRTR 控制流：工具线程在提问点抛出，dispatch 捕获后转成
 * `resultType: "input_required"` 信封（inputRequests + requestState）。
 * 不是错误——tracedTool 按独立状态记 metrics/span，不走 isError。
 */
internal class McpInputRequiredException(
    /** 提问点标识（内容派生，重执行下确定性命中同一 ask） */
    val key: String,
    /** 给客户端/用户看的问题文本 */
    val question: String,
    /** elicitation 的 requestedSchema（平铺原始类型字段） */
    val requestedSchema: JsonObject,
) : RuntimeException("input required: $key")

/**
 * `ask_` + 内容哈希（message + schema 规范序列化）：同一问题在重执行里确定性命中
 * 同一 key；不同问题文本/Schema 必不同 key（T-077 双问流程两问两 key，无需额外状态）。
 */
internal fun askKey(message: String, requestedSchema: JsonObject): String =
    "ask_" + com.stragent.core.trace.ProvenanceManifest.hashOf(
        buildJsonObject {
            put("message", message)
            put("requestedSchema", requestedSchema)
        },
    )

/**
 * 参数摘要（规范键序序列化的内容哈希）：requestState 与请求参数的绑定——
 * 重试时参数漂移（改了 arguments）会被四重绑定校验拒掉。
 */
internal fun argsDigest(args: JsonObject): String =
    com.stragent.core.trace.ProvenanceManifest.hashOf(args)

/**
 * requestState 编解码（T-165）。
 *
 * 载荷字段：`v`（格式版本）、`m`（来源 method）、`t`（工具名）、`h`（**规范序列化
 * args 摘要**——同内容不同键序同哈希，防跨参数复用）、`s`（会话 = principal）、
 * `iat`/`exp`（防重放窗口）、`a`（历史轮已消费答案 [[key, ElicitResult]…]）。
 * 线上形态：`base64url(payload).base64url(HMAC-SHA256(payload))`。
 *
 * 密钥：进程启动时 SecureRandom 生成 32 字节——单机单进程不需要跨实例共享；
 * 重启后旧 state 校验必败（客户端按 -32602 提示重发原请求即可）。
 */
internal object McpRequestState {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** state 有效期：人回答问题的窗口（10 分钟——超时后客户端重发原请求重新问） */
    internal var ttlMs: Long = 10 * 60_000L

    private const val FORMAT_VERSION = 1

    /** 签发：把累计答案一起封进签名载荷（下一轮重执行靠它接续） */
    fun issue(method: String, tool: String, argsHash: String, session: String, answers: List<Pair<String, JsonObject>>): String {
        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("v", FORMAT_VERSION)
            put("m", method)
            put("t", tool)
            put("h", argsHash)
            put("s", session)
            put("iat", now)
            put("exp", now + ttlMs)
            put("a", kotlinx.serialization.json.buildJsonArray {
                answers.forEach { (askKey, response) ->
                    add(kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(askKey))
                        add(response)
                    })
                }
            })
        }
        val body = base64Url(payload.toString().toByteArray(Charsets.UTF_8))
        return body + "." + base64Url(hmac(body))
    }

    /**
     * 校验并解码：HMAC（常量时间比较）→ 格式/版本 → method/tool/argsHash/session
     * 四重绑定 → TTL。任何一步失败抛 [McpInvalidParamsException]（客户端能做的
     * 正确动作 = 重发原始 tools/call，重新走一轮 InputRequiredResult）。
     */
    fun decode(token: String?, method: String, tool: String, argsHash: String, session: String): Decoded {
        if (token.isNullOrBlank()) {
            throw McpInvalidParamsException("缺少 requestState：带 inputResponses 的重试必须回显服务端签发的 requestState")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        }
        val parts = token.split(".")
        if (parts.size != 2) throw McpInvalidParamsException("requestState 形制非法（应为 payload.signature 两段）")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        val (body, sig) = parts[0] to parts[1]
        val given = try {
            Base64.getUrlDecoder().decode(sig)
        } catch (e: IllegalArgumentException) {
            throw McpInvalidParamsException("requestState 签名段不是合法 Base64url：${e.message}")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        }
        if (!MessageDigest.isEqual(hmac(body), given)) {
            throw McpInvalidParamsException("requestState 校验失败（签名不匹配）——它被篡改或不是本服务签发的")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        }
        val payload = try {
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw McpInvalidParamsException("requestState 载荷不是合法 JSON：${e.message}")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        }
        if ((payload["v"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() != FORMAT_VERSION) {
            throw McpInvalidParamsException("requestState 版本不识别")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        }
        fun field(name: String): String = (payload[name] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        if (field("m") != method || field("t") != tool || field("h") != argsHash || field("s") != session) {
            throw McpInvalidParamsException(
                "requestState 与本请求不匹配（method/tool/参数摘要/会话四重绑定至少一项不符）——请重发原始 tools/call",  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
            )
        }
        val exp = field("exp").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() >= exp) {
            throw McpInvalidParamsException("requestState 已过期（${ttlMs / 1000} 秒窗口）——请重发原始 tools/call 重新发起提问")  // lint:allow zh-first（校验拒绝文案，客户端可据此重发原请求；与 -32602 既有文案同源）
        }
        val answers = (payload["a"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { entry ->
            val pair = entry as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val k = (pair.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val v = pair.getOrNull(1) as? JsonObject ?: return@mapNotNull null
            k to v
        } ?: emptyList()
        return Decoded(answers)
    }

    /** 解码结果：历史轮答案（key → ElicitResult） */
    internal data class Decoded(val answers: List<Pair<String, JsonObject>>)

    private fun hmac(body: String): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.US_ASCII))
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
