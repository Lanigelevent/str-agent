package com.stragent.host

import com.stragent.core.chart.EChartsOptionReader
import com.stragent.core.chart.SvgChartRenderer
import kotlinx.serialization.json.JsonObject

/**
 * 导出件的**图表嵌入**共用件（T-138 补强轮：用户拍板「全部格式引 batik」）。
 *
 * 一条链路，三种格式共用：
 * `ECharts option` → [EChartsOptionReader] → [SvgChartRenderer]（SVG 文本，与 HTML
 * 仪表盘**同源**）→ [SvgRasterizer]（PNG）→ docx / pdf / pptx 各自的内嵌部件。
 *
 * 纪律：
 *  - **不静默丢图**：渲染不了的配置（热力图/雷达图/缺数据）返回 null，由调用方
 *    如实注明「该图未嵌入」，不假装画出来；
 *  - **栅格化失败也如实**：batik 抛错 → 返回 null + 失败原因，调用方写进注记；
 *  - **一张图一个字节数组**：同输入同结果（跨机器因系统字体可能不同，见 [SvgRasterizer] 边界）。
 */
internal object ExportCharts {

    /** 一张待嵌入的图 */
    data class Embedded(
        /** PNG 字节 */
        val png: ByteArray,
        /** 像素宽高 */
        val width: Int,
        /** 像素高 */
        val height: Int,
        /** 图注（缺省用序号） */
        val caption: String,
    )

    /**
     * 图表配置 → 可嵌入的图；不可渲染或栅格化失败返回 null。
     *
     * @param option ECharts JSON option
     * @param index 该图在章节内的序号（用于图注）
     */
    fun embed(option: JsonObject, index: Int): Embedded? {
        val spec = EChartsOptionReader.read(option) ?: return null
        val svg = runCatching { SvgChartRenderer.render(spec) }.getOrNull() ?: return null
        val png = runCatching { SvgRasterizer.toPng(svg) }.getOrNull() ?: return null
        val (w, h) = runCatching { SvgRasterizer.pngSize(png) }.getOrElse { return null }
        return Embedded(png = png, width = w, height = h, caption = "图 ${index + 1}")
    }

    /** 一节里的全部可嵌入图（保持顺序） */
    fun embedAll(options: List<JsonObject>): List<Embedded> =
        options.mapIndexedNotNull { index, option -> embed(option, index) }

    /** 章节里**没能**嵌入的图数（用于如实注明） */
    fun skipped(options: List<JsonObject>): Int = options.size - embedAll(options).size
}
