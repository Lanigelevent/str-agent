package com.stragent.agent

import com.stragent.core.dataset.Dataset
import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.SearchClient
import com.stragent.core.ports.ToolException
import com.stragent.core.tools.buildAnalysisTools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 状态工具三件套（Plan-and-Execute + Scratchpad + 自纠错）：submit_plan / record_finding /
 * audit_report 由 AgentLoop 内建处理（不进缓存、直接进 state / 调校验器）——audit_report 的
 * 可复用实现在 core/tools（T-148 下移，与 16 个分析工具同住）。
 *
 * 本文件只剩 **环境工具**（agent 循环专属）：web_search / web_fetch —— openWorld 工具，
 * 与外部实体交互，不进 core 的纯函数工具面，也不进 data 的只读 MCP 工具面。
 */

/** web_search：联网检索（取网络资料佐证分析）。结果为标题+链接+摘要 */
fun webSearchTool(search: SearchClient): AgentTool = object : AgentTool {
    override val name = "web_search"
    override val description = "联网检索（取网络资料佐证分析）。结果为标题+链接+摘要；外部资料与数据冲突时以数据为准。"
    override val parametersSchema = schemaObject(
        "query" to schemaField("string", "查询内容"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        required = listOf("query"),
    )
    override val openWorld = true

    override fun invoke(args: JsonObject): JsonObject {
        val query = args["query"]?.let { (it as? JsonPrimitive)?.content }
            ?: throw ToolException("query 不能为空")
        val hits = search.search(query)
        return buildJsonObject {
            put(
                "items",
                JsonArray(
                    hits.map { hit ->
                        buildJsonObject {
                            put("title", hit.title)
                            put("url", hit.url)
                            put("snippet", hit.snippet)
                        }
                    },
                ),
            )
        }
    }
}

/** web_fetch：抓网页正文（去标签去脚本），配合 web_search 深入阅读来源 */
fun webFetchTool(): AgentTool = object : AgentTool {
    override val name = "web_fetch"
    override val description = "抓取一个网页的正文文本（去标签、去脚本，截 4000 字）。配合 web_search 深入阅读来源全文。"
    override val parametersSchema = schemaObject(
        "url" to schemaField("string", "url 参数"),  // lint:allow zh-first（工具描述=调用方模型引导面，T-095/T-104 语言定位）
        required = listOf("url"),
    )
    override val openWorld = true

    override fun invoke(args: JsonObject): JsonObject {
        val url = args["url"]?.let { (it as? JsonPrimitive)?.content }
            ?: throw ToolException("url 不能为空")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw ToolException("url 必须以 http(s):// 开头")
        }
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; str-agent/1.0)")
            if (conn.responseCode >= 400) {
                throw ToolException("网页返回 ${conn.responseCode}，无法抓取")
            }
            val contentType = conn.contentType ?: ""
            val charset = Regex("charset=([\\w-]+)").find(contentType)?.groupValues?.get(1) ?: "UTF-8"
            val raw = String(conn.inputStream.readBytes(), charset(charset))
            // 去 script/style/head → 去标签 → 解实体 → 压空白
            val text = raw
                .replace(Regex("<(script|style|head|nav|footer)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replace(Regex("\\s+"), " ").trim()
                .take(4000)
            if (text.isBlank()) throw ToolException("网页正文为空（可能是纯脚本渲染页）")
            return buildJsonObject {
                put("url", url)
                put("text", text)
            }
        } catch (e: ToolException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw ToolException("网页抓取超时（15 秒），请稍后重试")
        } catch (e: Exception) {
            throw ToolException("网页抓取失败：${e.message ?: "网络异常"}")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * agent 循环工具全集（T-148：环境工具 + core 纯函数分析工具的装配点）。
 * dateIndex 用 pickDefaultColumns 预解析的横轴列；search 非 null 时含 web_search。
 */
fun buildAgentTools(dataset: Dataset, brief: String, search: SearchClient?): List<AgentTool> =
    buildAnalysisTools(dataset, brief) + listOfNotNull(
        search?.let { webSearchTool(it) },
        webFetchTool(),
    )
