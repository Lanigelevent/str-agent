package com.stragent.data

import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.RerankClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cohere/Jina 兼容 /rerank 客户端（T-101）——[RerankClient] 的生产实现。
 *
 * 请求：`POST {baseUrl}/rerank`，`{"model":…, "query":…, "documents":[...]}`；
 * 响应：`{"results":[{"index":0,"relevance_score":0.98}]}`——按 relevance_score 降序的
 * `index` 即重排后的下标序（服务端已排序，本类按分数再稳排一次防不保序实现）。
 *
 * 配置与 [OpenAiCompatibleEmbeddingClient] 同构：baseUrl 默认当前服务商可 override；
 * Key 走同一套 prefs；未配置 Key 抛 [RerankError]；失败由调用方回落融合序（诚实降级）。
 */
class OpenAiCompatibleRerankClient(
    private val prefs: PrefsStore,
    encrypt: (String) -> String = { it },
    decrypt: (String) -> String = { it },
    private val baseUrlOverride: String? = null,
    private val timeoutMs: Int = 30_000,
) : RerankClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val config = StepfunConfig(prefs, encrypt, decrypt)

    override fun rerank(query: String, candidates: List<String>): List<com.stragent.core.ports.RerankHit> {
        if (candidates.isEmpty()) return emptyList()
        val apiKey = config.getApiKey()
        if (apiKey.isEmpty()) throw RerankError("还没有配置重排模型的 API Key（rerank 未启用）") // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
        val payload = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive(com.stragent.core.ai.TEXT_RERANK_MODEL))
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("documents", kotlinx.serialization.json.JsonArray(candidates.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        )
        val conn = (URL("${baseUrlOverride ?: config.getVendor().baseUrl}/rerank").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.readBytes()?.decodeToString().orEmpty()
            if (code !in 200..299) throw RerankError("重排端点 HTTP $code：${body.take(200)}") // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
            return parse(body, candidates.size)
        } catch (e: RerankError) {
            throw e
        } catch (e: java.io.IOException) {
            throw RerankError("重排端点连接失败：${e.message ?: e.javaClass.simpleName}") // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 results 按 relevance_score 降序回下标；越界/缺项如实报错 */
    internal fun parse(body: String, expected: Int): List<com.stragent.core.ports.RerankHit> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw RerankError("重排响应不是 JSON 对象") } // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
        val results = root["results"] as? kotlinx.serialization.json.JsonArray
            ?: throw RerankError("重排响应缺 results 数组") // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
        val scored = results.mapNotNull { item ->
            val o = item.jsonObject
            val idx = (o["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val score = (o["relevance_score"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            if (idx in 0 until expected) com.stragent.core.ports.RerankHit(idx, score) else null
        }
        val seen = scored.map { it.index }.toSet()
        if (seen.size != expected) throw RerankError("重排响应覆盖不齐（期望 $expected，实得 ${seen.size}）") // lint:allow zh-first（用户面中文输出，T-095/T-101 语言定位）
        return scored.sortedByDescending { it.score }
    }
}

/** 重排通道错误（调用方据此回落 hybrid 融合序） */
class RerankError(message: String) : RuntimeException(message)
