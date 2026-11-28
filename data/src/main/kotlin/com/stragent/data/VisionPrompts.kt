package com.stragent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 视觉提问模板 —— D10 §阶段3 的两件事：**看懂图** 与 **把图表反解成数据**。
 *
 * 纪律：抽取模式要求模型**只输出 JSON、读不出的点用 null、不许编造**；
 * 抽出的数据会渲染成 CSV 交给既有分析链（import_table → 分析工具），
 * 这样"从图里读到的数"与"从表里读到的数"**走同一条可溯源的流水线**。
 */
object VisionPrompts {

    /** 描述模式：自由提问（默认） */
    fun describe(question: String): String = buildString {
        append("你是数据图表分析助手。请基于这张图片回答下面的问题，")
        append("只依据图中确实可见的内容作答；看不清或图里没有的，直接说「图中没有该信息」，不要推测。\n\n")
        append("问题：").append(question.ifBlank { "请描述这张图：标题、坐标轴含义、数据系列与整体趋势。" })
    }

    /** 抽取模式：把图表反解成数据序列（严格 JSON） */
    fun extractSeries(): String = """
        这是一张数据图表。请把图中的数据**逐点**读出来。

        只输出一个 JSON 对象，不要任何解释、不要 Markdown 代码块：
        {"title":"图表标题或空字符串","xAxis":["横轴刻度1","横轴刻度2"],"series":[{"name":"系列名","points":[{"x":"横轴刻度","y":12.3}]}]}

        规则：
        1. y 必须是数字；读不出或不存在的点用 null（不要写 0，不要编造）。
        2. x 用图上的原始刻度文本（字符串）。
        3. 图里没有的系列不要加；系列名用图例上的原名。
        4. 一条 JSON、单行输出。
    """.trimIndent()

    /**
     * 解析抽取模式的返回 → 结构化 JSON。
     *
     * 失败给中文错误（模型没按格式输出时，agent 需要知道是"抽取失败"而不是"图里没有数据"）。
     */
    fun parseSeries(text: String): JsonObject {
        val jsonText = Regex("\\{[\\s\\S]*\\}").find(text)?.value
            ?: throw IllegalArgumentException("抽取结果里找不到 JSON 对象（模型可能没按格式输出）")
        val root = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrElse {
            throw IllegalArgumentException("抽取结果的 JSON 解析失败：${it.message}")
        }
        val series = root["series"] as? JsonArray
            ?: throw IllegalArgumentException("抽取结果缺少 series 字段")
        if (series.isEmpty()) throw IllegalArgumentException("抽取结果里没有数据系列")
        return root
    }

    /**
     * 把抽取到的序列渲染成 CSV —— 交给既有分析链（import_table → 分析工具）继续算。
     *
     * 这样"从图里读到的数"与"从表里读到的数"走**同一条可溯源流水线**。
     */
    fun seriesToCsv(seriesJson: JsonObject): String {
        val series = seriesJson["series"] as? JsonArray ?: emptyList()
        val names = series.map { item ->
            (item.jsonObject["name"] as? JsonPrimitive)?.content ?: "series"
        }
        val rows = series.map { item ->
            val points = item.jsonObject["points"] as? JsonArray ?: emptyList()
            points.map { point ->
                val obj = point.jsonObject
                val x = (obj["x"] as? JsonPrimitive)?.content ?: ""
                val y = (obj["y"] as? JsonPrimitive)?.let { p ->
                    if (p.content == "null") "" else p.content
                } ?: ""
                x to y
            }
        }
        val rowCount = rows.maxOfOrNull { it.size } ?: 0
        val builder = StringBuilder()
        builder.append("x,").append(names.joinToString(",")).append('\n')
        for (i in 0 until rowCount) {
            val cells = rows.map { points -> points.getOrNull(i)?.let { (it.first to it.second) } ?: ("" to "") }
            builder.append(cells.map { (x, y) -> "$x,$y" }.joinToString(",")).append('\n')
        }
        return builder.toString()
    }
}
