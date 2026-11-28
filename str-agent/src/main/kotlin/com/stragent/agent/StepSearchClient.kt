package com.stragent.agent

import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.SearchClient
import com.stragent.core.ports.SearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * StepSearch：阶跃星辰官方远程 MCP 搜索服务（与 Step API 同一个 Key）——stepsearch.ts 移植。
 *
 * 契约：POST {jsonrpc:2.0} initialize → notifications/initialized → tools/call(web_search)。
 * 响应可能是 application/json 或 text/event-stream（SSE），两种都解析。
 * 这是第一个 SearchClient 实现——点亮经典路径的 useWeb 分支，同时作为 agent 的 web_search 工具后端。
 */
class StepSearchClient(private val prefs: PrefsStore) : SearchClient {

    private var sessionId: String? = null
    private var rpcId = 0

    override fun search(query: String): List<SearchHit> {
        val apiKey = StepfunKeyReader.apiKey(prefs)
        if (apiKey.isEmpty()) throw IllegalArgumentException("还没有配置 Step API Key")
        ensureSession(apiKey)
        val payload = rpc("tools/call", buildJsonObject {
            put("name", "web_search")
            put("arguments", buildJsonObject { put("query", query) })
        }, apiKey)
        val rawText = readToolText(payload)
        // 经典路径的 webMaterialBlock 用 items + 原文；截断口径与 TS 一致
        return extractItems(rawText)
    }

    /** 原始工具文本（经典路径拼接 web 块用），截 4000 */
    fun searchRaw(query: String): String {
        val apiKey = StepfunKeyReader.apiKey(prefs)
        if (apiKey.isEmpty()) throw IllegalArgumentException("还没有配置 Step API Key")
        ensureSession(apiKey)
        return readToolText(rpc("tools/call", buildJsonObject {
            put("name", "web_search")
            put("arguments", buildJsonObject { put("query", query) })
        }, apiKey)).take(4000)
    }

    private fun ensureSession(apiKey: String) {
        if (sessionId != null) return
        rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "stragent-analysis")
                put("version", "1.0.0")
            })
        }, apiKey)
        // initialized 是通知（无 id）；失败不影响后续调用
        runCatching { rpc("notifications/initialized", buildJsonObject { }, apiKey) }
    }

    private fun rpc(method: String, params: kotlinx.serialization.json.JsonObject, apiKey: String): JsonObject {
        val conn = (URL(MCP_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("Authorization", "Bearer $apiKey")
            sessionId?.let { setRequestProperty("mcp-session-id", it) }
            doOutput = true
        }
        try {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ++rpcId)
                put("method", method)
                put("params", params)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode >= 400) {
                throw IllegalArgumentException(
                    if (conn.responseCode == 401 || conn.responseCode == 403) {
                        "API Key 无效或已失效，请在设置里检查后重填"
                    } else {
                        "搜索服务返回 ${conn.responseCode}，请稍后重试"
                    },
                )
            }
            // 会话 ID 在响应头里（大小写不敏感）
            conn.headerFields.entries.firstOrNull { it.key.equals("mcp-session-id", ignoreCase = true) }
                ?.let { sessionId = it.value.firstOrNull() }
            val raw = String(conn.inputStream.readBytes(), Charsets.UTF_8)
            return parseRpcBody(raw)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MCP_URL = "https://api.stepfun.com/step_plan/v1/mcp/web_search/mcp"
        private const val PROTOCOL_VERSION = "2025-03-26"

        /** SSE（text/event-stream）或 JSON 都解析成 JSON-RPC 对象（stepsearch.ts parseRpcBody 移植） */
        fun parseRpcBody(raw: String): JsonObject {
            val text = raw.trim()
            if (text.isEmpty()) throw IllegalArgumentException("搜索服务没有返回内容")
            val jsonText = if (text.startsWith("{")) {
                text
            } else {
                // SSE：取最后一条完整事件的 data 行
                val dataLines = text.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.isNotEmpty() && it != "[DONE]" }
                dataLines.lastOrNull() ?: throw IllegalArgumentException("搜索服务返回格式无法解析")
            }
            return runCatching { Json.parseToJsonElement(jsonText).let { it as? JsonObject ?: throw IllegalArgumentException("x") } }
                .getOrElse { throw IllegalArgumentException("搜索服务返回格式无法解析") }
        }

        /** 工具输出 → 文本（isError / 空 / 缺 content 分别报对） */
        fun readToolText(payload: JsonObject): String {
            val result = payload["result"] as? JsonObject
            if (result?.get("isError")?.toString() == "true") {
                throw IllegalArgumentException("搜索服务返回了错误，请稍后重试")
            }
            val content = (result?.get("content") as? JsonArray) ?: JsonArray(emptyList())
            val text = content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                val partText = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                if (type == "text" && partText != null) partText else null
            }.joinToString("\n")
            if (text.isBlank()) throw IllegalArgumentException("没有搜索到相关内容，换个检索词试试")
            return text
        }

        /** 从 MCP 工具输出里提取 URL 与上下文，整理成结构化结果（最多 5 条，去重） */
        fun extractItems(rawText: String): List<SearchHit> {
            val items = mutableListOf<SearchHit>()
            val seen = mutableSetOf<String>()
            // 排除集含中文标点：URL 紧跟「，。；」等时不能把后续说明吃进链接（T-093 测试抓出）
            val urlRegex = Regex("https?://[^\\s)）\\]\"'，。；：！？]+")
            for (match in urlRegex.findAll(rawText)) {
                if (items.size >= 5) break
                val url = match.value.replace(Regex("[.,;:!?。，；：]+$"), "")
                if (url in seen) continue
                seen.add(url)
                val line = rawText.lines().find { it.contains(url) } ?: url
                val title = line.replace(urlRegex, "").replace(Regex("^[\\s\\-*#>|：:]+"), "").trim()
                items.add(
                    SearchHit(
                        title = if (title.isEmpty()) url else title.take(60),
                        url = url,
                        snippet = line.trim().take(120),
                    ),
                )
            }
            return items
        }
    }
}

/** Key 读取的小间接层：core 不 import data 的 StepfunConfig，app/data 装配时注入 prefs */
private object StepfunKeyReader {
    fun apiKey(prefs: PrefsStore): String = prefs.get("stepfun.apiKey", "")
}
