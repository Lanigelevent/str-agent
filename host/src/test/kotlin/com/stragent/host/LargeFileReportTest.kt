package com.stragent.host

import com.stragent.data.abilityTools
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-054 判据 ②：**100 万行可出报告，且报告里的数字可回链到明细**。
 *
 * 这条测试证明的是「用户能感知的那件事」而不是内部实现：
 *  ① 百万行 CSV 经 `stragent_import_file` 进入内存的是确定性样本（1 万行），
 *     回执如实给出「源文件多少行 / 分析了多少行 / 每几行取一行 / 文件指纹」；
 *  ② `stragent_list_rows` 的每一行都带 `sourceRow`，且该行号**真的指向源文件那一行**
 *     （本测试用「第 N 行销售额 = N−1」的构造，回链错一位立刻对不上）；
 *  ③ 报告回执带 `sampling` 块，生成提示词里明确写着「样本口径 + 指纹 + 行号换算」——
 *     模型知道自己在写样本口径，读者也能凭指纹确认「看的是哪份文件」。
 *
 * 为什么不在测试里把 100 万行都塞进断言：百万行的价值在**抽样与回链的接线**，
 * 不在逐行比对；样本第 1/2/3 行覆盖起点、步长、跨距三种情形，错位类缺陷都会被抓住。
 */
class LargeFileReportTest {

    private fun tempHome(): File =
        File(System.getProperty("java.io.tmpdir"), "str-agent-t054-${System.nanoTime()}").also { it.mkdirs() }

    /** 构造百万行 CSV：销售额 = 数据行号 − 1（第 1 个数据行是 0），地区每 7 行轮换 */
    private fun millionRowCsv(fileName: String, rows: Int): File {
        val file = File(tempHome(), fileName)
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("地区,销售额\n")
            for (i in 0 until rows) writer.write("G${i % 7},$i\n")
        }
        return file
    }

    private fun tool(bootstrap: StrAgentHostBootstrap, name: String) =
        (abilityTools(bootstrap.abilityDatasets) +
            pluginTools(bootstrap.plugin, bootstrap.abilityDatasets, bootstrap.memory, bootstrap.semantic) +
            importFileTool(bootstrap.abilityDatasets, bootstrap.sessionRegistry))
            .first { it.name == name }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private fun JsonObject.int(key: String): Int = str(key).toInt()

    @Test
    fun `百万行 CSV——抽样导入、明细回链、报告带样本口径与指纹`() {
        val file = millionRowCsv("million.csv", rows = 1_000_000)
        val llm = FakeLlmClient()
        val bootstrap = StrAgentHostBootstrap.create(tempHome(), llm = llm)
        try {
            // ① 导入：内存里只放样本，但「源文件多大」如实回报
            val imported = tool(bootstrap, "stragent_import_file")
                .invoke(buildJsonObject { put("filePath", file.absolutePath) })
            val datasetId = imported.str("datasetId")
            assertEquals(1_000_000, imported.int("rows"), "rows 是源文件口径：$imported")
            assertEquals(10_000, imported.int("analyzedRows"), "内存里只该有 1 万行样本：$imported")
            assertEquals(true, imported.str("truncated").toBoolean(), "抽样即截断，必须显式标记")
            val importSampling = imported["sampling"]!!.jsonObject
            assertEquals("systematic", importSampling.str("method"))
            assertEquals(100, importSampling.int("step"), "100 万 ÷ 1 万 = 每 100 行取 1 行")
            assertEquals(10_000, importSampling.int("sampleRows"))
            assertEquals(1_000_000, importSampling.int("totalRows"))
            val fingerprint = importSampling.str("fingerprint")
            assertEquals(71, fingerprint.length, "指纹应为 sha256: + 64 位十六进制：$fingerprint")
            assertTrue(fingerprint.startsWith("sha256:"), "指纹需带算法前缀（换算法时可识别）：$fingerprint")
            assertTrue(
                importSampling.str("notice").contains("确定性系统抽样"),
                "抽样口径必须显式告知，实际：${importSampling.str("notice")}",
            )

            // ② 明细回链：sourceRow = (row − 1) × 100 + 1，且单元格内容与源文件对得上
            val listed = tool(bootstrap, "stragent_list_rows").invoke(
                buildJsonObject {
                    put("datasetId", datasetId)
                    put("offset", 0)
                    put("limit", 3)
                },
            )
            val items = listed["items"]!!.jsonArray
            assertEquals(3, items.size)
            val first = items[0].jsonObject
            assertEquals(1, first.int("row"))
            assertEquals(1, first.int("sourceRow"), "样本第 1 行对应源文件第 1 个数据行")
            val second = items[1].jsonObject
            assertEquals(2, second.int("row"))
            assertEquals(101, second.int("sourceRow"), "样本第 2 行对应源文件第 101 个数据行")
            // 构造值是「数据行号 − 1」：源文件第 101 个数据行的销售额应为 100
            assertEquals("100", second["cells"]!!.jsonArray[1].jsonPrimitive.content)
            assertEquals(201, items[2].jsonObject.int("sourceRow"))
            assertEquals(
                fingerprint,
                listed["sampling"]!!.jsonObject.str("fingerprint"),
                "明细与导入必须是同一份抽样（同一指纹）",
            )

            // ③ 报告：回执带 sampling，提示词明确「样本口径 + 指纹 + 行号换算」
            val report = tool(bootstrap, "stragent_generate_report")
                .invoke(buildJsonObject { put("datasetId", datasetId) })
            val reportSampling = report["sampling"]!!.jsonObject
            assertEquals(100, reportSampling.int("step"))
            assertEquals(1_000_000, reportSampling.int("totalRows"))
            assertEquals(fingerprint, reportSampling.str("fingerprint"))
            val prompt = llm.prompts.first()
            assertTrue(prompt.contains("确定性系统抽样"), "报告提示词必须写明样本口径")
            assertTrue(prompt.contains(fingerprint), "报告提示词必须带文件指纹（可复核看的是哪份文件）")
            assertTrue(prompt.contains("行号换算"), "报告提示词必须给出样本行 → 源文件行的换算规则")
        } finally {
            bootstrap.close()
            file.parentFile?.deleteRecursively()
        }
    }
}
