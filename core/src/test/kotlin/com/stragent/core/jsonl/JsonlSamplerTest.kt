package com.stragent.core.jsonl

import com.stragent.core.csv.ColumnType
import java.io.File
import java.nio.charset.Charset
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JSONL 导入（T-058）的行为测试。
 *
 * 钉住的契约：
 *  ① 字段并集来自**全量扫描**：抽样范围外的字段也要进 schema；
 *  ② 列类型 = 全部非空值都可解析为数字才是 number（不是只看样本）；
 *  ③ 对象/数组按紧凑 JSON 文本入表并标 nested；
 *  ④ 抽样行号 = 第 N 个非空 JSON 行（空白行不计），同一文件可复现；
 *  ⑤ 坏行 / 非对象行 / 空对象文件都给出可解释错误（带行号）。
 */
class JsonlSamplerTest {

    private fun tempFile(name: String, text: String, charset: Charset = Charsets.UTF_8): File =
        createTempFile("stragent-jsonl-$name").toFile().apply { writeBytes(text.toByteArray(charset)) }

    @Test
    fun `字段并集与类型——全量扫描，不只看样本`() {
        val lines = buildList {
            for (i in 1..9) add("""{"id":$i,"name":"n$i","score":${i * 1.5}}""")
            // 第 10 行才出现的字段与破坏数值性的值：目标样本 2 行（step=5）抽不到它
            add("""{"id":10,"name":"n10","score":"n/a","late":"x"}""")
        }
        val sample = JsonlSampler.sampleFile(tempFile("union", lines.joinToString("\n")), targetRows = 2)

        assertEquals(10, sample.plan.totalRows)
        assertEquals(5, sample.plan.step)
        assertEquals(listOf(1, 6), sample.plan.rowNumbers, "系统抽样：第 1、6 个非空行")
        assertEquals(listOf("id", "name", "score", "late"), sample.columns, "字段并集按首次出现排序")
        assertEquals(ColumnType.NUMBER, sample.columnTypes[0], "id 全量都是数字")
        assertEquals(ColumnType.TEXT, sample.columnTypes[2], "score 有一行 n/a，全量口径应判 text")
        assertEquals(ColumnType.TEXT, sample.columnTypes[3], "late 只出现在未被抽中的行里")
        assertTrue(sample.fields.single { it.name == "late" }.numeric.not(), "late 不是数值字段")
    }

    @Test
    fun `对象与数组——紧凑 JSON 入表并标 nested，空值与缺字段都写空串`() {
        val text = """
            {"id":1,"meta":{"k":1,"s":"值"},"tags":[1,2],"note":null}
            {"id":2,"meta":{"k":2},"tags":[]}
        """.trimIndent()
        val sample = JsonlSampler.sampleFile(tempFile("nested", text))
        assertEquals(listOf("id", "meta", "tags", "note"), sample.columns)
        assertEquals("""{"k":1,"s":"值"}""", sample.rows[0][1])
        assertEquals("[1,2]", sample.rows[0][2])
        assertEquals("", sample.rows[0][3], "null 写空串")
        assertEquals("", sample.rows[1][3], "缺字段写空串")
        assertEquals(setOf("meta", "tags"), sample.fields.filter { it.nested }.map { it.name }.toSet())
    }

    @Test
    fun `空白行不计行号——行号映射指向第 N 个非空 JSON 行`() {
        val text = "\n" + """{"v":1}""" + "\n\n" + """{"v":2}""" + "\n   \n" + """{"v":3}""" + "\n"
        val sample = JsonlSampler.sampleFile(tempFile("blank", text), targetRows = 2)
        assertEquals(3, sample.plan.totalRows)
        assertEquals(3, sample.plan.blankLines)
        assertEquals(listOf(1, 3), sample.plan.rowNumbers)
        assertEquals(listOf("1", "3"), sample.rows.map { it[0] })
    }

    @Test
    fun `同一文件两次抽样——指纹与样本完全一致`() {
        val text = (1..50).joinToString("\n") { """{"id":$it,"v":${it % 7}}""" }
        val file = tempFile("stable", text)
        val first = JsonlSampler.sampleFile(file, targetRows = 7)
        val second = JsonlSampler.sampleFile(file, targetRows = 7)
        assertEquals(first.plan.fingerprint, second.plan.fingerprint)
        assertEquals(first.plan.rowNumbers, second.plan.rowNumbers)
        assertEquals(first.rows, second.rows)
        assertTrue(first.plan.fingerprint.startsWith("sha256:"))
    }

    @Test
    fun `坏行——指出第几个非空行并带行首预览`() {
        val text = """{"ok":1}""" + "\n" + """{"ok":2}""" + "\n" + """{oops}""" + "\n"
        val error = assertFailsWith<IllegalArgumentException> {
            JsonlSampler.sampleFile(tempFile("bad", text))
        }
        assertTrue(error.message!!.contains("第 3 个非空行"), error.message)
        assertTrue(error.message!!.contains("不是合法 JSON"), error.message)
    }

    @Test
    fun `非对象行——说明 JSONL 要求每行是对象`() {
        val error = assertFailsWith<IllegalArgumentException> {
            JsonlSampler.sampleFile(tempFile("array", """[1,2,3]"""))
        }
        assertTrue(error.message!!.contains("要求每行是一个 JSON 对象"), error.message)
        assertTrue(error.message!!.contains("数组"), error.message)
    }

    @Test
    fun `全是空对象——可解释错误`() {
        val error = assertFailsWith<IllegalArgumentException> {
            JsonlSampler.sampleFile(tempFile("emptyobjects", "{}\n{}\n"))
        }
        assertTrue(error.message!!.contains("空对象"), error.message)
    }

    @Test
    fun `GB18030 编码——按探测结果解码中文值`() {
        val text = """{"姓名":"张三","城市":"上海"}""" + "\n" + """{"姓名":"李四","城市":"北京"}""" + "\n"
        val file = tempFile("gbk", text, Charset.forName("GB18030"))
        val sample = JsonlSampler.sampleFile(file)
        assertEquals("GB18030", sample.plan.encoding.name)
        assertEquals(listOf("姓名", "城市"), sample.columns)
        assertEquals("张三", sample.rows[0][0])
        assertEquals("李四", sample.rows[1][0])
        assertEquals(0, sample.plan.replacementChars)
    }

    @Test
    fun `UTF-8 BOM——字段名不带 BOM 前缀`() {
        val payload = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"id":1,"name":"甲"}""".toByteArray(Charsets.UTF_8)
        val file = createTempFile("stragent-jsonl-bom").toFile().apply { writeBytes(payload) }
        val sample = JsonlSampler.sampleFile(file)
        assertEquals("UTF-8", sample.plan.encoding.name)
        assertEquals(3, sample.plan.encoding.bomBytes)
        assertEquals(listOf("id", "name"), sample.columns)
    }
}
