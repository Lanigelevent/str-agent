package com.stragent.core.chart

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.sqrt

/**
 * ECharts JSON option 构建器 —— D11 v3 S1 交互式可视化。
 *
 * 将 [ChartSpec] 转换为 ECharts 5.x 兼容的 JSON option 对象。
 * 纯 Kotlin、零第三方依赖、确定性——同一输入必然产出同一 JSON。
 *
 * 双轨输出策略：
 *  - SVG 保留 [SvgChartRenderer]（零依赖退化，用于 Markdown/PDF 嵌入）
 *  - ECharts JSON option 用于浏览器交互式渲染（悬停/缩放/动画）
 *
 * 支持八种图表类型（覆盖 Julius AI 核心图表类型）：
 *  - BAR / LINE / PIE / SCATTER（与 SVG 渲染器对齐）
 *  - HEATMAP / HISTOGRAM / BOXPLOT / RADAR（ECharts 独有，SVG 退化）
 *
 * 诚实边界：
 *  - 不引入 JS 运行时——只产出 JSON option，渲染由前端 ECharts 库完成
 *  - option 中不含函数（ECharts 的 formatter 回调等），只用静态配置
 *  - 颜色用固定调色板（与 SvgChartRenderer 同色系）
 */
object EChartsOptionBuilder {

    /** 与 SvgChartRenderer 一致的 8 色调色板 */
    private val PALETTE = listOf(
        "#4E79A7", "#F28E2B", "#E15759", "#76B7B2",
        "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7",
    )

    /** 数值格式化：整值去 .0，否则保留两位 */
    private fun fmt(v: Double): String =
        if (v.isFinite() && v == Math.floor(v) && kotlin.math.abs(v) < 1e15)
            v.toLong().toString()
        else
            String.format("%.2f", v)

    /** 构建 ECharts option（入口） */
    fun build(spec: ChartSpec): JsonObject = when (spec.type) {
        ChartType.BAR -> buildBar(spec)
        ChartType.LINE -> buildLine(spec)
        ChartType.PIE -> buildPie(spec)
        ChartType.SCATTER -> buildScatter(spec)
        ChartType.HEATMAP -> buildHeatmap(spec)
        ChartType.HISTOGRAM -> buildHistogram(spec)
        ChartType.BOXPLOT -> buildBoxplot(spec)
        ChartType.RADAR -> buildRadar(spec)
    }

    /** 柱状图 option */
    private fun buildBar(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "axis") })
        if (spec.series.size > 1) {
            put("legend", buildJsonObject {
                put("data", JsonArray(spec.series.map { JsonPrimitive(it.name) }))
            })
        }
        put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.categories.map { JsonPrimitive(it) }))
        })
        put("yAxis", buildJsonObject { put("type", "value") })
        spec.xLabel?.let { put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.categories.map { JsonPrimitive(it) }))
            put("name", it)
        }) }
        spec.yLabel?.let { put("yAxis", buildJsonObject {
            put("type", "value")
            put("name", it)
        }) }
        put("color", JsonArray(PALETTE.take(spec.series.size).map { JsonPrimitive(it) }))
        put("series", JsonArray(spec.series.map { s ->
            buildJsonObject {
                put("name", s.name)
                put("type", "bar")
                put("data", JsonArray(s.values.map { JsonPrimitive(fmt(it)) }))
            }
        }))
    }

    /** 折线图 option */
    private fun buildLine(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "axis") })
        if (spec.series.size > 1) {
            put("legend", buildJsonObject {
                put("data", JsonArray(spec.series.map { JsonPrimitive(it.name) }))
            })
        }
        put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.categories.map { JsonPrimitive(it) }))
        })
        put("yAxis", buildJsonObject { put("type", "value") })
        spec.xLabel?.let { put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.categories.map { JsonPrimitive(it) }))
            put("name", it)
        }) }
        spec.yLabel?.let { put("yAxis", buildJsonObject {
            put("type", "value")
            put("name", it)
        }) }
        put("color", JsonArray(PALETTE.take(spec.series.size).map { JsonPrimitive(it) }))
        put("series", JsonArray(spec.series.map { s ->
            buildJsonObject {
                put("name", s.name)
                put("type", "line")
                put("data", JsonArray(s.values.map { JsonPrimitive(fmt(it)) }))
            }
        }))
    }

    /** 饼图 option */
    private fun buildPie(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "item") })
        put("legend", buildJsonObject {
            put("orient", "vertical")
            put("left", "right")
        })
        put("color", JsonArray(PALETTE.take(spec.categories.size).map { JsonPrimitive(it) }))
        val vals = spec.series.firstOrNull()?.values ?: emptyList()
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("type", "pie")
                put("radius", "60%")
                put("data", JsonArray(spec.categories.indices.map { i ->
                    if (i < vals.size) buildJsonObject {
                        put("name", spec.categories[i])
                        put("value", JsonPrimitive(fmt(vals[i])))
                    } else buildJsonObject { put("name", spec.categories[i]); put("value", JsonPrimitive("0")) }
                }))
            }
        )))
    }

    /** 散点图 option */
    private fun buildScatter(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "item") })
        put("xAxis", buildJsonObject { put("type", "value") })
        put("yAxis", buildJsonObject { put("type", "value") })
        spec.xLabel?.let { put("xAxis", buildJsonObject { put("type", "value"); put("name", it) }) }
        spec.yLabel?.let { put("yAxis", buildJsonObject { put("type", "value"); put("name", it) }) }
        put("color", JsonArray(PALETTE.take(spec.series.size).map { JsonPrimitive(it) }))
        put("series", JsonArray(spec.series.map { s ->
            val xs = s.xValues ?: emptyList()
            buildJsonObject {
                put("name", s.name)
                put("type", "scatter")
                put("data", JsonArray(s.values.indices.map { i ->
                    val x = if (i < xs.size) xs[i] else 0.0
                    JsonArray(listOf(JsonPrimitive(fmt(x)), JsonPrimitive(fmt(s.values[i]))))
                }))
            }
        }))
    }

    /**
     * 热力图 option
     *
     * 将 ChartSpec 适配为热力图：categories 为 X 轴，series 名为 Y 轴，
     * values 为格子值。
     */
    private fun buildHeatmap(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("position", "top") })
        put("grid", buildJsonObject {
            put("top", "10%")
            put("left", "15%")
            put("right", "15%")
        })
        put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.categories.map { JsonPrimitive(it) }))
            put("splitArea", buildJsonObject { put("show", true) })
        })
        put("yAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.series.map { JsonPrimitive(it.name) }))
            put("splitArea", buildJsonObject { put("show", true) })
        })
        put("visualMap", buildJsonObject {
            put("min", 0)
            put("max", spec.series.flatMap { it.values }.filter { it.isFinite() }.maxOrNull()?.let { JsonPrimitive(fmt(it)) } ?: JsonPrimitive("100"))
            put("calculable", true)
            put("orient", "horizontal")
            put("left", "center")
            put("bottom", "5%")
        })
        val heatData = mutableListOf<JsonArray>()
        for (si in spec.series.indices) {
            val s = spec.series[si]
            for (ci in s.values.indices) {
                if (ci < spec.categories.size) {
                    heatData.add(JsonArray(listOf(
                        JsonPrimitive(ci),
                        JsonPrimitive(si),
                        JsonPrimitive(fmt(s.values[ci])),
                    )))
                }
            }
        }
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("name", spec.title)
                put("type", "heatmap")
                put("data", JsonArray(heatData))
                put("label", buildJsonObject { put("show", true) })
                put("emphasis", buildJsonObject { put("itemStyle", buildJsonObject { put("shadowBlur", 10); put("shadowColor", "rgba(0, 0, 0, 0.5)") }) })
            }
        )))
    }

    /**
     * 直方图 option
     *
     * 从第一个 series 的 values 计算等宽分箱，输出为柱状图格式。
     */
    private fun buildHistogram(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "axis") })
        val vals = spec.series.firstOrNull()?.values ?: emptyList()
        val valid = vals.filter { it.isFinite() }
        val nbins = minOf(10, valid.size.coerceAtLeast(1))
        val minV = valid.minOrNull() ?: 0.0
        val maxV = valid.maxOrNull() ?: 0.0
        val range = maxV - minV
        val binW = if (range > 0) range / nbins else 1.0
        val bins = IntArray(nbins)
        for (v in valid) {
            val idx = if (range > 0) ((v - minV) / binW).toInt().coerceIn(0, nbins - 1) else 0
            bins[idx]++
        }
        val binLabels = (0 until nbins).map { i ->
            val lo = minV + i * binW
            val hi = lo + binW
            "${fmt(lo)}~${fmt(hi)}"
        }
        put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(binLabels.map { JsonPrimitive(it) }))
        })
        put("yAxis", buildJsonObject { put("type", "value"); put("name", "频数") })
        put("color", JsonArray(listOf(JsonPrimitive(PALETTE[0]))))
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("name", spec.series.firstOrNull()?.name ?: "频数")
                put("type", "bar")
                put("data", JsonArray(bins.map { JsonPrimitive(it) }))
                put("barWidth", "95%")
            }
        )))
    }

    /**
     * 箱线图 option
     *
     * 从每个 series 的 values 计算五数概括（min/Q1/median/Q3/max）。
     */
    private fun buildBoxplot(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "item") })
        put("xAxis", buildJsonObject {
            put("type", "category")
            put("data", JsonArray(spec.series.map { JsonPrimitive(it.name) }))
        })
        put("yAxis", buildJsonObject { put("type", "value") })
        put("color", JsonArray(listOf(JsonPrimitive(PALETTE[0]))))
        val boxData = spec.series.map { s ->
            val sorted = s.values.filter { it.isFinite() }.sorted()
            val n = sorted.size
            if (n == 0) {
                JsonArray(listOf(JsonPrimitive("0"), JsonPrimitive("0"), JsonPrimitive("0"), JsonPrimitive("0"), JsonPrimitive("0")))
            } else {
                val q = { p: Double ->
                    val idx = p * (n - 1)
                    val lo = idx.toInt()
                    val hi = (lo + 1).coerceAtMost(n - 1)
                    val frac = idx - lo
                    sorted[lo] * (1 - frac) + sorted[hi] * frac
                }
                JsonArray(listOf(
                    JsonPrimitive(fmt(sorted.first())),
                    JsonPrimitive(fmt(q(0.25))),
                    JsonPrimitive(fmt(q(0.50))),
                    JsonPrimitive(fmt(q(0.75))),
                    JsonPrimitive(fmt(sorted.last())),
                ))
            }
        }
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("name", spec.title)
                put("type", "boxplot")
                put("data", JsonArray(boxData))
            }
        )))
    }

    /**
     * 雷达图 option
     *
     * categories 作为雷达维度指示器，series 值为各维数据。
     */
    private fun buildRadar(spec: ChartSpec): JsonObject = buildJsonObject {
        put("title", buildJsonObject { put("text", spec.title) })
        put("tooltip", buildJsonObject { put("trigger", "item") })
        if (spec.series.size > 1) {
            put("legend", buildJsonObject {
                put("data", JsonArray(spec.series.map { JsonPrimitive(it.name) }))
            })
        }
        val maxVal = spec.series.flatMap { it.values }.filter { it.isFinite() }.maxOrNull() ?: 100.0
        put("radar", buildJsonObject {
            put("indicator", JsonArray(spec.categories.map { cat ->
                buildJsonObject {
                    put("name", cat)
                    put("max", JsonPrimitive(fmt(maxVal * 1.1)))
                }
            }))
        })
        put("color", JsonArray(PALETTE.take(spec.series.size).map { JsonPrimitive(it) }))
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("type", "radar")
                put("data", JsonArray(spec.series.map { s ->
                    buildJsonObject {
                        put("name", s.name)
                        put("value", JsonArray(s.values.map { JsonPrimitive(fmt(it)) }))
                    }
                }))
            }
        )))
    }

    /**
     * 计算 option 的确定性哈希（使用 ProvenanceManifest 的 canonical 序列化）。
     *
     * 用于溯源：同一 ChartSpec 产出的 option 哈希必然一致。
     */
    fun optionHash(spec: ChartSpec): String {
        val option = build(spec)
        return com.stragent.core.trace.ProvenanceManifest.hashOf(option)
    }
}
