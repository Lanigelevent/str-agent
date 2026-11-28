package com.stragent.host

/**
 * 最小 Markdown → HTML 渲染（T-167 抽出，供 HTML 仪表盘与落地页共用）。
 *
 * 子集：标题（`#`/`##`/`###`）、段落、`|` 表格、代码块（``` ）、`<br>` 空行。
 * **先转义再拼装**（[esc]）——正文里的 `<script>` 之类只会变成可见文本，不会被执行。
 * 不做行内 Markdown（粗体/斜体）：与 docx/pdf 导出的纯文本口径一致。
 */
internal object MarkdownHtml {

    fun toHtml(md: String): String {
        val lines = md.lines()
        val sb = StringBuilder()
        var inTable = false
        var inCode = false
        var tableHeader = false

        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCode) { sb.append("</pre>\n"); inCode = false }
                    else { sb.append("<pre>"); inCode = true }
                }
                inCode -> sb.append(esc(line)).append("\n")
                line.startsWith("### ") -> sb.append("<h3>${esc(line.substring(4))}</h3>\n")
                line.startsWith("## ") -> sb.append("<h2>${esc(line.substring(3))}</h2>\n")
                line.startsWith("# ") -> sb.append("<h1>${esc(line.substring(2))}</h1>\n")
                line.startsWith("|") -> {
                    if (!inTable) { sb.append("<table>\n"); inTable = true; tableHeader = true }
                    val cells = line.split("|").filter { it.isNotBlank() }.map { it.trim() }
                    if (cells.all { it.all { c -> c == '-' || c == ':' || c == ' ' } }) {
                        tableHeader = false
                    } else {
                        val tag = if (tableHeader) "th" else "td"
                        sb.append("<tr>")
                        cells.forEach { sb.append("<$tag>${esc(it)}</$tag>") }
                        sb.append("</tr>\n")
                        if (tableHeader) tableHeader = false
                    }
                }
                line.isBlank() -> {
                    if (inTable) { sb.append("</table>\n"); inTable = false }
                    sb.append("<br>\n")
                }
                else -> {
                    if (inTable) { sb.append("</table>\n"); inTable = false }
                    sb.append("<p>${esc(line)}</p>\n")
                }
            }
        }
        if (inTable) sb.append("</table>\n")
        if (inCode) sb.append("</pre>\n")
        return sb.toString()
    }

    /** HTML 转义（防注入） */
    fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
