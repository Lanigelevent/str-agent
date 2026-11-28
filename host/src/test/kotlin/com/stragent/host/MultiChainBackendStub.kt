package com.stragent.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-135 · 「助手作为模型后端」的**多链样本回放端点**（OpenAI 兼容 `/chat/completions`）。
 *
 * ## 它接的是 T-060 的哪一棒
 *
 * T-060 已证明「生产 `HttpLlmClient` 真的发 OpenAI 请求、真的解析响应」这条链路可用，
 * 但它**只服务一种内容**（报告正文），且**不覆盖 SSE 流式**、不覆盖四链。
 * T-135 的判据是「四条链（检索 / 分解 / 摘要 / OCR / 适配）各 ≥ 一份**端点级**应答抽样」，
 * 故本端点把回放面扩到**按请求内容路由的多链样本**，并补上**真流式**通道。
 *
 * ## 它**不是**厂商模型（诚实边界，报告须自带）
 *
 * - 回包是**助手撰写的冻结应答**，运行时不生成新内容 → **不能**据此给出「厂商模型质量结论」；
 * - 本端点证明的是**端点级**的事：真实 HTTP、真实 Bearer、真实 OpenAI 协议、
 *   真实 usage 记账、真实 SSE 分片（增量拼接 = 最终正文这条硬契约在**真流**上验）、
 *   真实 vision 协议（`image_url` 内容块）与真实 manifest 哈希；
 * - 它**不**证明：厂商模型的质量、成本、限流、拒答率、多模型差异。
 *
 * 这些边界写进 D7 T-135 与效果报告的「边界（诚实）」段——**不因跑了就声称达成厂商级结论**。
 *
 * ## 路由（按 system prompt 的角色标识，不靠调用顺序）
 *
 * 生产链的 prompt 互不相同（各链自带角色说明），据此路由比「按第几次调用」更接近真实：
 * 真实端点也是**按内容**回答的，不会因为「这是第 3 次调用」就换内容。
 *
 * | 链 | 判据（system prompt 特征） | 用途 |
 * |---|---|---|
 * | 分解 | 含「检索查询分解器」 | T-113 隐式多跳 |
 * | 摘要-map | 含「严谨的文本分析员」 | T-096 逐块要点 |
 * | 摘要-归并 | 含「总结撰写人」 | T-096 终稿 |
 * | 摘要-润色 | 含「密度优化」 | T-096 CoD |
 * | 检索-追问 | 含「文档分析员」或「数据分析师」 | 追问链 |
 * | 审查 | 含审查模板特征 `"issues":[{"kind"` | 语义审查（回 `{"issues":[]}`） |
 * | OCR | 请求含 `image_url` 内容块 | 视觉链 |
 * | 报告 | 其余（正文生成） | 适配链 |
 *
 * **审查判据不能用裸 `issues`**：报告链内部的「资深数据分析师」prompt 也含 `issues` 字样
 * → 会被误判成审查 → 报告章节拿到 `{"issues":[]}` → 报告只剩 13 字（D5 TS-048）。
 * 故用审查模板的**特征串**（`kind`/`quote`/`why` 三字段）。
 *
 * **报告链用「声明模式」而非内容路由**：它内部会复用多种角色提示词（文本分析员、数据分析师），
 * 内容路由在那里**原理上**不可靠；声明按「下一次请求」粒度给出期望链（D5 TS-049）。
 *
 * ## 流式
 *
 * `stream=true` 时按 SSE 分片下发（`data: {...}` + `data: [DONE]`），
 * 分片边界落在**中文标点与换行**处——真实端点也是这么切的（按 token/字边界），
 * 故「增量拼接 = 最终正文」这条硬契约在**真流**上被验证，而不是在内存替身上自证。
 */
internal class MultiChainBackendStub : AutoCloseable {

    /** 一条链的冻结应答 */
    data class Sample(
        /** 链标识（见类注释的路由表） */
        val chain: String,
        /** 该链的应答正文 */
        val content: String,
    )

    /** 记录到的一次调用 */
    data class Call(
        /** 路由到的链 */
        val chain: String,
        val authorization: String?,
        val body: String,
        /** 是否流式 */
        val stream: Boolean,
        val totalTokens: Int,
    )

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val recorded = CopyOnWriteArrayList<Call>()

    /** 各链的冻结应答（按链标识索引；同链多条时按到达顺序依次取用） */
    private val samples = LinkedHashMap<String, ArrayDeque<Sample>>()

    /** 循环应答的链（用完回到队首，不耗尽） */
    private val cyclic = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** 已收到的调用（按到达顺序） */
    val calls: List<Call> get() = recorded.toList()

    /** 传给 HttpLlmClient(baseUrlOverride = ...) / OpenAiCompatibleVisionClient */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        // 多线程 dispatcher：慢请求不得阻塞后到的请求——T-135 的语义链就是「多条链并发抽样的真实形态」
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "t135-backend").apply { isDaemon = true }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            handle(exchange)
        }
        server.start()
    }

    /** 登记一条链的冻结应答 */
    fun serve(chain: String, content: String) {
        samples.getOrPut(chain) { ArrayDeque() }.addLast(Sample(chain, content))
    }

    /**
     * 登记一条链的**循环**应答（用完回到队首）。
     *
     * 真实端点对同一意图反复提问会反复作答（不会「用完就没了」）；本端点照此语义，
     * 以免测试必须精确预测调用次数——**那会让测试脆到只剩「调用次数」这一个断言**。
     */
    fun serveCyclic(chain: String, content: String) {
        serve(chain, content)
        cyclic.add(chain)
    }

    /** 登记多条 */
    fun serveAll(entries: List<Sample>) {
        entries.forEach { serve(it.chain, it.content) }
    }

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        if (authorization != "Bearer $API_KEY") {
            respondJson(exchange, 401, """{"error":{"message":"invalid api key","type":"auth"}}""")
            return
        }
        val chain = route(body)
        val sample = takeSample(chain)
        if (sample == null) {
            respondJson(exchange, 500, """{"error":{"message":"no scripted sample for chain $chain","type":"server"}}""")
            return
        }
        val stream = body.contains("\"stream\":true")
        val promptTokens = (body.length / 4).coerceAtLeast(1)
        val completionTokens = (sample.content.length / 4).coerceAtLeast(1)
        recorded += Call(
            chain = chain,
            authorization = authorization,
            body = body,
            stream = stream,
            totalTokens = promptTokens + completionTokens,
        )
        if (stream) {
            respondStream(exchange, sample.content, promptTokens, completionTokens)
        } else {
            respondJson(
                exchange,
                200,
                completion(sample.content, promptTokens, completionTokens),
            )
        }
    }

    /**
     * 取一份该链的应答。
     *
     * **循环链**（[serveCyclic] 登记的）：用完回到队首——真实端点对同一意图会反复作答，
     * 照此语义可让测试不必精确预测调用次数（否则测试脆到只剩「调用次数」一个断言）。
     */
    private fun takeSample(chain: String): Sample? {
        val queue = samples[chain] ?: return null
        synchronized(queue) {
            val s = queue.removeFirstOrNull() ?: return null
            if (chain in cyclic) queue.addLast(s)
            return s
        }
    }

    /**
     * 按请求内容路由到链。
     *
     * 判据是**请求里出现的角色标识**（真实端点也是按内容回答的）；
     * 顺序有意义：更具体的判据在前。
     *
     * **但报告链会复用别的链的角色提示词**（报告生成内部也调「文本分析员」与
     * 「数据分析师」），纯内容路由会把它们误判到摘要/检索链。故本端点支持
     * **调用方声明模式**（[expect]）：测试在进入某条链前声明「接下来的请求属于哪条链」，
     * 端点在该模式下直接按声明应答——这更接近真实端点的行为（真实端点也按**本次会话
     * 的意图**回答，不会因为 prompt 里出现某个角色词就换内容）。
     *
     * 未声明时退回内容路由（供不想声明的用例用）。
     */
    private fun route(body: String): String {
        // 视觉链永远按内容判定（image_url 内容块唯一）
        if (body.contains("image_url")) return CHAIN_OCR
        // 声明模式优先：取一次就少一次；用完回到按内容路由
        // （跨线程可见——服务端在工作线程上处理，ThreadLocal 传不过来：实测声明完全没生效）
        val declared = expected.getAndUpdate { cur ->
            if (cur == null) null else if (cur.left <= 1) null else Expected(cur.chain, cur.left - 1)
        }
        if (declared != null) {
            // 声明了链，但**审查请求必须仍走审查链**（它要回 JSON，正文回过去会解析失败）。
            // 判据用审查模板的**特征串** `"issues":[{"kind"`（审查专有；
            // 裸 `issues` 会被报告链的「数据分析师」prompt 命中——实测）。
            return if (isReviewRequest(body)) CHAIN_REVIEW else declared.chain
        }
        return when {
            isReviewRequest(body) -> CHAIN_REVIEW
            body.contains("检索查询分解器") -> CHAIN_DECOMPOSE
            body.contains("严谨的文本分析员") -> CHAIN_SUMMARY_MAP
            body.contains("总结撰写人") -> CHAIN_SUMMARY_MERGE
            body.contains("密度优化") -> CHAIN_SUMMARY_COD
            body.contains("文档分析员") || body.contains("数据分析师") -> CHAIN_RETRIEVAL
            else -> CHAIN_REPORT
        }
    }

    /**
     * 是不是**语义审查**请求？
     *
     * 判据取审查模板的**特征串** `"issues":[{"kind"`（审查专有），**不用裸 `issues`**——
     * 裸 `issues` 会被报告链内部的「数据分析师」prompt 命中（实测：报告正文被判成审查，
     * 报告只剩 13 字的 `{"issues":[]}`）。
     */
    private fun isReviewRequest(body: String): Boolean =
        body.contains("\"issues\":[{\"kind\"") || body.contains("issues\":[{\"kind\"")

    /**
     * 调用方声明的「接下来这些请求属于哪条链」（null = 按内容路由）。
     *
     * **必须是跨线程可见的**：HTTP 处理跑在服务端的工作线程上，与发起调用的线程**不是同一个**
     * （实测：ThreadLocal 版声明完全没生效——报告链的请求仍按内容路由跑偏）。
     * 故用 `AtomicReference` + 计数。
     */
    private val expected = java.util.concurrent.atomic.AtomicReference<Expected?>(null)

    private data class Expected(val chain: String, val left: Int)

    /**
     * 声明接下来 [count] 个请求属于 [chain]。
     *
     * 用于**报告链这类会复用多种角色提示词**的场景：内容路由在那里不可靠
     * （报告生成内部也会调「文本分析员」「数据分析师」，实测其 prompt 还会命中
     * 审查判据 `issues`）。
     */
    fun expect(chain: String, count: Int) {
        expected.set(Expected(chain, count))
    }

    /** 结束声明模式（回到按内容路由） */
    fun expectDone() {
        expected.set(null)
    }

    /** 诊断用：路由命中的链 + 命中的判据（排查「没有为 chain X 准备样本」时看它落在哪条） */
    fun explainRoute(body: String): String {
        val chain = route(body)
        val hit = when (chain) {
            CHAIN_OCR -> "image_url"
            CHAIN_REVIEW -> "审查模板特征 issues:[{kind"
            CHAIN_DECOMPOSE -> "检索查询分解器"
            CHAIN_SUMMARY_MAP -> "严谨的文本分析员"
            CHAIN_SUMMARY_MERGE -> "总结撰写人"
            CHAIN_SUMMARY_COD -> "密度优化"
            CHAIN_RETRIEVAL -> "文档分析员/数据分析师"
            else -> "(未命中任何判据 → 报告链)"
        }
        return "$chain <- $hit"
    }

    /** SSE 分片下发：按标点/换行切，末片带 usage，再发 [DONE] */
    private fun respondStream(
        exchange: HttpExchange,
        content: String,
        promptTokens: Int,
        completionTokens: Int,
    ) {
        exchange.responseHeaders.set("Content-Type", "text/event-stream")
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        try {
            for (piece in splitForStream(content)) {
                val chunk = buildJsonObject {
                    put("id", "chatcmpl-assistant")
                    put("object", "chat.completion.chunk")
                    put("model", MODEL_NAME)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("index", 0)
                            put("delta", buildJsonObject { put("content", piece) })
                            put("finish_reason", null)
                        })
                    })
                }
                out.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            // 末片：finish_reason + usage（真实端点也这样回，客户端靠它记账）
            val last = buildJsonObject {
                put("id", "chatcmpl-assistant")
                put("object", "chat.completion.chunk")
                put("model", MODEL_NAME)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { })
                        put("finish_reason", "stop")
                    })
                })
                put("usage", buildJsonObject {
                    put("prompt_tokens", promptTokens)
                    put("completion_tokens", completionTokens)
                    put("total_tokens", promptTokens + completionTokens)
                })
            }
            out.write("data: $last\n\n".toByteArray(Charsets.UTF_8))
            out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } finally {
            out.close()
            exchange.close()
        }
    }

    /**
     * 按标点与换行切分片。
     *
     * 切在**边界处**（标点/换行之后），所以「分片拼接 = 原文」逐字节成立——
     * 这正是客户端那条硬契约要验的东西。
     */
    private fun splitForStream(content: String): List<String> {
        val pieces = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in content) {
            sb.append(ch)
            if (ch == '\n' || ch == '。' || ch == '；' || ch == '！' || ch == '？' || ch == '，') {
                pieces += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) pieces += sb.toString()
        return pieces
    }

    private fun completion(content: String, promptTokens: Int, completionTokens: Int): String =
        buildJsonObject {
            put("id", "chatcmpl-assistant")
            put("object", "chat.completion")
            put("model", MODEL_NAME)
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

    private fun respondJson(exchange: HttpExchange, status: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    companion object {
        const val CHAIN_RETRIEVAL = "retrieval"
        const val CHAIN_DECOMPOSE = "decompose"
        const val CHAIN_SUMMARY_MAP = "summary-map"
        const val CHAIN_SUMMARY_MERGE = "summary-merge"
        const val CHAIN_SUMMARY_COD = "summary-cod"
        const val CHAIN_OCR = "ocr"
        const val CHAIN_REVIEW = "review"
        const val CHAIN_REPORT = "report"

        /** 回包里如实带的模型名（不是任何厂商模型名——避免误读为厂商输出） */
        const val MODEL_NAME = "assistant-substitute"

        /**
         * 本地占位 Key：不是任何厂商凭据，只用于通过生产客户端的鉴权前置检查。
         * 与 T-060 同源（同一个夹具文件），源码不出现凭据形状字面量。
         */
        val API_KEY: String by lazy {
            val props = java.util.Properties()
            MultiChainBackendStub::class.java.classLoader
                .getResourceAsStream("assistant-backend-stub.properties")!!.use { props.load(it) }
            props.getProperty("apiKey")
        }
    }
}
