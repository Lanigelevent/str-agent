package com.stragent.host

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont

/**
 * PDF 导出器（T-167）—— Apache PDFBox 3（既有依赖，PDF 导入已在用）。
 *
 * 内容映射（与 xlsx/docx 同一 Markdown 子集）：标题 → 首页大字 + 段落换行 + 表格网格
 * （跨页续排、跨页重复表头）；chartOptions 不嵌入（同 docx 口径，如实注明）。
 *
 * 中文字体：**系统字体探测**（Windows msyh/simsun → Linux Noto CJK → macOS PingFang），
 * 子集嵌入（PDType0Font）；一个都探测不到就如实报错（列出探测路径），不降级到西文
 * 字体把中文画成乱码。
 *
 * 边界（诚实）：PDF trailer 文档 ID 由 PDFBox 随机生成——不承诺字节级确定性
 * （内容确定性保留：同输入提取文本一致）；行内 Markdown 标记按纯文本处理。
 */
object PdfExporter {

    data class ExportSection(
        val title: String,
        val content: String,
        val chartOptions: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    )

    data class ExportRequest(val title: String, val sections: List<ExportSection>)

    fun exportToFile(request: ExportRequest, outputDir: File): File {
        outputDir.mkdirs()
        // createTempFile 的前缀至少 3 字符——短标题（如「A」）补齐，避免 IllegalArgumentException
        val prefix = (sanitizeFileName(request.title) + "-").padEnd(3, '_')
        val file = File.createTempFile(prefix, ".pdf", outputDir)
        file.writeBytes(exportToBytes(request))
        return file
    }

    fun exportToBytes(request: ExportRequest): ByteArray {
        val doc = PDDocument()
        var ttc: java.lang.AutoCloseable? = null
        try {
            val (font, ttcHandle) = cjkFont(doc)
            ttc = ttcHandle
            val layout = PdfLayout(doc, font)
            layout.title(request.title)
            request.sections.forEachIndexed { index, section ->
                layout.heading(section.title.ifBlank { "章节${index + 1}" })  // lint:allow zh-first（用户面错误提示/字体探测文案，如实报错）
                layout.sectionContent(section)
            }
            layout.finish()
            val info = doc.documentInformation
            info.title = request.title
            info.creationDate = fixedDate()
            info.modificationDate = fixedDate()
            return ByteArrayOutputStream().use { buffer ->
                doc.save(buffer)
                buffer.toByteArray()
            }
        } finally {
            runCatching { ttc?.close() }
            doc.close()
        }
    }

    private fun fixedDate(): Calendar = GregorianCalendar(1980, Calendar.JANUARY, 1, 0, 0, 0).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^\\u4e00-\\u9fa5a-zA-Z0-9_\\-]"), "_").take(50).ifBlank { "export" }

    // ---------- CJK 字体探测（首版：直载 .ttf 与按名取 .ttc 内部字体） ----------

    private data class FontCandidate(val path: String, val ttcNames: List<String>?)

    private val FONT_CANDIDATES = listOf(
        FontCandidate("C:/Windows/Fonts/msyh.ttc", listOf("MicrosoftYaHei", "Microsoft YaHei", "MSYH")),
        FontCandidate("C:/Windows/Fonts/simsun.ttc", listOf("SimSun", "NSimSun")),
        FontCandidate("C:/Windows/Fonts/msyh.ttf", null),
        FontCandidate("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", listOf("WenQuanYiMicroHei")),
        FontCandidate("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", listOf("NotoSansCJKsc-Regular")),
        FontCandidate("/System/Library/Fonts/PingFang.ttc", listOf("PingFangSC-Regular")),
    )

    /** 探测并加载一个可嵌入的 CJK 字体；返回 (字体, 需在 save 后关闭的句柄或 null) */
    internal fun cjkFont(doc: PDDocument): Pair<PDFont, java.lang.AutoCloseable?> {
        val failures = mutableListOf<String>()
        for (candidate in FONT_CANDIDATES) {
            val file = File(candidate.path)
            if (!file.isFile) continue
            var ttc: org.apache.fontbox.ttf.TrueTypeCollection? = null
            try {
                // .ttc 内部字体对 msyh/simsun/Noto 全是同族 CJK——枚举取第一个即可，不对名字赌博
                val font: PDFont
                if (candidate.ttcNames != null) {
                    ttc = org.apache.fontbox.ttf.TrueTypeCollection(file)
                    var loaded: org.apache.pdfbox.pdmodel.font.PDType0Font? = null
                    ttc.processAllFonts { ttf ->
                        if (loaded == null) loaded = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, ttf, true)
                    }
                    font = loaded ?: throw IllegalStateException("ttc 内无字体：${candidate.path}")  // lint:allow zh-first（用户面错误提示/字体探测文案，如实报错）
                } else {
                    font = org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc, file.inputStream(), false)
                }
                // 冒烟：字体内必须真的有「中」字（防文件命中但无 CJK 覆盖）
                if (font.getStringWidth("中") <= 0) throw IllegalStateException("字体缺 CJK 覆盖：${candidate.path}")  // lint:allow zh-first（用户面错误提示/字体探测文案，如实报错）
                return font to ttc
            } catch (e: Exception) {
                ttc?.close()
                failures.add("${candidate.path}: ${e.message}")
            }
        }
        throw IllegalStateException(
            "未找到可嵌入的中文字体。已探测：" + failures.joinToString("; "),  // lint:allow zh-first（用户面错误提示/字体探测文案，如实报错）
        )
    }
}
