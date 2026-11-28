package com.stragent.host

import com.stragent.core.chart.EChartsOptionReader
import com.stragent.core.chart.SvgChartRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * HTML 仪表盘导出器 —— D11 v3 S3 输出格式导出（T-054 去 CDN）。
 *
 * 将报告章节 + ECharts JSON option 组合为**单文件、离线可读**的 HTML：
 *  - 图表：ECharts option → [EChartsOptionReader] → [SvgChartRenderer] 内联 SVG
 *    （不再从 CDN 加载 echarts.min.js，也不生成 setOption 脚本）；
 *  - 静态渲染不了的配置（热力图/雷达图/混合图…）→ 明示「本文件未渲染」并附原始 option，
 *    不假装画出来；
 *  - 正文：最小 Markdown 子集（标题/段落/表格/代码块）。
 *
 * 纯字符串拼装、确定性——同一输入产出同一份 HTML。
 */
object HtmlDashboardExporter {

    /** 导出请求 */
    data class ExportSection(
        val title: String,
        val content: String,
        val chartOptions: List<JsonObject> = emptyList(),
    )

    data class ExportRequest(
        val title: String,
        val sections: List<ExportSection>,
    )

    private val prettyJson = Json { prettyPrint = true }

    fun export(request: ExportRequest): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n")
        sb.append("<meta charset=\"UTF-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        sb.append("<title>${MarkdownHtml.esc(request.title)}</title>\n")
        sb.append("<style>\n")
        sb.append("body { font-family: -apple-system, sans-serif; max-width: 960px; margin: 0 auto; padding: 20px; color: #333; }\n")
        sb.append("h1 { color: #1a1a2e; border-bottom: 2px solid #4E79A7; padding-bottom: 8px; }\n")
        sb.append("h2 { color: #16213e; margin-top: 32px; }\n")
        sb.append("h3 { color: #0f3460; }\n")
        sb.append("table { border-collapse: collapse; width: 100%; margin: 12px 0; }\n")
        sb.append("th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }\n")
        sb.append("th { background: #4E79A7; color: white; font-weight: bold; }\n")
        sb.append("tr:nth-child(even) { background: #f8f9fa; }\n")
        sb.append("code { background: #f1f3f5; padding: 2px 6px; border-radius: 3px; font-family: monospace; }\n")
        sb.append("pre { background: #f8f9fa; padding: 16px; border-radius: 6px; overflow-x: auto; }\n")
        sb.append("figure.chart { margin: 16px 0; }\n")
        sb.append("figure.chart svg { max-width: 100%; height: auto; display: block; }\n")
        sb.append(".chart-fallback { border: 1px dashed #bbb; border-radius: 6px; padding: 12px; background: #fafafa; }\n")
        sb.append(".chart-fallback p { margin: 0 0 8px; color: #666; font-size: 13px; }\n")
        sb.append(".chart-tip { display: none; position: fixed; z-index: 9; background: rgba(26,26,46,0.92); color: #fff; padding: 4px 10px; border-radius: 4px; font-size: 12px; pointer-events: none; }\n")
        sb.append("footer { margin-top: 40px; padding-top: 12px; border-top: 1px solid #eee; color: #999; font-size: 12px; }\n")
        sb.append("</style>\n")
        sb.append("</head>\n<body>\n")
        sb.append("<h1>${MarkdownHtml.esc(request.title)}</h1>\n")

        var chartIdx = 0
        for (section in request.sections) {
            sb.append("<h2>${MarkdownHtml.esc(section.title)}</h2>\n")
            sb.append(MarkdownHtml.toHtml(section.content))
            for (option in section.chartOptions) {
                sb.append("<figure class=\"chart\" id=\"chart_$chartIdx\">\n")
                val spec = EChartsOptionReader.read(option)
                if (spec != null) {
                    sb.append(SvgChartRenderer.render(spec, interactive = true)).append('\n')
                } else {
                    sb.append("<div class=\"chart-fallback\">\n")
                    sb.append("<p>该图表配置暂不支持离线静态渲染（仅支持柱状图/折线图/饼图/散点图；热力图、雷达图、箱线图或缺数据的配置不画）。以下为原始 ECharts option，可交给支持交互渲染的环境查看。</p>\n")
                    sb.append("<pre>").append(MarkdownHtml.esc(prettyJson.encodeToString(JsonElement.serializer(), option))).append("</pre>\n")
                    sb.append("</div>\n")
                }
                sb.append("</figure>\n")
                chartIdx++
            }
        }

        sb.append(INTERACTIVE_JS)
        sb.append("<footer>本文件为离线单文件：不加载外部脚本、样式或字体；图表为内联 SVG + 内嵌交互（悬停看数值、点图例开合系列），可直接分享与归档。</footer>\n")
        sb.append("</body>\n</html>")
        return sb.toString()
    }

    /**
     * T-168 · 交互层（内嵌 JS，无 CDN、无外部引用）：
     *  - 悬停 tooltip：跟随鼠标显示 `系列 · 类目 · 数值`（读 SVG 数据形状的 data-*）；
     *  - 图例开合：点图例色块切换对应系列的显隐（再点恢复；饼图切换扇区）。
     * 确定性保留：脚本是静态模板，同一输入产出同一份 HTML。
     * 边界：图例文本是截断显示（完整名在 data-legend）；饼图切走扇区时 % 标签仍在。
     */
    private val INTERACTIVE_JS = """
<script>
(function () {
  var tip = document.createElement('div');
  tip.className = 'chart-tip';
  document.body.appendChild(tip);
  document.querySelectorAll('figure.chart svg').forEach(function (svg) {
    svg.addEventListener('mousemove', function (ev) {
      var el = ev.target.closest('[data-v],[data-y]');
      if (!el) { tip.style.display = 'none'; return; }
      var parts = [];
      var name = el.getAttribute('data-name');
      var cat = el.getAttribute('data-cat');
      var v = el.getAttribute('data-v');
      var x = el.getAttribute('data-x');
      var y = el.getAttribute('data-y');
      var pct = el.getAttribute('data-pct');
      if (name) parts.push(name);
      if (cat) parts.push(cat);
      if (v) parts.push(v);
      if (x) parts.push('(' + x + ', ' + y + ')');
      if (pct) parts.push(pct + '%');
      var text = parts.filter(Boolean).join(' \u00b7 ');
      tip.textContent = text;
      tip.style.display = text ? 'block' : 'none';
      tip.style.left = (ev.clientX + 12) + 'px';
      tip.style.top = (ev.clientY + 12) + 'px';
    });
    svg.addEventListener('mouseleave', function () { tip.style.display = 'none'; });
    svg.querySelectorAll('[data-legend]').forEach(function (rect) {
      rect.style.cursor = 'pointer';
      rect.addEventListener('click', function () {
        var name = rect.getAttribute('data-legend');
        var shapes = svg.querySelectorAll('[data-name="' + name + '"]');
        var hidden = shapes.length > 0 && shapes[0].style.display === 'none';
        shapes.forEach(function (s) { s.style.display = hidden ? '' : 'none'; });
        rect.style.opacity = hidden ? '1' : '0.3';
        if (name === '') {
          svg.querySelectorAll('[data-x]').forEach(function (s) { s.style.display = hidden ? '' : 'none'; });
        }
        var line = svg.querySelector('[data-line="' + name + '"]');
        if (line) line.style.display = hidden ? '' : 'none';
        if (name === '') { svg.querySelectorAll('[data-x]').forEach(function (s) { s.style.display = hidden ? '' : 'none'; }); }
      });
    });
  });
})();
</script>
""".trimIndent() + "\n"
}
