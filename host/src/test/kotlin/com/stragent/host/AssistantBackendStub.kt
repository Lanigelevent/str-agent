package com.stragent.host

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-060 · 「助手作为模型后端」的本地录制/回放端点（OpenAI 兼容 `/v1/chat/completions`）。
 *
 * 为什么要有它：真实模型评测需要供应商 API Key，环境里没有。T-052 的替身只在进程内
 * 直接调 [com.stragent.core.ports.LlmClient]，绕过了生产客户端与 HTTP 接线；本端点让
 * 生产 [com.stragent.data.HttpLlmClient] 真正发出 OpenAI 请求、真正解析响应，把
 * 「没有 Key」这一外部条件替换成「本地端点 + 助手撰写的回包」。
 *
 * 它**不是模型**：
 *  - 回包是 T-052 冻结语料（助手撰写）的原样回放，运行时不生成新内容；
 *  - 不证明厂商模型的质量、成本、限流或拒答率，只证明项目侧的后端接线可用；
 *  - 鉴权仍走生产路径：不到 `Bearer $API_KEY` 一律 401，客户端无 Key 时根本不会发请求。
 *
 * 每种请求各司其职：
 *  - 正文生成（请求里不含语义审查的 `issues` 模板）→ 返回 [serve] 设置的报告正文；
 *  - 语义审查（请求含 `issues` 关键词）→ 返回 `{"issues":[]}`，与 T-052 进程内替身同口径；
 *  - 记录每次调用的 body、Authorization 与 usage，供测试核对请求形态与 token 记账。
 */
internal class AssistantBackendStub : AutoCloseable {

    data class Call(
        /** report = 正文生成；review = 语义审查 */
        val kind: String,
        val authorization: String?,
        val body: String,
        val totalTokens: Int,
    )

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val recorded = CopyOnWriteArrayList<Call>()

    @Volatile
    private var report: String = ""

    /** 已收到的调用（按到达顺序） */
    val calls: List<Call> get() = recorded.toList()

    /** 传给 HttpLlmClient(baseUrlOverride = ...)；真实客户端会拼 `/chat/completions` */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            if (authorization != "Bearer $API_KEY") {
                respond(exchange, 401, """{"error":{"message":"invalid api key","type":"auth"}}""")
                return@createContext
            }
            // 注意：prompt 在 OpenAI 请求体里是转义后的字符串，`"issues"` 的两侧引号
            // 会带反斜杠（`\"issues\"`），按带引号的子串匹配不到；用裸关键词判定。
            val review = body.contains("issues")
            val content = if (review) "{\"issues\":[]}" else report
            if (content.isBlank()) {
                respond(exchange, 500, """{"error":{"message":"no scripted report","type":"server"}}""")
                return@createContext
            }
            val promptTokens = (body.length / 4).coerceAtLeast(1)
            val completionTokens = (content.length / 4).coerceAtLeast(1)
            val payload = completion(content, promptTokens, completionTokens)
            recorded += Call(
                kind = if (review) "review" else "report",
                authorization = authorization,
                body = body,
                totalTokens = promptTokens + completionTokens,
            )
            respond(exchange, 200, payload)
        }
        server.start()
    }

    /** 为下一次正文请求设置回放内容（语义审查固定回 `{"issues":[]}`） */
    fun serve(text: String) {
        report = text
    }

    override fun close() = server.stop(0)

    private fun completion(content: String, promptTokens: Int, completionTokens: Int): String =
        buildJsonObject {
            put("id", "chatcmpl-assistant")
            put("object", "chat.completion")
            put("model", "assistant-substitute")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", content)
                    })
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }.toString()

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    companion object {
        /**
         * 本地占位 Key：不是任何厂商凭据，只用于通过生产客户端的鉴权前置检查。
         * 值按夹具惯例放测试资源 `assistant-backend-stub.properties`（资源头注明用途），
         * 源码不出现凭据形状字面量；缺失直接抛错，不用默认值兜底。
         */
        val API_KEY: String by lazy {
            val props = java.util.Properties()
            AssistantBackendStub::class.java.classLoader
                .getResourceAsStream("assistant-backend-stub.properties")!!.use { props.load(it) }
            props.getProperty("apiKey")
        }
    }
}
