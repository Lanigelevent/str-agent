package com.stragent.host

import com.stragent.core.ports.AgentTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 报告导出工具 —— D11 v3 S3 输出格式导出（T-054 诚实化）。
 *
 * 将报告内容导出为真正的 Excel（.xlsx，多工作表）或离线 HTML 仪表盘。
 * 纯 JVM、确定性——同一输入产出同一输出/同一份字节。
 *
 * 诚实边界：
 *  - Excel 全量在内存构建（未流式写入），工作表数与单元格数受 JVM 堆约束；
 *  - HTML 只静态渲染柱状图/折线图/饼图/散点图（内联 SVG）；其他图表配置
 *    附原始 option 并明示「未渲染」；
 *  - 不支持 PDF/PPT（后续阶段）。
 */
fun exportReportTool(
    outputDir: File? = null,
    /** T-205：数据集库——解析当前数据集，为报告元信息节提供数据集名 */
    datasets: com.stragent.data.AbilityDatasets? = null,
    /** T-205：溯源留痕器——派生数据时点（第一步调用捕获的 importedAt） */
    provenance: com.stragent.data.ProvenanceRecorder? = null,
): AgentTool = object : AgentTool {
    override val name = "stragent_export_report"
    override val description =
        "将报告内容导出为可分享文件：Excel（.xlsx 多工作表）/ Word（.docx）/ PDF（.pdf）/ PowerPoint（.pptx）/ 交互式 HTML 仪表盘 / 离线落地页。" +
        "Excel：首表「报告概要」，每章一张工作表；Word/PDF/PPT：标题 + 章节 + 段落 + 真 Markdown 表格（PPT 为原生表格），可渲染的图表**内嵌为图片**。" +
        "HTML：单文件离线可读（无 CDN），图表内联 SVG + 内嵌交互（悬停 tooltip、点图例开合系列）；landing：报告的对外门面（hero + 分章 + 图表，离线单文件）。" +
        "用途：将分析报告导出为可分享的文件。" +
        "输出：excel/docx/pdf/pptx 返回文件路径，html/landing 返回完整 HTML 文本。" +
        "参数：format（excel/docx/pdf/pptx/landing/html）、title（报告标题）、sections（章节数组）。"

    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("format", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(
                    JsonPrimitive("excel"), JsonPrimitive("docx"), JsonPrimitive("pdf"),
                    JsonPrimitive("pptx"), JsonPrimitive("landing"), JsonPrimitive("html")
                )))
                put("description", "导出格式：excel/.xlsx、docx/.docx（Word）、pdf/.pdf、pptx/.pptx（PowerPoint）、landing（离线落地页）、html（交互式单文件仪表盘），缺省 html")
            })
            put("title", buildJsonObject {
                put("type", "string")
                put("description", "报告标题")
            })
            put("subtitle", buildJsonObject {
                put("type", "string")
                put("description", "副标题（仅 landing 格式用；通常是数据集名或口径说明）")  // lint:allow zh-first（用户面中文输出，与导出文案同源）
            })
            put("sections", buildJsonObject {
                put("type", "array")
                put("description", "章节数组，每个章节含 title（标题）、content（Markdown 内容）、chartOptions（可选，ECharts JSON option 数组；HTML 中静态渲染为内联 SVG）")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject { put("type", "string") })
                        put("content", buildJsonObject { put("type", "string") })
                        put("chartOptions", buildJsonObject {
                            put("type", "array")
                            put("description", "ECharts JSON option 数组（HTML 中渲染为交互式 SVG；docx/pdf/pptx 中可渲染的图表内嵌为图片，其余如实注明未嵌入）")
                        })
                    })
                })
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("title"))))
    }

    override val cacheable = false

    // 写盘（同名文件覆盖）——不是只读，且具有破坏性
    override val readOnly = false
    override val destructive = true

    override val outputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("format", buildJsonObject { put("type", "string") })
            put("filePath", buildJsonObject { put("type", "string"); put("description", "导出文件路径（excel= .xlsx / docx= .docx / pdf= .pdf / pptx= .pptx）") })
            put("html", buildJsonObject { put("type", "string"); put("description", "完整 HTML 文本（仅 html 格式）") })
            put("sizeBytes", buildJsonObject { put("type", "integer") })
        })
    }

    override fun invoke(args: JsonObject): JsonObject {
        val format = args.string("format") ?: "html"
        val title = args.string("title") ?: "导出报告"

        @Suppress("UNCHECKED_CAST")
        val sectionsRaw = args["sections"] as? JsonArray
        val sections = sectionsRaw?.map { sec ->
            val secObj = sec.jsonObject
            HtmlDashboardExporter.ExportSection(
                title = secObj["title"]?.jsonPrimitive?.content ?: "",
                content = secObj["content"]?.jsonPrimitive?.content ?: "",
                chartOptions = (secObj["chartOptions"] as? JsonArray)?.map { it.jsonObject } ?: emptyList(),
            )
        } ?: emptyList()

        // T-205：报告元信息节——数据时点自动标注（有分析留痕才插，不编造；全格式可见）
        val metaSection = datasets?.currentId()?.let { dsId ->
            provenance?.manifest(dsId)?.let { manifest ->
                val asOf = manifest.steps.firstNotNullOfOrNull { it.datasetAsOf }
                if (asOf != null) {
                    val ds = datasets.byId(dsId)
                    HtmlDashboardExporter.ExportSection(
                        title = "报告元信息",  // lint:allow zh-first（T-205 用户面文案，D19/D21 语言定位）
                        content = "- 数据集：${ds?.name ?: dsId}（短哈希 ${manifest.datasetHash.take(8)}）\n" +  // lint:allow zh-first（T-205 用户面文案，D19/D21 语言定位）
                            "- 数据时点：${java.time.Instant.ofEpochMilli(asOf)}（结论基于该时点导入的数据；时效标注，非有效性判断）",  // lint:allow zh-first（T-205 用户面文案，D19/D21 语言定位）
                        chartOptions = emptyList(),
                    )
                } else null
            }
        }
        val allSections = if (metaSection != null) listOf(metaSection) + sections else sections

        return when (format) {
            "excel" -> {
                val request = XlsxExporter.ExportRequest(
                    title = title,
                    sections = allSections.map { XlsxExporter.ExportSection(it.title, it.content) },
                )
                val dir = outputDir ?: File(System.getProperty("java.io.tmpdir"), "str-agent-export")
                dir.mkdirs()
                val file = XlsxExporter.exportToFile(request, dir)
                buildJsonObject {
                    put("format", "excel")
                    put("filePath", file.absolutePath)
                    put("sizeBytes", file.length())
                }
            }
            "html" -> {
                val request = HtmlDashboardExporter.ExportRequest(title = title, sections = allSections)
                val html = HtmlDashboardExporter.export(request)
                buildJsonObject {
                    put("format", "html")
                    put("html", html)
                    put("sizeBytes", html.toByteArray(Charsets.UTF_8).size)
                }
            }
            "docx" -> {
                val request = DocxExporter.ExportRequest(
                    title = title,
                    sections = allSections.map { DocxExporter.ExportSection(it.title, it.content, it.chartOptions) },
                )
                val dir = outputDir ?: File(System.getProperty("java.io.tmpdir"), "str-agent-export")
                val file = DocxExporter.exportToFile(request, dir)
                buildJsonObject {
                    put("format", "docx")
                    put("filePath", file.absolutePath)
                    put("sizeBytes", file.length())
                }
            }
            "pdf" -> {
                val request = PdfExporter.ExportRequest(
                    title = title,
                    sections = allSections.map { PdfExporter.ExportSection(it.title, it.content, it.chartOptions) },
                )
                val dir = outputDir ?: File(System.getProperty("java.io.tmpdir"), "stragent-export")
                val file = PdfExporter.exportToFile(request, dir)
                buildJsonObject {
                    put("format", "pdf")
                    put("filePath", file.absolutePath)
                    put("sizeBytes", file.length())
                }
            }
            "pptx" -> {
                val request = PptxExporter.ExportRequest(
                    title = title,
                    sections = allSections.map { PptxExporter.ExportSection(it.title, it.content, it.chartOptions) },
                )
                val dir = outputDir ?: File(System.getProperty("java.io.tmpdir"), "str-agent-export")
                val file = PptxExporter.exportToFile(request, dir)
                buildJsonObject {
                    put("format", "pptx")
                    put("filePath", file.absolutePath)
                    put("sizeBytes", file.length())
                }
            }
            "landing" -> {
                val request = LandingPageExporter.ExportRequest(
                    title = title,
                    sections = allSections.map {
                        LandingPageExporter.ExportSection(it.title, it.content, it.chartOptions)
                    },
                    subtitle = args.string("subtitle") ?: "",
                )
                val html = LandingPageExporter.export(request)
                buildJsonObject {
                    put("format", "landing")
                    put("html", html)
                    put("sizeBytes", html.toByteArray(Charsets.UTF_8).size)
                    put("meta", LandingPageExporter.meta(request))
                }
            }
            else -> throw IllegalArgumentException("不支持的导出格式「$format」：请用 excel/docx/pdf/pptx/landing/html")
        }
    }
}
