package com.stragent.host

import com.stragent.core.chart.EChartsOptionReader
import com.stragent.core.chart.SvgChartRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * 报告**落地页**导出器（T-175 · D22 §6 P2-9「首页产出」）。
 *
 * 形态选择（用户拍板「首页产出」后定的口径）：做成**离线单文件静态页**——
 * 不是常驻服务、不做登录态、不放数据原文。它是「报告的对外门面」：
 * 一屏看清标题、摘要、元信息与图表，往下是分章正文。
 *
 * 为什么是静态页而不是服务：①与仓库既有纪律一致（导出件一律离线单文件、无外部引用）；
 * ②「仅作为工具存在」的定位下，多一个常驻服务是产品形态的越界；③静态页可分享、可归档、
 * 可离线打开——这正是「交付」需要的。
 *
 * 诚实边界：
 *  - **不含登录态与权限**——页面内容就是导出时给它的内容，谁能拿到文件谁就能看；
 *  - **图表**用与仪表盘同一渲染器（SVG 内联），因此同源同形；渲染不了的配置如实注明；
 *  - **正文**是 Markdown 子集（与其它导出格式同一口径），行内 Markdown 按纯文本处理。
 */
object LandingPageExporter {

    data class ExportSection(
        val title: String,
        val content: String,
        val chartOptions: List<JsonObject> = emptyList(),
    )

    data class ExportRequest(
        val title: String,
        val sections: List<ExportSection>,
        /** 副标题（通常是数据集名/口径说明） */
        val subtitle: String = "",
    )

    /** 导出落地页 HTML（单文件、离线、无外部引用） */
    fun export(request: ExportRequest): String {
        val sb = StringBuilder(16 * 1024)
        val title = request.title.ifBlank { "报告" }  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        val summary = request.sections.firstOrNull()?.content
            ?.lineSequence()?.firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("|") }
            ?.trim()?.take(160).orEmpty()

        sb.append("<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>${MarkdownHtml.esc(title)}</title>\n")
        sb.append("<style>\n").append(STYLE).append("</style>\n")
        sb.append("</head>\n<body>\n")

        // hero
        sb.append("<header class=\"hero\">\n")
        sb.append("<div class=\"eyebrow\">STR AGENT · 分析报告</div>\n")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        sb.append("<h1>${MarkdownHtml.esc(title)}</h1>\n")
        if (request.subtitle.isNotBlank()) {
            sb.append("<p class=\"sub\">${MarkdownHtml.esc(request.subtitle)}</p>\n")
        }
        if (summary.isNotBlank()) {
            sb.append("<p class=\"lead\">${MarkdownHtml.esc(summary)}</p>\n")
        }
        sb.append("<div class=\"facts\">")
        sb.append("<span>${request.sections.size} 个章节</span>")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        sb.append("<span>${request.sections.sumOf { it.content.length }} 字正文</span>")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        sb.append("<span>${request.sections.sumOf { it.chartOptions.size }} 张图表</span>")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        sb.append("</div>\n</header>\n")

        sb.append("<main>\n")
        request.sections.forEachIndexed { index, section ->
            sb.append("<section class=\"card\">\n")
            sb.append("<h2>${MarkdownHtml.esc(section.title.ifBlank { "章节${index + 1}" })}</h2>\n")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
            sb.append("<div class=\"body\">\n")
            sb.append(MarkdownHtml.toHtml(section.content))
            sb.append("</div>\n")
            section.chartOptions.forEachIndexed { chartIndex, option ->
                val spec = EChartsOptionReader.read(option)
                sb.append("<figure class=\"chart\">\n")
                if (spec != null) {
                    sb.append(SvgChartRenderer.render(spec, interactive = true)).append('\n')
                } else {
                    sb.append("<div class=\"fallback\">该图表配置暂不支持离线静态渲染（仅柱状图/折线图/饼图/散点图），原始 ECharts option 见 JSON。</div>\n")  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                }
                sb.append("<figcaption>图 ${chartIndex + 1}</figcaption>\n")
                sb.append("</figure>\n")
            }
            sb.append("</section>\n")
        }
        sb.append("</main>\n")
        sb.append(
            "<footer>本文件为离线单文件落地页：不加载任何外部脚本、样式或字体；图表为内联 SVG。" +  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
                "内容由 str-agent 生成，数字均可回溯到原始数据行。</footer>\n",  // lint:allow zh-first（用户面中文文案/结构标记，与语言档位同源）
        )
        sb.append("</body>\n</html>")
        return sb.toString()
    }

    /** 落地页的落款元数据（供调用方写进文件名/回执） */
    fun meta(request: ExportRequest): JsonObject = buildJsonObject {
        put("sections", kotlinx.serialization.json.JsonPrimitive(request.sections.size))
        put("chars", kotlinx.serialization.json.JsonPrimitive(request.sections.sumOf { it.content.length }))
        put("charts", kotlinx.serialization.json.JsonPrimitive(request.sections.sumOf { it.chartOptions.size }))
        put("renderer", kotlinx.serialization.json.JsonPrimitive("static-offline-single-file"))
    }

    private val STYLE = """        :root { --ink:#1f2933; --muted:#6b7785; --line:#e4e8ee; --accent:#4e79a7; --bg:#f7f9fb; }
        * { box-sizing: border-box; }
        body { margin:0; background:var(--bg); color:var(--ink);
               font-family: "Segoe UI", "Microsoft YaHei", system-ui, sans-serif; line-height:1.7; }
        .hero { padding:56px 24px 40px; background:linear-gradient(160deg,#12263a,#1d3f5e); color:#f2f6fa; }
        .hero .eyebrow { letter-spacing:.16em; font-size:12px; opacity:.72; }
        .hero h1 { margin:10px 0 6px; font-size:32px; line-height:1.25; }
        .hero .sub { margin:0 0 10px; opacity:.85; font-size:15px; }
        .hero .lead { margin:0 0 16px; max-width:70ch; opacity:.92; }
        .hero .facts { display:flex; gap:18px; flex-wrap:wrap; font-size:13px; opacity:.8; }
        main { max-width:980px; margin:0 auto; padding:28px 16px 60px; }
        .card { background:#fff; border:1px solid var(--line); border-radius:12px; padding:24px 26px; margin:0 0 22px; }
        .card h2 { margin:0 0 12px; font-size:21px; }
        .body h1 { font-size:20px; } .body h2 { font-size:17px; } .body h3 { font-size:15px; }
        .body table { border-collapse:collapse; margin:12px 0; font-size:14px; }
        .body th, .body td { border:1px solid var(--line); padding:6px 10px; text-align:left; }
        .body th { background:#eef2f7; }
        .body pre { background:#f3f5f8; padding:12px; overflow:auto; border-radius:8px; }
        figure.chart { margin:18px 0 6px; }
        figure.chart svg { max-width:100%; height:auto; display:block; }
        figcaption { color:var(--muted); font-size:12px; margin-top:4px; }
        .fallback { border:1px dashed #bbb; border-radius:8px; padding:12px; color:var(--muted); font-size:13px; }
        footer { max-width:980px; margin:0 auto; padding:0 16px 40px; color:var(--muted); font-size:12px; }
    """.trimIndent()
}
