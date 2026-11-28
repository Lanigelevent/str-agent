package com.stragent.host

import com.stragent.core.ports.VisionClient
import com.stragent.core.ports.VisionImage
import com.stragent.core.ports.VisionRequest
import com.stragent.core.ports.VisionResult
import com.stragent.core.trace.VisionManifest
import org.apache.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-115 扫描件 OCR 兜底测试：夹具 = PDFBox 现建的**真·图片型 PDF**（整页位图、零文本操作），
 * 视觉转写用替身（确定性文本）。
 * ① 图版 PDF + OCR 通道 → 逐页转写导入成功，回执 ocr=true；
 * ② 图版 PDF + 无 OCR 通道 → 既有诚实报错不变（负控）；
 * ③ 文本层 PDF + OCR 通道就位 → 文本层路径零视觉调用（OCR 不劫持文本层）；
 * ④ 页数上限（STRAGENT_OCR_MAX_PAGES 语义）——超出报中文错并指引调高。
 */
class PdfOcrFallbackTest {

    /** 转写替身：确定性文本；记录调用次数与图像（校验「OCR 不劫持文本层」用） */
    private class FakeVision : VisionClient {
        override val id = "fake-ocr"
        val calls = mutableListOf<String>()
        override fun isAvailable() = true
        override fun describe(request: VisionRequest): VisionResult {
            calls += request.prompt.take(6)
            return VisionResult(
                text = "扫描件第${calls.size}页：桥面铺装厚度十公分",
                model = "fake-ocr-model",
                totalTokens = 120,
                manifest = VisionManifest(imageHash = "img", promptHash = "p", outputHash = "o", model = "fake-ocr-model", env = "fake"),
            )
        }
    }

    /** 现建一页图片型 PDF：整页位图（Graphics2D 画字，无任何文本操作符） */
    private fun imageOnlyPdf(pages: Int = 1): File {
        val doc = PDDocument()
        repeat(pages) {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val img = BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, 600, 800)
            g.color = Color.BLACK
            g.drawString("扫描件测试页面内容", 50, 100)
            g.dispose()
            val pd = LosslessFactory.createFromImage(doc, img)
            val cs = org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.OVERWRITE, true, true)
            cs.drawImage(pd, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
            cs.close()
        }
        val f = File.createTempFile("str-agent-ocr-", ".pdf")
        doc.save(f)
        doc.close()
        return f
    }

    @Test
    fun `图版 PDF 经 OCR 兜底导入成功且回执如实`() {
        val vision = FakeVision()
        val pdf = imageOnlyPdf()
        try {
            val r = extractPdfTextWithOcr(pdf, vision)
            assertTrue(r.ocrUsed, "图版 PDF 走 OCR 兜底")
            assertTrue(r.lines.isNotEmpty() && r.lines.first().contains("扫描件第1页"), "替身转写文本成行")
            assertEquals(1, vision.calls.size, "一页一次视觉调用")
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `无 OCR 通道时保持既有诚实报错`() {
        val pdf = imageOnlyPdf()
        try {
            try {
                extractPdfTextWithOcr(pdf, null)
                fail("应当报错")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    e.message!!.contains("扫描件"),
                    "既有报错口径不变：${e.message?.take(60)}",
                )
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `文本层 PDF 零视觉调用——OCR 不劫持文本层`() {
        val vision = FakeVision()
        // 现建一个文本层 PDF（PDFBox 直接写文本）
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        val cs = org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.OVERWRITE, true, true)
        cs.beginText()
        cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
        cs.newLineAtOffset(50f, 700f)
        cs.showText("Second quarter sales 128 wan yuan, growth steady.")
        cs.endText()
        cs.close()
        val f = File.createTempFile("str-agent-ocr-text-", ".pdf")
        doc.save(f)
        doc.close()
        try {
            val r = extractPdfTextWithOcr(f, vision)
            assertFalse(r.ocrUsed, "文本层路径")
            assertEquals(0, vision.calls.size, "OCR 不劫持文本层：零视觉调用")
            assertTrue(r.lines.first().contains("sales"), "文本层内容原样")
        } finally {
            f.delete()
        }
    }

    /** import_text 工具级集成：OCR 兜底走全链，回执 ocr=true 且段落进库 */
    @Test
    fun `import_text 全链——图版 PDF 回执 ocr 为真且段落进库`() {
        val vision = FakeVision()
        val pdf = imageOnlyPdf()
        try {
            val datasets = com.stragent.data.AbilityDatasets()
            val tool = importTextTool(datasets, null, vision)
            val r = tool.invoke(buildJsonObject {
                put("filePath", pdf.absolutePath)
                put("name", "扫描件材料")
            })
            assertEquals("true", r.getValue("ocr").jsonPrimitive.content, "回执 ocr=true")
            assertEquals(1, vision.calls.size, "一页一次视觉调用")
            val dsId = r.getValue("datasetId").jsonPrimitive.content
            val segs = datasets.mustDataset(dsId).text!!.segments
            assertTrue(segs.isNotEmpty() && segs.first().text.contains("扫描件第1页"), "转写文本进段落层")
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun `页数上限——超出报中文错并指引调高`() {
        val vision = FakeVision()
        val pdf = imageOnlyPdf(pages = 2)
        try {
            try {
                extractPdfTextWithOcr(pdf, vision, maxPages = 1)
                fail("应当报错")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    e.message!!.contains("上限") && e.message!!.contains("STRAGENT_OCR_MAX_PAGES"),
                    "上限报错含指引：${e.message?.take(70)}",
                )
            }
            assertEquals(0, vision.calls.size, "超限时一页都不转写（先判后跑）")
        } finally {
            pdf.delete()
        }
    }
}
