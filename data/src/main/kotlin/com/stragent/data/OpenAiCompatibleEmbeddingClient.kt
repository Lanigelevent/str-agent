package com.stragent.data

import com.stragent.core.ports.EmbeddingClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 /embeddings 客户端（T-100）——[EmbeddingClient] 的生产实现。
 *
 * 请求：`POST {baseUrl}/embeddings`，`{"model":…, "input":[...]}`（数组批量，一次调用
 * 嵌入一组文本）；响应：`{"data":[{"index":0,"embedding":[...]}], "usage":{…}}`——按
 * `index` 对齐回输入序（部分服务商不保序）。
 *
 * 配置：baseUrl 默认取当前服务商（与 [HttpLlmClient] 同源），可 override（自建网关/
 * 契约测试桩）；Key 走同一套 prefs 存储。未配置 Key 时抛 `no-key`（与 chat 同口径）。
 *
 * 失败语义：HTTP 非 2xx / 缺 data / 维度不齐抛 [EmbeddingError]——由调用方决定回落
 * （诚实降级 + 结构化警告），本类不静默吞。
 */
class OpenAiCompatibleEmbeddingClient(
    private val prefs: com.stragent.core.ports.PrefsStore,
    encrypt: (String) -> String = { it },
    decrypt: (String) -> String = { it },
    /** 覆盖 base URL（自建网关 / 契约测试桩） */
    private val baseUrlOverride: String? = null,
    private val timeoutMs: Int = 30_000,
) : EmbeddingClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val config = StepfunConfig(prefs, encrypt, decrypt)

    private fun embeddingsUrl(): String = "${baseUrlOverride ?: config.getVendor().baseUrl}/embeddings"

    private fun requireApiKey(): String {
        val apiKey = config.getApiKey()
        if (apiKey.isEmpty()) throw EmbeddingError("还没有配置 ${config.getVendor().keyLabel}（嵌入检索未启用）") // lint:allow zh-first（用户面中文输出，T-095/T-100 语言定位）
        return apiKey
    }

    override fun embed(texts: List<String>): List<DoubleArray> {
        if (texts.isEmpty()) return emptyList()
        val apiKey = requireApiKey()
        val payload = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive(com.stragent.core.ai.TEXT_EMBEDDING_MODEL))
                put("input", kotlinx.serialization.json.JsonArray(texts.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
        )
        val conn = (URL(embeddingsUrl()).openConnection() as HttpURLConnection).apply {
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
            if (code !in 200..299) {
                throw EmbeddingError("嵌入端点 HTTP $code：${body.take(200)}") // lint:allow zh-first（用户面中文输出，T-095/T-100 语言定位）
            }
            return parseEmbeddings(body, texts.size)
        } catch (e: EmbeddingError) {
            throw e
        } catch (e: java.io.IOException) {
            throw EmbeddingError("嵌入端点连接失败：${e.message ?: e.javaClass.simpleName}") // lint:allow zh-first（用户面中文输出，T-095/T-100 语言定位）
        } finally {
            conn.disconnect()
        }
    }

    /** 按 index 对齐回输入序；缺项/维度不齐如实报错（错位向量比报错更危险） */
    internal fun parseEmbeddings(body: String, expected: Int): List<DoubleArray> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw EmbeddingError("嵌入响应不是 JSON 对象") } // lint:allow zh-first（用户面中文输出，T-095/T-100 语言定位）
        val data = root["data"] as? kotlinx.serialization.json.JsonArray
            ?: throw EmbeddingError("嵌入响应缺 data 数组") // lint:allow zh-first（用户面中文输出，T-095/T-100 语言定位）
        val byIndex = arrayOfNulls<DoubleArray>(expected)
        for (item in data) {
            val o = item.jsonObject
            val idx = (o["index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: -1
            val vec = (o["embedding"] as? kotlinx.serialization.json.JsonArray)
                ?.map { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: Double.NaN }
                ?: continue
            if (idx in 0 until expected) byIndex[idx] = vec.toDoubleArray()
        }
        if (byIndex.any { it == null || it!!.isEmpty() || it.any { v -> v.isNaN() } }) {
            throw EmbeddingError("嵌入响应缺项或含非数值（期望 $expected 条，实得 ${byIndex.count { it != null }}）") // lint:allow zh-first（用户面中文输出，T-095/T-100 语言定位）
        }
        return byIndex.map { it!! }
    }
}

/** 嵌入通道错误（调用方据此回落 BM25 单路） */
class EmbeddingError(message: String) : RuntimeException(message)
