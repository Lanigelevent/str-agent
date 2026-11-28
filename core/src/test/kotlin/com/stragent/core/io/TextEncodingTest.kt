package com.stragent.core.io

import java.io.File
import java.nio.charset.Charset
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 编码探测（T-058）的行为测试。
 *
 * 钉住的契约：
 *  ① BOM 优先，且回包的 reason 说得清依据；
 *  ② 无 BOM 的 UTF-8 / GB18030 能区分（GBK 中文按 GB18030 读）；
 *  ③ 两者都不合法时落到 ISO-8859-1，并明确警告可能乱码；
 *  ④ 探测只读前 1MB，不把大文件整读进堆（行为上只看结果与判定理由）。
 */
class TextEncodingTest {

    private fun tempFile(name: String, bytes: ByteArray): File =
        createTempFile("stragent-encoding-$name").toFile().apply { writeBytes(bytes) }

    @Test
    fun `无 BOM 的 UTF-8 中文——判为 UTF-8`() {
        val file = tempFile("utf8", "姓名,城市\n张三,上海\n".toByteArray(Charsets.UTF_8))
        val detected = TextEncoding.detect(file)
        assertEquals("UTF-8", detected.name)
        assertEquals(0, detected.bomBytes)
        assertTrue(detected.reason.contains("合法 UTF-8"), detected.reason)
    }

    @Test
    fun `UTF-8 BOM——判为 UTF-8 且记录 BOM 字节数`() {
        val payload = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "a,b\n1,2\n".toByteArray(Charsets.UTF_8)
        val detected = TextEncoding.detect(tempFile("utf8bom", payload))
        assertEquals("UTF-8", detected.name)
        assertEquals(3, detected.bomBytes)
        assertTrue(detected.reason.contains("UTF-8 BOM"), detected.reason)
    }

    @Test
    fun `GBK 中文——不是合法 UTF-8，判为 GB18030`() {
        val text = "姓名,城市\n张三,上海\n"
        val file = tempFile("gbk", text.toByteArray(Charset.forName("GB18030")))
        val detected = TextEncoding.detect(file)
        assertEquals("GB18030", detected.name)
        assertTrue(detected.reason.contains("不是合法 UTF-8"), detected.reason)
        assertEquals(text, String(file.readBytes(), detected.charset))
    }

    @Test
    fun `UTF-16LE BOM——判为 UTF-16LE 且回原文本`() {
        val text = "name,value\n甲,1\n"
        val payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val file = tempFile("utf16le", payload)
        val detected = TextEncoding.detect(file)
        assertEquals("UTF-16LE", detected.name)
        assertEquals(2, detected.bomBytes)
        val decoded = TextEncoding.reader(file, detected).use { it.readText() }
        assertEquals(text, decoded, "读取器应吞掉 BOM，正文从第一列开始")
    }

    @Test
    fun `既非 UTF-8 也非 GB18030——回落 ISO-8859-1 并警告乱码`() {
        // 0xFF 不是合法 UTF-8 起始字节，也不是合法 GB18030 起始字节；且不以 FF FE 开头（否则是 UTF-16LE BOM）
        val file = tempFile("binary", byteArrayOf(0xFF.toByte(), 0x41, 0x42))
        val detected = TextEncoding.detect(file)
        assertEquals("ISO-8859-1", detected.name)
        assertTrue(detected.reason.contains("乱码"), detected.reason)
    }

    @Test
    fun `文件不存在——可解释错误`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "no-such-encoding-${System.nanoTime()}")
        val error = assertFailsWith<IllegalArgumentException> { TextEncoding.detect(missing) }
        assertTrue(error.message!!.contains("文件不存在"), error.message)
    }
}
