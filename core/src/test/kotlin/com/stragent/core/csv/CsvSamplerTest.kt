package com.stragent.core.csv

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 大文件确定性抽样（T-054）的行为测试。
 *
 * 钉住的契约：
 *  ① 小文件不抽样（step=1、全行参与），大文件按固定步长抽样且**可复现**；
 *  ② 指纹 = 整份文件字节的 SHA-256（不是解析结果的哈希）；
 *  ③ 样本行号 → 源文件数据行号的映射逐条正确；
 *  ④ 引号内换行、CRLF、空逻辑行与 Csv 解析口径一致；
 *  ⑤ 百万行文件不整读进堆（测试只断言结果，不测内存峰值——那是 bench 的事）。
 */
class CsvSamplerTest {

    private fun tempFile(name: String, content: String): File =
        kotlin.io.path.createTempFile("stragent-sampler-$name").toFile().apply { writeText(content) }

    private fun sha256(file: File): String =
        "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `小文件不抽样——全行参与且指纹与文件字节一致`() {
        val file = tempFile("small", "地区,销售额\n华东,1000\n华北,300\n")
        val sample = CsvSampler.sampleFile(file)
        assertFalse(sample.plan.sampled, "行数未超目标时不应标成抽样")
        assertEquals(2, sample.plan.totalRows)
        assertEquals(listOf(1, 2), sample.plan.rowNumbers)
        assertEquals(listOf("华东", "华北"), sample.rows.map { it[0] })
        assertEquals(sha256(file), sample.plan.fingerprint)
    }

    @Test
    fun `百万行系统抽样——步长、行数与行号映射可复现`() {
        val file = kotlin.io.path.createTempFile("stragent-sampler-1m").toFile()
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("id,region,amount\n")
            for (i in 1..1_000_000) {
                out.write("$i,")
                out.write(if (i % 3 == 0) "华东" else "华北")
                out.write(",")
                out.write((i * 7 % 100_000).toString())
                out.write("\n")
            }
        }
        val first = CsvSampler.sampleFile(file, targetRows = 10_000)
        assertEquals(1_000_000, first.plan.totalRows)
        assertEquals(100, first.plan.step, "步长 = ceil(100 万 / 1 万) = 100")
        assertEquals(10_000, first.rows.size)
        assertEquals(1, first.plan.rowNumbers.first())
        assertEquals(101, first.plan.rowNumbers[1])
        assertEquals(999_901, first.plan.rowNumbers.last())
        assertEquals("1", first.rows.first()[0])
        assertEquals("101", first.rows[1][0])
        assertEquals("999901", first.rows.last()[0])
        assertEquals(sha256(file), first.plan.fingerprint)
        assertTrue(first.plan.sampled)

        // 同一文件再抽一次：指纹、行号、取值逐条一致（确定性重放）
        val second = CsvSampler.sampleFile(file, targetRows = 10_000)
        assertEquals(first.plan, second.plan)
        assertEquals(first.rows, second.rows)
        file.delete()
    }

    @Test
    fun `引号内换行不切断逻辑行`() {
        val file = tempFile("quoted", "名称,备注\n\"A\nB\",1\nC,2\n")
        val sample = CsvSampler.sampleFile(file)
        assertEquals(2, sample.plan.totalRows, "引号内换行不应多算一行")
        assertEquals("A\nB", sample.rows[0][0])
        assertEquals(listOf(1, 2), sample.plan.rowNumbers)
    }

    @Test
    fun `CRLF 与空逻辑行——行号仍按有效数据行计`() {
        val file = tempFile("crlf", "a,b\r\n\r\n1,2\r\n3,4\r\n")
        val sample = CsvSampler.sampleFile(file)
        assertEquals(2, sample.plan.totalRows, "空行丢弃")
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), sample.rows)
        assertEquals(listOf(1, 2), sample.plan.rowNumbers)
    }

    @Test
    fun `目标一行时取首个数据行并如实标出步长`() {
        val file = tempFile("one", "a\n1\n2\n3\n")
        val sample = CsvSampler.sampleFile(file, targetRows = 1)
        assertEquals(3, sample.plan.step)
        assertEquals(listOf(1), sample.plan.rowNumbers)
        assertEquals(listOf("1"), sample.rows.map { it[0] })
    }

    @Test
    fun `空文件与只有表头都明确拒绝`() {
        assertFailsWith<IllegalArgumentException> {
            CsvSampler.sampleFile(tempFile("empty", ""))
        }
        assertFailsWith<IllegalArgumentException> {
            CsvSampler.sampleFile(tempFile("header", "a,b\n"))
        }
    }

    @Test
    fun `GB18030 编码的 CSV——按探测结果解码，指纹仍按原始字节`() {
        val text = "地区,销售额\n华东,1000\n华北,300\n"
        val file = kotlin.io.path.createTempFile("stragent-sampler-gbk").toFile()
        file.writeBytes(text.toByteArray(java.nio.charset.Charset.forName("GB18030")))

        val detected = com.stragent.core.io.TextEncoding.detect(file)
        assertEquals("GB18030", detected.name)
        val sample = CsvSampler.sampleFile(file, charset = detected.charset)
        assertEquals(listOf("地区", "销售额"), sample.columns)
        assertEquals(listOf("华东", "华北"), sample.rows.map { it[0] })
        assertEquals(sha256(file), sample.plan.fingerprint, "指纹是原始字节的摘要，与解码方式无关")
    }
}
