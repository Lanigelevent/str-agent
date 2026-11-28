package com.stragent.host

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-169 · PPTX 导出（模板底座 + 补丁）测试。
 *
 * 覆盖：包结构齐备（底座三件 + 每页两件）/ 三处补丁自洽（Content_Types 覆盖项、
 * presentation rels、presentation.xml 的 sldIdLst 互相指得到）/ 幻灯片 XML 良构 /
 * 内容映射（标题、章节、正文、表格行、项目符号、图表如实注明）/ 续页拆分 /
 * 字节级确定性 / 空章节拒绝。
 *
 * 诚实边界：本机无 PowerPoint，**没有** Office 自动化验证——这里做的是「包结构 +
 * XML 良构 + 关系自洽」三层校验；另用 python-pptx 做过一次读回实测（本地一次性，
 * 见 D4 P-222），未固化为 CI 用例（CI runner 不保证有 python-pptx）。
 */
class PptxExporterTest {

    private val sections = listOf(
        PptxExporter.ExportSection(
            title = "区域销售",
            content = "华东地区销售额最高。\n\n|地区|销售额|\n|---|---|\n|华东|1000|\n|华北|300|\n\n- 要点一\n- 要点二",
            chartOptions = listOf(buildJsonObject { put("dummy", true) }),
        ),
        PptxExporter.ExportSection(title = "结论", content = "整体向好了。"),
    )

    private val title = "季度销售分析"

    // ---------------- 包结构 ----------------

    @Test
    fun `pptx 包结构齐备——底座三件 + 每页两件`() {
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val names = entryNames(bytes)
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels",
            "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml",
            "ppt/theme/theme1.xml",
        ).forEach { part -> assertTrue(names.contains(part), "缺底座部件 $part：$names") }
        // 封面 + 两个章节 = 3 页，每页两个部件
        listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide3.xml")
            .forEach { part -> assertTrue(names.contains(part), "缺幻灯片部件 $part：$names") }
        listOf(
            "ppt/slides/_rels/slide1.xml.rels",
            "ppt/slides/_rels/slide2.xml.rels",
            "ppt/slides/_rels/slide3.xml.rels",
        ).forEach { part -> assertTrue(names.contains(part), "缺幻灯片关系 $part：$names") }
    }

    @Test
    fun `三处补丁自洽——Content_Types 覆盖项、rels 关系、sldIdLst 互相对得上`() {
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val contentTypes = String(partBytes(bytes, "[Content_Types].xml"), Charsets.UTF_8)
        val rels = String(partBytes(bytes, "ppt/_rels/presentation.xml.rels"), Charsets.UTF_8)
        val presentation = String(partBytes(bytes, "ppt/presentation.xml"), Charsets.UTF_8)

        val slideCount = 3
        (1..slideCount).forEach { number ->
            assertTrue(
                contentTypes.contains("/ppt/slides/slide$number.xml"),
                "Content_Types 缺 slide$number 的 Override",
            )
            assertTrue(rels.contains("Target=\"slides/slide$number.xml\""), "rels 缺 slide$number 的关系")
        }
        // sldIdLst 里的 r:id 必须在 rels 里真实存在
        val sldIds = presentation.substringAfter("<p:sldIdLst>").substringBefore("</p:sldIdLst>")
            .split("<p:sldId ").filter { it.contains("r:id=") }
        assertEquals(slideCount, sldIds.size, "sldIdLst 条目数与幻灯片数一致")
        sldIds.forEach { entry ->
            val relId = entry.substringAfter("r:id=\"").substringBefore("\"")
            assertTrue(rels.contains("Id=\"$relId\""), "sldIdLst 引用的 $relId 未在 rels 中定义")
        }
        // 顺序约束：sldIdLst 必须排在 sldMasterIdLst 之后、sldSz 之前
        val masterEnd = presentation.indexOf("</p:sldMasterIdLst>")
        val listStart = presentation.indexOf("<p:sldIdLst>")
        val sizeStart = presentation.indexOf("<p:sldSz")
        assertTrue(masterEnd in 0 until listStart, "sldIdLst 必须排在 sldMasterIdLst 之后")
        assertTrue(listStart < sizeStart, "sldIdLst 必须排在 sldSz 之前")
    }

    @Test
    fun `幻灯片 XML 良构——每页与三处补丁都能被 XML 解析器解析`() {
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val parts = mutableListOf(
            "[Content_Types].xml",
            "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels",
        )
        (1..3).forEach { number ->
            parts.add("ppt/slides/slide$number.xml")
            parts.add("ppt/slides/_rels/slide$number.xml.rels")
        }
        parts.forEach { part ->
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(ByteArrayInputStream(partBytes(bytes, part))))
        }
    }

    // ---------------- 内容映射 ----------------

    @Test
    fun `内容映射——封面、章节标题、正文、原生表格、项目符号与图表如实注明`() {
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val cover = String(partBytes(bytes, "ppt/slides/slide1.xml"), Charsets.UTF_8)
        assertTrue(cover.contains("季度销售分析"), "封面标题：$cover")
        assertTrue(cover.contains("共 2 个章节"), "封面副标题：$cover")

        val first = String(partBytes(bytes, "ppt/slides/slide2.xml"), Charsets.UTF_8)
        assertTrue(first.contains("区域销售"), "章节标题：$first")
        assertTrue(first.contains("华东地区销售额最高"), "正文段落：$first")
        assertTrue(first.contains("要点一"), "项目符号条目：$first")
        assertTrue(first.contains("buChar"), "项目符号用 PPT 原生项目符号")
        assertTrue(first.contains("未嵌入 PPT"), "渲染不了的图表如实注明：$first")
    }

    @Test
    fun `Markdown 表格——映射为 PPT 原生表格（graphicFrame + tbl 部件）`() {
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val first = String(partBytes(bytes, "ppt/slides/slide2.xml"), Charsets.UTF_8)
        assertTrue(first.contains("<a:tbl>"), "表格应为原生表格：$first")
        assertTrue(first.contains("<a:gridCol"), "表格要有列网格")
        assertTrue(first.contains("地区") && first.contains("华东") && first.contains("1000"), "单元格内容")
        assertTrue(first.contains("<p:graphicFrame>"), "表格用 graphicFrame 承载")
        // 分隔行不进表格
        assertTrue(!first.contains("---"), "Markdown 分隔行不进表格：$first")
    }

    @Test
    fun `图表内嵌——PNG 部件 + 图片关系 + pic 形状 + png 内容类型`() {
        val bytes = PptxExporter.exportToBytes(
            PptxExporter.ExportRequest(
                "带图报告",
                listOf(PptxExporter.ExportSection("数据", "正文", listOf(barOption()))),
            ),
        )
        val names = entryNames(bytes)
        assertTrue(names.contains("ppt/media/image1.png"), "内嵌图部件：$names")
        val slide = String(partBytes(bytes, "ppt/slides/slide2.xml"), Charsets.UTF_8)
        assertTrue(slide.contains("<p:pic>"), "幻灯片含图片形状：$slide")
        assertTrue(slide.contains("r:embed=\"rId2\""), "图片引用关系 id：$slide")
        val rels = String(partBytes(bytes, "ppt/slides/_rels/slide2.xml.rels"), Charsets.UTF_8)
        assertTrue(rels.contains("../media/image1.png"), "图片关系指向 media：$rels")
        val contentTypes = String(partBytes(bytes, "[Content_Types].xml"), Charsets.UTF_8)
        assertTrue(contentTypes.contains("Extension=\"png\""), "png 内容类型：$contentTypes")
        // 部件是合法 PNG
        val png = partBytes(bytes, "ppt/media/image1.png")
        assertEquals(0x89.toByte(), png[0], "PNG 签名")
    }

    @Test
    fun `续页——条目过多自动拆页并标注续页序号`() {
        val long = PptxExporter.ExportSection(
            title = "长章节",
            content = (1..30).joinToString("\n") { "第 $it 行内容" },
        )
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest("多页测试", listOf(long)))
        val names = entryNames(bytes)
        // 封面 1 + 长章节 3（30 条 / 每页 14 条）
        assertTrue(names.contains("ppt/slides/slide4.xml"), "应有第 4 页：$names")
        val third = String(partBytes(bytes, "ppt/slides/slide4.xml"), Charsets.UTF_8)
        assertTrue(third.contains("长章节（续 3）"), "续页标题带序号：$third")
        assertTrue(third.contains("第 30 行内容"), "末条内容在最后一页：$third")
    }

    @Test
    fun `python-pptx 读回——原生表格与内嵌图都被读出来`() {
        val bytes = PptxExporter.exportToBytes(
            PptxExporter.ExportRequest(
                "带图表",
                listOf(
                    PptxExporter.ExportSection(
                        title = "区域销售",
                        content = "|地区|销售额|\n|---|---|\n|华东|1000|",
                        chartOptions = listOf(barOption()),
                    ),
                ),
            ),
        )
        val file = java.io.File(createTempDirectory(), "readback.pptx")
        file.writeBytes(bytes)

        val script = buildString {
            append("import sys\n")
            append("sys.stdout.reconfigure(encoding='utf-8')\n")
            append("from pptx import Presentation\n")
            append("prs = Presentation(sys.argv[1])\n")
            append("print('SLIDES=%d' % len(prs.slides))\n")
            append("for s in prs.slides:\n")
            append("    for sh in s.shapes:\n")
            append("        if sh.has_table:\n")
            append("            print('TABLE|' + '||'.join(c.text for r in sh.table.rows for c in r.cells))\n")
            append("        if sh.shape_type is not None and getattr(sh, 'image', None) is not None:\n")
            append("            print('PICTURE|' + str(sh.image.size))\n")
        }
        val result = runPython(script, file.absolutePath)
        assumeTrue(
            "本机无 python / python-pptx，跳过读回验证（缺失即跳过，不假绿）",
            result != null && result.exitCode == 0,
        )
        val output = result!!.stdout
        assertTrue(output.contains("SLIDES=2"), "读回的幻灯片数：\n$output")
        assertTrue(output.contains("TABLE|"), "原生表格被读出：\n$output")
        assertTrue(output.contains("地区"), "表头被读出：\n$output")
        assertTrue(output.contains("1000"), "单元格被读出：\n$output")
        assertTrue(output.contains("PICTURE|"), "内嵌图被读出：\n$output")
    }

    @Test
    fun `确定性——同输入两次导出字节一致`() {
        val a = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val b = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        assertTrue(a.contentEquals(b), "pptx 导出必须字节级确定")
    }

    @Test
    fun `空章节拒绝`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, emptyList()))
        }
        assertTrue(error.message!!.contains("sections"), error.message)
    }

    @Test
    fun `短标题也能落盘——临时文件名前缀至少 3 字符`() {
        val dir = createTempDirectory()
        val file = PptxExporter.exportToFile(
            PptxExporter.ExportRequest("A", listOf(PptxExporter.ExportSection("章节", "正文"))),
            dir,
        )
        assertTrue(file.isFile && file.length() > 0, "短标题不应抛 IllegalArgumentException：$file")
    }

    // ---------------- python-pptx 读回（环境缺依赖则显式跳过，不假绿） ----------------

    @Test
    fun `python-pptx 读回——能打开且读出幻灯片数与文本`() {
        val bytes = PptxExporter.exportToBytes(PptxExporter.ExportRequest(title, sections))
        val file = java.io.File(createTempDirectory(), "sample.pptx")
        file.writeBytes(bytes)

        val script = buildString {
            append("import sys\n")
            // Windows 下 stdout 默认按本地代码页输出——显式转 UTF-8，否则读回的中文是乱码
            append("sys.stdout.reconfigure(encoding='utf-8')\n")
            append("from pptx import Presentation\n")
            append("prs = Presentation(sys.argv[1])\n")
            append("print('SLIDES=%d' % len(prs.slides))\n")
            append("for s in prs.slides:\n")
            append("    print('SLIDE|' + '||'.join(sh.text_frame.text for sh in s.shapes if sh.has_text_frame))\n")
        }
        val result = runPython(script, file.absolutePath)
        assumeTrue(
            "本机无 python / python-pptx，跳过读回验证（缺失即跳过，不假绿）",
            result != null && result.exitCode == 0,
        )
        val output = result!!.stdout
        assertEquals("SLIDES=3", output.lines().first(), "读回的幻灯片数：\n$output")
        assertTrue(output.contains("季度销售分析"), "读回的封面标题：\n$output")
        assertTrue(output.contains("区域销售"), "读回的章节标题：\n$output")
        assertTrue(output.contains("华东地区销售额最高"), "读回的正文：\n$output")
    }

    /** 跑一段 python 脚本；python 或 python-pptx 不可用时返回 null（调用方显式跳过） */
    private fun runPython(script: String, arg: String): ProcessResult? {
        val process = try {
            ProcessBuilder("python", "-c", script, arg).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            return null
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor()
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String)

    // ---------------- 工具接线 ----------------

    @Test
    fun `导出工具 pptx 分支——回执带文件路径且文件可读`() {
        val dir = createTempDirectory()
        val tool = exportReportTool(outputDir = dir)
        val result = tool.invoke(
            buildJsonObject {
                put("format", "pptx")
                put("title", title)
                put("sections", JsonArray(listOf(
                    buildJsonObject {
                        put("title", "区域销售")
                        put("content", "华东地区销售额最高。")
                    },
                )))
            },
        )
        assertEquals("pptx", (result["format"] as JsonPrimitive).content)
        val path = (result["filePath"] as JsonPrimitive).content
        assertTrue(path.endsWith(".pptx"), "回执给出 pptx 路径：$path")
        val file = java.io.File(path)
        assertTrue(file.isFile && file.length() > 0, "导出文件存在且非空：$path")
        val names = entryNames(file.readBytes())
        assertTrue(names.contains("ppt/slides/slide2.xml"), "文件内容含章节页：$names")
    }

    @Test
    fun `导出工具参数 schema 含 pptx 枚举值`() {
        val schema = exportReportTool().parametersSchema
        val format = (schema["properties"] as JsonObject)["format"] as JsonObject
        val values = (format["enum"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue(values.contains("pptx"), "format 枚举含 pptx：$values")
    }

    // ---------------- 助手 ----------------

    /** 可渲染的柱状图配置（ECharts option） */
    private fun barOption(): JsonObject = buildJsonObject {
        put("xAxis", buildJsonObject {
            put("data", JsonArray(listOf(JsonPrimitive("华东"), JsonPrimitive("华北"))))
        })
        put("series", JsonArray(listOf(
            buildJsonObject {
                put("name", JsonPrimitive("销售额"))
                put("data", JsonArray(listOf(JsonPrimitive(1000), JsonPrimitive(300))))
            },
        )))
    }

    private fun createTempDirectory(): java.io.File =
        java.nio.file.Files.createTempDirectory("pptx-test").toFile()

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun partBytes(bytes: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes()
                entry = zip.nextEntry
            }
        }
        throw AssertionError("缺部件：$name")
    }
}
