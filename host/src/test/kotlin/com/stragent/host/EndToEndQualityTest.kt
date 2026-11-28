package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.data.abilityTools
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 端到端质量实证（D7 清空后的补强轮 A）：为 T-166..T-175 补齐**真实场景证据链**。
 *
 * 此前这些能力主要是单元测试 + XML 结构校验；本类用确定性假模型 + 真实导出器跑完整链路：
 * ① 英文报告从工具调用 → prompt 注入 → 门禁 → 回执的端到端；
 * ② docx/pdf/pptx 三格式内嵌图的**像素级**验证（不是只看部件存在，而是图真的画上去了）。
 *
 * 纪律：假模型只能证明**接线与确定性门禁**，不证明真实厂商模型的写作质量（D7 T-020 同口径）。
 */
class EndToEndQualityTest {

    private val csv = "地区,销售额\n华东,1000\n华北,300\n"

    private fun tempHome(): File = Files.createTempDirectory("e2e-quality").toFile()

    /** 带英文结构块的报告（无数字——避开 verifyReport 的数字白名单，专测语言/门禁接线） */
    private val englishReport = """
        ## Overview

        Sales rose in the eastern region.

        | Metric | Full-period value | Peak location | Change |
        |---|---|---|---|
        | Sales | n/a | row 1 | n/a |

        ## Scope

        Based on the imported orders. Judgment applies to the observed sample only.

        ## Questions the data cannot answer

        - Why did the eastern region outperform? We would need campaign data.

        The observation is drawn from the full set (Source: full-set statistic).
    """.trimIndent()

    @Test
    fun `英文报告端到端——语言指令与英文纪律进 prompt，英文结构块过门禁，回执带用量`() {
        val fake = FakeLlmClient(report = englishReport)
        val home = tempHome()
        try {
            StrAgentHostBootstrap.create(home, llm = fake).use { bootstrap ->
                val ledger = com.stragent.core.observability.UsageLedger(costPer1kTokens = 0.001)
                val tools: List<AgentTool> = abilityTools(bootstrap.abilityDatasets) + pluginTools(
                    bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory,
                    usageLedger = ledger,
                )
                val datasetId = (
                    tools.first { it.name == "stragent_import_table" }
                        .invoke(buildJsonObject { put("csv", csv); put("name", "样本") })["datasetId"]
                        as JsonPrimitive
                    ).content

                val receipt = tools.first { it.name == "stragent_generate_report" }.invoke(
                    buildJsonObject {
                        put("datasetId", datasetId)
                        put("language", "en")
                    },
                )

                // ① 语言指令与英文纪律真的进了模型 prompt
                assertTrue(
                    fake.prompts.any { it.contains("entire report in English") },
                    "模型应收到英文语言指令：${fake.prompts.map { it.take(80) }}",
                )
                assertTrue(
                    fake.prompts.any { it.contains("To sum up") && it.contains("Full-period value") },
                    "模型应收到英文套话词表与英文结构规范",
                )
                // ② 回执：语言档生效 + 用量块在
                val profile = receipt["outputProfile"]!!.jsonObject
                val overrides = profile["overrides"]!!.jsonObject
                assertEquals(
                    "en",
                    overrides[com.stragent.core.report.ReportLanguage.PREF_KEY]!!.jsonPrimitive.content,
                    "回执应回显语言档：$profile",
                )
                assertNotNull(receipt["usage"]?.jsonObject, "回执应带用量块")
                // ③ 英文结构块匹配上了门禁——不应报「缺结构块」
                val notes = receipt["qualityNotes"]
                val noteText = notes?.toString().orEmpty()
                assertTrue(
                    !noteText.contains("缺结构块"),
                    "报告用了英文结构块，门禁不该报缺结构块：$noteText",
                )
            }
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun `内嵌图三格式像素级实证——图真的画上去了，且同源同形`() {
        val option = buildJsonObject {
            put("xAxis", buildJsonObject {
                put("data", JsonArray(listOf(JsonPrimitive("华东"), JsonPrimitive("华北"), JsonPrimitive("华南"))))
            })
            put("series", JsonArray(listOf(
                buildJsonObject {
                    put("name", JsonPrimitive("销售额"))
                    put("data", JsonArray(listOf(JsonPrimitive(1000), JsonPrimitive(300), JsonPrimitive(650))))
                },
            )))
        }

        // docx / pptx：直接从 zip 部件取 PNG
        val docxPng = extractZipEntry(
            DocxExporter.exportToBytes(
                DocxExporter.ExportRequest("d", listOf(DocxExporter.ExportSection("c", "正文", listOf(option)))),
            ),
            "word/media/chart1.png",
        )
        val pptxPng = extractZipEntry(
            PptxExporter.exportToBytes(
                PptxExporter.ExportRequest("d", listOf(PptxExporter.ExportSection("c", "正文", listOf(option)))),
            ),
            "ppt/media/image1.png",
        )

        // pdf：用 PDFBox 把内嵌的 XObject 取回
        val pdfPng = org.apache.pdfbox.Loader.loadPDF(
            PdfExporter.exportToBytes(
                PdfExporter.ExportRequest("d", listOf(PdfExporter.ExportSection("c", "正文", listOf(option)))),
            ),
        ).use { doc ->
            val page = doc.getPage(0)
            val resources = page.resources
            val xobjects = resources.xObjectNames
            val image = xobjects.asSequence()
                .map { resources.getXObject(it) }
                .filterIsInstance<org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject>()
                .firstOrNull() ?: error("PDF 中未找到内嵌图片 XObject")
            image.image.rasterBytes()
        }

        val images = listOf("docx" to docxPng, "pptx" to pptxPng, "pdf" to pdfPng)
        images.forEach { (format, bytes) ->
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            assertNotNull(img, "$format 内嵌图不是合法 PNG")
            assertTrue(img.width > 100 && img.height > 50, "$format 图尺寸异常：${img.width}x${img.height}")
            val nonWhite = img.countNonWhite()
            assertTrue(nonWhite > 500, "$format 内嵌图像是空白（非白像素只有 $nonWhite）——图没真画上去")
        }
        // 同源：三格式的内嵌图宽高一致（同一个 SvgChartRenderer 渲染）
        val sizes = images.map { (_, bytes) ->
            val img = ImageIO.read(ByteArrayInputStream(bytes))
            "${img.width}x${img.height}"
        }.toSet()
        assertEquals(1, sizes.size, "三格式图尺寸应一致（同一渲染器）：$sizes")
    }

    private fun extractZipEntry(zipBytes: ByteArray, name: String): ByteArray {
        java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        error("zip 中缺部件：$name")
    }

    private fun BufferedImage.countNonWhite(): Int {
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val rgb = getRGB(x, y) and 0xFFFFFF
                if (rgb != 0xFFFFFF) count++
                x++
            }
            y++
        }
        return count
    }

    /** PDImageXObject.image 是 RenderedImage（不是 BufferedImage）——转成 PNG 字节再统一读 */
    private fun java.awt.image.RenderedImage.rasterBytes(): ByteArray {
        val img = this as? BufferedImage ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { out ->
            @Suppress("DEPRECATION")
            val g = out.createGraphics()
            try {
                g.drawImage(this@rasterBytes as java.awt.Image, 0, 0, null)
            } finally {
                g.dispose()
            }
        }
        val buffer = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", buffer)
        return buffer.toByteArray()
    }
}
