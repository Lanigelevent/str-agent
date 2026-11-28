package com.stragent.core.chart

/**
 * SVG 图表渲染器 —— 纯 JVM、零依赖、确定性。
 *
 * 将 [ChartSpec] 渲染为 SVG 文本字符串。不依赖任何前端库或浏览器——
 * 纯字符串拼装，同一输入必然产出同一输出。
 *
 * 支持四种图表：
 *  - BAR：分组柱状图（多系列并列）
 *  - LINE：多系列折线图
 *  - PIE：饼图（含标签与百分比）
 *  - SCATTER：散点图
 *
 * 诚实边界（不冒称）：
 *  - SVG 是静态图，不交互（无 hover/zoom）——交互需前端 JS
 *  - 颜色固定调色板，不支持自定义主题
 *  - 不做动画——SVG 是确定性文本，动画会引入时间维度
 *  - 标签过长时截断（不自动换行）
 */
object SvgChartRenderer {

    /** 8 色调色板（覆盖大多数多系列场景） */
    private val PALETTE = listOf(
        "#4E79A7", "#F28E2B", "#E15759", "#76B7B2",
        "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7",
    )

    private fun color(i: Int): String = PALETTE[i % PALETTE.size]

    /** SVG 颜色转义（防注入） */
    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 数值格式化：整值去 .0 */
    private fun fmt(v: Double): String {
        return if (v.isFinite() && v == Math.floor(v) && kotlin.math.abs(v) < 1e15)
            v.toLong().toString()
        else
            String.format("%.2f", v)
    }

    /** 标签截断 */
    private fun trunc(s: String, max: Int = 12): String =
        if (s.length <= max) s else s.take(max - 1) + "\u2026"

    /**
     * 渲染 SVG。`interactive=true` 时给数据形状附加 `data-name/data-cat/data-v`
     * 属性（HTML 仪表盘的悬停 tooltip 与图例开合据此工作）——纯属性附加，
     * 视觉与默认输出完全一致；缺省 false（既有调用零变化）。
     */
    fun render(spec: ChartSpec, interactive: Boolean = false): String = when (spec.type) {
        ChartType.BAR -> renderBar(spec, interactive)
        ChartType.LINE -> renderLine(spec, interactive)
        ChartType.PIE -> renderPie(spec, interactive)
        ChartType.SCATTER -> renderScatter(spec, interactive)
        else -> unsupportedSvg(spec)
    }

    /** 新增图表类型（热力图/直方图/箱线图/雷达图）暂不支持 SVG，回退提示 */
    private fun unsupportedSvg(spec: ChartSpec): String =
        header(spec.width, spec.height) +
        "<text x=\"${spec.width / 2}\" y=\"${spec.height / 2}\" text-anchor=\"middle\" font-size=\"12\" fill=\"#999\">${spec.type.label}暂不支持 SVG 渲染，请使用 ECharts option</text>" +
        footer()

    private fun renderBar(spec: ChartSpec, interactive: Boolean): String {
        val w = spec.width.coerceIn(200, 1200)
        val h = spec.height.coerceIn(150, 800)
        val padL = 60; val padR = 20; val padT = 40; val padB = 50
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        val cats = spec.categories
        val series = spec.series
        if (cats.isEmpty() || series.isEmpty()) return emptySvg(spec)

        val allVals = series.flatMap { it.values }.filter { it.isFinite() }
        if (allVals.isEmpty()) return emptySvg(spec)
        val maxV = allVals.max(); val minV = minOf(0.0, allVals.min())
        val range = maxV - minV; if (range == 0.0) return emptySvg(spec)

        val groupW = plotW.toDouble() / cats.size
        val barW = groupW * 0.7 / series.size
        val sb = StringBuilder()
        sb.append(header(w, h))
        sb.append(title(w, spec.title))

        sb.append("<g class=\"grid\">")
        for (i in 0..5) {
            val v = minV + range * i / 5
            val y = padT + plotH - plotH * i / 5
            sb.append("<line x1=\"$padL\" y1=\"${y.toInt()}\" x2=\"${padL + plotW}\" y2=\"${y.toInt()}\" stroke=\"#e0e0e0\"/>")
            sb.append("<text x=\"${padL - 8}\" y=\"${y.toInt() + 4}\" text-anchor=\"end\" font-size=\"10\" fill=\"#666\">${fmt(v)}</text>")
        }
        sb.append("</g>")

        for (si in series.indices) {
            val s = series[si]
            for (ci in cats.indices) {
                if (ci >= s.values.size) continue
                val v = s.values[ci]
                if (!v.isFinite()) continue
                val barH = plotH * (v - minV) / range
                val x = padL + ci * groupW + si * barW + groupW * 0.15
                val y = padT + plotH - barH
                sb.append("<rect x=\"${x.toInt()}\" y=\"${y.toInt()}\" width=\"${barW.toInt()}\" height=\"${barH.toInt()}\" fill=\"${color(si)}\" rx=\"2\"${if (interactive) attrsFor(s.name, cats[ci], fmt(v)) else ""}/>")
            }
        }

        sb.append("<g class=\"x-labels\">")
        for (ci in cats.indices) {
            val x = padL + ci * groupW + groupW / 2
            val y = padT + plotH + 18
            sb.append("<text x=\"${x.toInt()}\" y=\"$y\" text-anchor=\"middle\" font-size=\"10\" fill=\"#333\">${esc(trunc(cats[ci]))}</text>")
        }
        sb.append("</g>")

        sb.append(legend(w, padL, padR, padB, series, interactive))
        spec.xLabel?.let { sb.append(axisLabel(w / 2, h - 5, it)) }
        spec.yLabel?.let { sb.append(rotatedLabel(15, h / 2, it)) }
        sb.append(footer())
        return sb.toString()
    }

    private fun renderLine(spec: ChartSpec, interactive: Boolean): String {
        val w = spec.width.coerceIn(200, 1200)
        val h = spec.height.coerceIn(150, 800)
        val padL = 60; val padR = 20; val padT = 40; val padB = 50
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        val cats = spec.categories
        val series = spec.series
        if (cats.isEmpty() || series.isEmpty()) return emptySvg(spec)

        val allVals = series.flatMap { it.values }.filter { it.isFinite() }
        if (allVals.isEmpty()) return emptySvg(spec)
        val maxV = allVals.max(); val minV = minOf(0.0, allVals.min())
        val range = maxV - minV; if (range == 0.0) return emptySvg(spec)

        val stepX = if (cats.size > 1) plotW.toDouble() / (cats.size - 1) else 0.0
        val sb = StringBuilder()
        sb.append(header(w, h))
        sb.append(title(w, spec.title))

        sb.append("<g class=\"grid\">")
        for (i in 0..5) {
            val v = minV + range * i / 5
            val y = padT + plotH - plotH * i / 5
            sb.append("<line x1=\"$padL\" y1=\"${y.toInt()}\" x2=\"${padL + plotW}\" y2=\"${y.toInt()}\" stroke=\"#e0e0e0\"/>")
            sb.append("<text x=\"${padL - 8}\" y=\"${y.toInt() + 4}\" text-anchor=\"end\" font-size=\"10\" fill=\"#666\">${fmt(v)}</text>")
        }
        sb.append("</g>")

        for (si in series.indices) {
            val s = series[si]
            if (s.values.isEmpty()) continue
            val pts = StringBuilder()
            for (vi in s.values.indices) {
                if (vi >= cats.size) break
                val v = s.values[vi]
                if (!v.isFinite()) continue
                val x = padL + vi * stepX
                val y = padT + plotH - plotH * (v - minV) / range
                pts.append("${x.toInt()},${y.toInt()} ")
            }
            sb.append("<polyline points=\"${pts.toString().trim()}\" fill=\"none\" stroke=\"${color(si)}\" stroke-width=\"2\"${if (interactive) " data-line=\"${esc(s.name)}\"" else ""}/>")
            for (vi in s.values.indices) {
                if (vi >= cats.size) break
                val v = s.values[vi]
                if (!v.isFinite()) continue
                val x = padL + vi * stepX
                val y = padT + plotH - plotH * (v - minV) / range
                sb.append("<circle cx=\"${x.toInt()}\" cy=\"${y.toInt()}\" r=\"3\" fill=\"${color(si)}\"${if (interactive) attrsFor(s.name, cats[vi], fmt(v)) else ""}/>")
            }
        }

        sb.append("<g class=\"x-labels\">")
        for (ci in cats.indices) {
            val x = padL + ci * stepX
            val y = padT + plotH + 18
            sb.append("<text x=\"${x.toInt()}\" y=\"$y\" text-anchor=\"middle\" font-size=\"10\" fill=\"#333\">${esc(trunc(cats[ci]))}</text>")
        }
        sb.append("</g>")

        sb.append(legend(w, padL, padR, padB, series, interactive))
        spec.xLabel?.let { sb.append(axisLabel(w / 2, h - 5, it)) }
        spec.yLabel?.let { sb.append(rotatedLabel(15, h / 2, it)) }
        sb.append(footer())
        return sb.toString()
    }

    private fun renderPie(spec: ChartSpec, interactive: Boolean): String {
        val w = spec.width.coerceIn(200, 1200)
        val h = spec.height.coerceIn(150, 800)
        val cats = spec.categories
        val vals = spec.series.firstOrNull()?.values ?: return emptySvg(spec)
        if (cats.isEmpty() || vals.isEmpty()) return emptySvg(spec)
        val total = vals.filter { it.isFinite() && it > 0 }.sum()
        if (total <= 0) return emptySvg(spec)

        val cx = w / 2; val cy = h / 2
        val r = minOf(w, h) / 2 - 40
        val sb = StringBuilder()
        sb.append(header(w, h))
        sb.append(title(w, spec.title))

        var angle = -90.0
        for (i in vals.indices) {
            if (i >= cats.size) break
            val v = vals[i]
            if (!v.isFinite() || v <= 0) continue
            val sweep = 360.0 * v / total
            val endAngle = angle + sweep
            val rad1 = Math.toRadians(angle)
            val rad2 = Math.toRadians(endAngle)
            val x1 = cx + r * Math.cos(rad1)
            val y1 = cy + r * Math.sin(rad1)
            val x2 = cx + r * Math.cos(rad2)
            val y2 = cy + r * Math.sin(rad2)
            val largeArc = if (sweep > 180) 1 else 0
            sb.append("<path d=\"M$cx,$cy L${x1.toInt()},${y1.toInt()} A$r,$r 0 $largeArc 1 ${x2.toInt()},${y2.toInt()} Z\" fill=\"${color(i)}\" stroke=\"white\" stroke-width=\"1.5\"${if (interactive) attrsFor(cats[i], cats[i], fmt(v)) else ""}/>")
            val midAngle = angle + sweep / 2
            val lr = r * 0.6
            val lx = cx + lr * Math.cos(Math.toRadians(midAngle))
            val ly = cy + lr * Math.sin(Math.toRadians(midAngle))
            val pct = v / total * 100
            sb.append("<text x=\"${lx.toInt()}\" y=\"${ly.toInt()}\" text-anchor=\"middle\" font-size=\"9\" fill=\"white\" font-weight=\"bold\"${if (interactive) " data-pct=\"${fmt(pct)}\"" else ""}>${fmt(pct)}%</text>")
            angle = endAngle
        }

        sb.append("<g class=\"legend\">")
        for (i in vals.indices) {
            if (i >= cats.size) break
            val v = vals[i]
            if (!v.isFinite() || v <= 0) continue
            val ly = 20 + i * 16
            val lx = w - 100
            sb.append("<rect x=\"$lx\" y=\"$ly\" width=\"10\" height=\"10\" fill=\"${color(i)}\"${if (interactive) " data-legend=\"${esc(cats[i])}\"" else ""}/>")
            sb.append("<text x=\"${lx + 14}\" y=\"${ly + 9}\" font-size=\"10\" fill=\"#333\">${esc(trunc(cats[i]))}</text>")
        }
        sb.append("</g>")
        sb.append(footer())
        return sb.toString()
    }

    private fun renderScatter(spec: ChartSpec, interactive: Boolean): String {
        val w = spec.width.coerceIn(200, 1200)
        val h = spec.height.coerceIn(150, 800)
        val padL = 60; val padR = 20; val padT = 40; val padB = 50
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        val series = spec.series
        if (series.isEmpty()) return emptySvg(spec)
        val s = series[0]
        val xs = s.xValues ?: return emptySvg(spec)
        val ys = s.values
        if (xs.isEmpty() || ys.isEmpty() || xs.size != ys.size) return emptySvg(spec)

        val validXs = xs.filter { it.isFinite() }
        val validYs = ys.filter { it.isFinite() }
        if (validXs.isEmpty() || validYs.isEmpty()) return emptySvg(spec)
        val minX = validXs.min(); val maxX = validXs.max()
        val minY = validYs.min(); val maxY = validYs.max()
        val rangeX = maxX - minX; val rangeY = maxY - minY
        if (rangeX == 0.0 || rangeY == 0.0) return emptySvg(spec)

        val sb = StringBuilder()
        sb.append(header(w, h))
        sb.append(title(w, spec.title))

        sb.append("<g class=\"grid\">")
        for (i in 0..5) {
            val v = minY + rangeY * i / 5
            val y = padT + plotH - plotH * i / 5
            sb.append("<line x1=\"$padL\" y1=\"${y.toInt()}\" x2=\"${padL + plotW}\" y2=\"${y.toInt()}\" stroke=\"#e0e0e0\"/>")
            sb.append("<text x=\"${padL - 8}\" y=\"${y.toInt() + 4}\" text-anchor=\"end\" font-size=\"10\" fill=\"#666\">${fmt(v)}</text>")
        }
        sb.append("</g>")

        for (i in ys.indices) {
            val xv = xs[i]; val yv = ys[i]
            if (!xv.isFinite() || !yv.isFinite()) continue
            val x = padL + plotW * (xv - minX) / rangeX
            val y = padT + plotH - plotH * (yv - minY) / rangeY
            sb.append("<circle cx=\"${x.toInt()}\" cy=\"${y.toInt()}\" r=\"4\" fill=\"${color(0)}\" opacity=\"0.7\"${if (interactive) " data-name=\"\" data-x=\"${fmt(xv)}\" data-y=\"${fmt(yv)}\"" else ""}/>")
        }

        spec.xLabel?.let { sb.append(axisLabel(w / 2, h - 5, it)) }
        spec.yLabel?.let { sb.append(rotatedLabel(15, h / 2, it)) }
        sb.append(footer())
        return sb.toString()
    }

    /** 交互层数据属性（T-168）：HTML 仪表盘的 tooltip/图例开合读取这些 data-* */
    private fun attrsFor(name: String, cat: String, value: String): String =
        " data-name=\"${esc(name)}\" data-cat=\"${esc(trunc(cat))}\" data-v=\"${esc(value)}\""

    private fun header(w: Int, h: Int): String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\" style=\"font-family: sans-serif;\">"

    private fun title(w: Int, text: String): String =
        "<text x=\"${w / 2}\" y=\"20\" text-anchor=\"middle\" font-size=\"14\" font-weight=\"bold\" fill=\"#222\">${esc(text)}</text>"

    private fun legend(w: Int, padL: Int, padR: Int, padB: Int, series: List<ChartSeries>, interactive: Boolean): String {
        if (series.size <= 1) return ""
        val sb = StringBuilder()
        sb.append("<g class=\"legend\">")
        val cols = minOf(series.size, 4)
        for (i in series.indices) {
            val ly = padB + 5 + (i / cols) * 14
            val lx = padL + (i % cols) * (w - padL - padR) / cols
            val mark = if (interactive) " data-legend=\"${esc(series[i].name)}\"" else ""
            sb.append("<rect x=\"$lx\" y=\"$ly\" width=\"8\" height=\"8\" fill=\"${color(i)}\"$mark/>")
            sb.append("<text x=\"${lx + 12}\" y=\"${ly + 8}\" font-size=\"9\" fill=\"#333\">${esc(trunc(series[i].name))}</text>")
        }
        sb.append("</g>")
        return sb.toString()
    }

    private fun axisLabel(x: Int, y: Int, text: String): String =
        "<text x=\"$x\" y=\"$y\" text-anchor=\"middle\" font-size=\"11\" fill=\"#555\">${esc(text)}</text>"

    private fun rotatedLabel(x: Int, y: Int, text: String): String =
        "<text x=\"$x\" y=\"$y\" text-anchor=\"middle\" font-size=\"11\" fill=\"#555\" transform=\"rotate(-90 $x $y)\">${esc(text)}</text>"

    private fun footer(): String = "</svg>"

    private fun emptySvg(spec: ChartSpec): String =
        header(spec.width, spec.height) +
        "<text x=\"${spec.width / 2}\" y=\"${spec.height / 2}\" text-anchor=\"middle\" font-size=\"12\" fill=\"#999\">无有效数据</text>" +
        footer()
}
