package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-110 规模与多文档（业界做法：RULER 合成长文压测 + LongBench 多文档分类）：
 * ①四区段真实材料——两份同构 PDF 导入互不串扰，同构问题各自命中本档窗口；
 * ②合成 ~380K 字符（5000 段=段上限边界、~95 块=块上限边界）针句埋设——
 *   检索在规模上限处仍命中首/中/尾针句，负例不凑。
 * 回答语义质量归真实 Key 抽样；本测试钉检索与规模纪律。
 */
class PdfScaleMultiDocTest {

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )

    private fun import(b: StrAgentHostBootstrap, path: String, name: String): String =
        call(b, "stragent_import_text", buildJsonObject {
            put("filePath", path); put("name", name)
        })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content

    private fun windowOf(fake: FakeLlmClient, b: StrAgentHostBootstrap, dsId: String, q: String): String {
        fake.prompts.clear()
        call(b, "stragent_ask_followup", buildJsonObject {
            put("datasetId", dsId); put("question", q)
        })
        return fake.prompts.lastOrNull() ?: ""
    }

    @Test
    fun `四区段两份真实 PDF——同构问题各自命中且不串档`() {
        val pdf1 = RealMaterialFixture.requirePdf()
        val pdf4 = RealMaterialFixture.requirePdf(
            envName = "AB_REAL_PDF4",
            defaultPath = "C:/Users/<user>/Desktop/总结报告/四区/基坑安全监测总结报告_字处理版.pdf",
        )
        val fake = FakeLlmClient()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-multidoc-" + System.nanoTime()).also { it.mkdirs() },
            llm = fake,
        )
        try {
            val ds1 = import(b, pdf1, "一区二段")
            val ds4 = import(b, pdf4, "四区")
            val w1 = windowOf(fake, b, ds1, "立柱竖向位移的整组归零事件是什么情况？")
            val w4 = windowOf(fake, b, ds4, "报警值未经设计确认的效力问题是什么？")
            assertTrue(w1.contains("归零"), "一区二段窗口命中本档主题")
            assertTrue(w4.contains("报警值"), "四区窗口命中本档主题")
            assertTrue(w1.contains("段") && w4.contains("段"), "双档窗口均带段锚")
        } finally {
            b.close()
        }
    }

    @Test
    fun `合成 380K 大文本——段与块上限边界处针检索`() {
        val needleA = "特殊事项登记编号 NA-301：东备坑降水井在观测中期完成深度核准。"
        val needleB = "特殊事项登记编号 NB-507：西护坡位移计在观测后期更换了基准环。"
        val needleC = "特殊事项登记编号 NC-902：南施工道路沉降点群在雨季加密了观测。"
        val plain = "常规监测数据按统一口径逐日登记，本月无异常变化，各项读数均在允许范围内波动，记录完整可复核。"
        val segs = mutableListOf<String>()
        while (segs.size < 5000) {
            when (segs.size) {
                100 -> segs.add(needleA)
                2500 -> segs.add(needleB)
                4900 -> segs.add(needleC)
                else -> segs.add(plain)
            }
        }
        val f = File.createTempFile("t110-scale", ".txt")
        f.writeText(segs.joinToString("\n"), Charsets.UTF_8)
        val fake = FakeLlmClient()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-scale-" + System.nanoTime()).also { it.mkdirs() },
            llm = fake,
        )
        try {
            val dsId = import(b, f.absolutePath, "合成大文本")
            for ((q, marker) in listOf(
                "NA-301 东备坑降水井的登记内容是什么？" to "NA-301",
                "NB-507 西护坡位移计的登记内容是什么？" to "NB-507",
                "NC-902 南施工道路的登记内容是什么？" to "NC-902",
            )) {
                val w = windowOf(fake, b, dsId, q)
                assertTrue(w.contains(marker), "针句命中（首/中/尾）：$marker")
            }
            val negQ = "ND-999 北备用井的登记内容是什么？"
            val neg = windowOf(fake, b, dsId, negQ).replace(negQ, "")
            assertTrue(!neg.contains("ND-999"), "不存在的针编号不被检索层凭空带出（词法部分重叠的最近邻入窗属诚实行为，拒答语义归 corrective 层）")
        } finally {
            b.close()
        }
    }
}
