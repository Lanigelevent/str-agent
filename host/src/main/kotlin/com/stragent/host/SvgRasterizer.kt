package com.stragent.host

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder

/**
 * SVG → PNG 栅格化（T-138 补强轮：用户拍板「全部格式引 batik」）。
 *
 * 为什么需要它：docx / pdf / pptx 三种交付格式此前只能注明「图表见 HTML 仪表盘」——
 * 它们要**内嵌图**就得有位图，而仓库的图表事实源是 [com.stragent.core.chart.SvgChartRenderer]
 * 产出的 SVG 文本。于是引入 Apache Batik（`batik-transcoder`，Apache-2.0）做 SVG→PNG。
 *
 * **单事实源的取舍**：栅格化的输入是**同一个** SVG 渲染器产出的文本——
 * 文档里嵌的图与 HTML 仪表盘里的图同源同形（不是另写一套绘图逻辑）。
 *
 * 边界（诚实）：
 *  - **字体依赖系统**：SVG 的 `font-family: sans-serif` 由 batik 交给 JDK 字体系统解析，
 *    因此**不同机器（字体集不同）产出的 PNG 字节不同** → 含图表的导出件**不再字节级确定**
 *    （不含图表的仍确定）；无 CJK 字体的环境（如精简 Linux 容器）中文标签会缺字形。
 *  - 背景**压白**（SVG 本身透明，PNG 透明底在深色主题页面上会看不清）——
 *    用 JDK 自带的 ImageIO 合成，不引第二个依赖。
 *  - 栅格化失败（非法 SVG / batik 异常）→ 抛异常，由调用方**如实降级**为注记，不静默丢图。
 */
object SvgRasterizer {

    /** 嵌入图的目标宽度（像素）——三个格式统一，保证清晰度够阅读 */
    const val TARGET_WIDTH_PX = 900

    /**
     * SVG 文本 → PNG 字节。
     *
     * @param widthPx 目标宽度（高度按 SVG 原始宽高比推算）
     */
    fun toPng(svg: String, widthPx: Int = TARGET_WIDTH_PX): ByteArray {
        val (w, h) = svgSize(svg)
        val targetW = widthPx.coerceIn(120, 4000)
        val targetH = ((h.toDouble() * targetW) / w).toInt().coerceIn(80, 4000)
        val transcoder = PNGTranscoder()
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, targetW.toFloat())
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, targetH.toFloat())
        val out = ByteArrayOutputStream()
        transcoder.transcode(
            TranscoderInput(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8))),
            TranscoderOutput(out),
        )
        return flattenOntoWhite(out.toByteArray())
    }

    /** 透明底 → 白底（PNG 透明底在深色页面上不可读） */
    private fun flattenOntoWhite(png: ByteArray): ByteArray {
        val source = ImageIO.read(ByteArrayInputStream(png)) ?: return png
        val flat = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = flat.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, flat.width, flat.height)
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(flat, "png", out)
        return out.toByteArray()
    }

    /** PNG 尺寸（读 IHDR，不解码整图） */
    fun pngSize(png: ByteArray): Pair<Int, Int> {
        require(png.size >= 24) { "PNG 太短，不是合法 PNG" }  // lint:allow zh-first（内部校验文案，用户面不可见）
        val w = ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or
            ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)
        val h = ((png[20].toInt() and 0xFF) shl 24) or ((png[21].toInt() and 0xFF) shl 16) or
            ((png[22].toInt() and 0xFF) shl 8) or (png[23].toInt() and 0xFF)
        return w to h
    }

    /** 从 SVG 头里取 width/height 属性（缺省 800×480） */
    fun svgSize(svg: String): Pair<Int, Int> {
        val w = attr(svg, "width") ?: 800
        val h = attr(svg, "height") ?: 480
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /** 取第一个 `name="数字"` 形式的属性值 */
    private fun attr(svg: String, name: String): Int? {
        val key = "$name=\""
        val start = svg.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = svg.indexOf('"', from)
        if (end < 0) return null
        return svg.substring(from, end).trim().toIntOrNull()
    }
}
