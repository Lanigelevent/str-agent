package com.stragent.core.chart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ECharts JSON option → [ChartSpec]（[EChartsOptionBuilder] 的逆变换）。
 *
 * 用途：离线 HTML 导出时把调用方给的 ECharts option 直接渲染成内联 SVG，
 * 不再依赖 CDN 脚本（T-054 / D17 G-12「HTML 去 CDN」）。
 *
 * 口径（宁可诚实降级，不假装支持）：
 *  - 只读静态配置，能静态渲染的只有 bar / line / pie / scatter；
 *  - 混合图（bar+line）、热力图/雷达图/箱线图，以及缺 series/data 的配置 → 返回 null，
 *    由调用方给出「原始 option + 不支持离线渲染」的降级视图；
 *  - 数值解析失败的点保留为 NaN（渲染器跳过，不伪造 0）。
 */
object EChartsOptionReader {

    /** 读取 option；无法静态渲染时返回 null */
    fun read(option: JsonObject, width: Int = 720, height: Int = 420): ChartSpec? {
        val series = normalizedSeries(option)
        if (series.isEmpty()) return null
        val types = series.map { (it.str("type") ?: "bar").lowercase() }.distinct()
        if (types.size != 1) return null
        val type = ChartType.fromId(types.first()) ?: return null
        if (type !in STATIC_TYPES) return null

        val title = option.obj("title")?.str("text")?.takeIf { it.isNotBlank() } ?: "图表"
        val xAxis = option.obj("xAxis") ?: (option.arr("xAxis")?.firstOrNull() as? JsonObject)
        val yAxis = option.obj("yAxis") ?: (option.arr("yAxis")?.firstOrNull() as? JsonObject)
        val xLabel = xAxis?.str("name")
        val yLabel = yAxis?.str("name")

        return when (type) {
            ChartType.PIE -> readPie(series, title, width, height)
            ChartType.SCATTER -> readScatter(series, title, xLabel, yLabel, width, height)
            ChartType.BAR, ChartType.LINE -> readCategory(series, type, xAxis, title, xLabel, yLabel, width, height)
            else -> null
        }
    }

    private val STATIC_TYPES = setOf(ChartType.BAR, ChartType.LINE, ChartType.PIE, ChartType.SCATTER)

    /** series 既可能是数组也可能是单个对象（手写 option 常见） */
    private fun normalizedSeries(option: JsonObject): List<JsonObject> = when (val raw = option["series"]) {
        is JsonArray -> raw.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(raw)
        else -> emptyList()
    }

    private fun readCategory(
        series: List<JsonObject>,
        type: ChartType,
        xAxis: JsonObject?,
        title: String,
        xLabel: String?,
        yLabel: String?,
        width: Int,
        height: Int,
    ): ChartSpec? {
        val dataSeries = series.mapIndexedNotNull { index, s ->
            val values = s.arr("data")?.map { valueOf(it) } ?: return@mapIndexedNotNull null
            if (values.isEmpty()) return@mapIndexedNotNull null
            ChartSeries(s.str("name") ?: "系列${index + 1}", values)
        }
        if (dataSeries.isEmpty()) return null

        val explicitCats = xAxis?.arr("data")?.map { it.text() } ?: emptyList()
        val maxLen = dataSeries.maxOf { it.values.size }
        val categories = if (explicitCats.isNotEmpty()) explicitCats else (1..maxLen).map { it.toString() }
        return ChartSpec(
            type = type,
            title = title,
            series = dataSeries,
            categories = categories,
            xLabel = xLabel,
            yLabel = yLabel,
            width = width,
            height = height,
        )
    }

    private fun readPie(series: List<JsonObject>, title: String, width: Int, height: Int): ChartSpec? {
        // 多个饼图系列无法在单张静态图里并列——不擅自合并
        if (series.size != 1) return null
        val entries = series.first().arr("data") ?: return null
        val categories = mutableListOf<String>()
        val values = mutableListOf<Double>()
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is JsonObject -> {
                    categories.add(entry.str("name") ?: "项目${index + 1}")
                    values.add(entry["value"]?.let { valueOf(it) } ?: Double.NaN)
                }
                is JsonPrimitive -> {
                    categories.add("项目${index + 1}")
                    values.add(valueOf(entry))
                }
                else -> Unit
            }
        }
        if (categories.isEmpty()) return null
        return ChartSpec(
            type = ChartType.PIE,
            title = title,
            series = listOf(ChartSeries("占比", values)),
            categories = categories,
            width = width,
            height = height,
        )
    }

    private fun readScatter(
        series: List<JsonObject>,
        title: String,
        xLabel: String?,
        yLabel: String?,
        width: Int,
        height: Int,
    ): ChartSpec? {
        // SvgChartRenderer 的散点只画第一个系列——多系列不静默丢数据
        if (series.size != 1) return null
        val s = series.first()
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (point in s.arr("data") ?: return null) {
            val pair = point as? JsonArray ?: continue
            if (pair.size < 2) continue
            xs.add(valueOf(pair[0]))
            ys.add(valueOf(pair[1]))
        }
        if (xs.isEmpty()) return null
        return ChartSpec(
            type = ChartType.SCATTER,
            title = title,
            series = listOf(ChartSeries(s.str("name") ?: "数据点", ys, xs)),
            categories = emptyList(),
            xLabel = xLabel,
            yLabel = yLabel,
            width = width,
            height = height,
        )
    }

    /** 数据点 → 数值；对象取 value；解析不出来给 NaN（渲染器会跳过） */
    private fun valueOf(element: kotlinx.serialization.json.JsonElement): Double = when (element) {
        is JsonPrimitive -> element.content.toDoubleOrNull() ?: Double.NaN
        is JsonObject -> element["value"]?.let { valueOf(it) } ?: Double.NaN
        else -> Double.NaN
    }

    private fun kotlinx.serialization.json.JsonElement.text(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
