package com.stragent.core.io

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码探测（T-058）—— 让「这份文件按什么编码读的」成为可解释的结论，而不是默认 UTF-8 撞运气。
 *
 * 判定顺序（每一步都写进 [EncodingDetection.reason]，随导入回包给用户）：
 *  1. 文件头 BOM：UTF-8（EF BB BF）/ UTF-16LE（FF FE）/ UTF-16BE（FE FF），BOM 不参与解析；
 *  2. 无 BOM 时取前 1MB 做**严格**解码探测：合法 UTF-8 → UTF-8；
 *  3. 不合法 UTF-8 但合法 GB18030（兼容 GBK）→ GB18030；
 *  4. 两者都不合法 → ISO-8859-1 逐字节解码，并明说「可能出现乱码」。
 *
 * 诚实边界：
 *  - 只对**新接入的文本源**（JSONL）启用；CSV/TSV/TXT 仍按既有 UTF-8 口径读取（T-054 的指纹与金标不动）。
 *  - 无 BOM 的 UTF-16 不识别（探针里会出现大量 0x00，按 UTF-8/GB18030 都会判失败 → 落到 ISO-8859-1）；
 *    遇到时回包里的 `encoding.name` 与 `replacementChars` 会让用户看见异常，而不是静默。
 *  - 探测只证明「前 1MB 像什么编码」，不证明「后面没有混入别的编码」；调用方要把
 *    解码过程中出现的替换字符计数一并回报（见 `JsonlSampler`）。
 */
data class EncodingDetection(
    /** 展示名：`UTF-8` / `UTF-16LE` / `UTF-16BE` / `GB18030` / `ISO-8859-1` */
    val name: String,
    val charset: Charset,
    /** 判定依据（一句话给用户看） */
    val reason: String,
    /** BOM 占用的字节数（0 = 无 BOM）；解码后首字符若是 U+FEFF 需要剥掉 */
    val bomBytes: Int = 0,
)

object TextEncoding {

    /** 无 BOM 时的探测窗口：1MB 足够让 UTF-8 / GB18030 的差异暴露出来 */
    const val PROBE_BYTES: Int = 1 shl 20

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * 探测文件编码。
     *
     * @throws IllegalArgumentException 文件不存在或读不出来
     */
    fun detect(file: File): EncodingDetection {
        if (!file.isFile) throw IllegalArgumentException("文件不存在：${file.path}")
        val probe = try {
            file.inputStream().use { readUpTo(it, PROBE_BYTES) }
        } catch (e: Exception) {
            throw IllegalArgumentException("读取文件失败（${file.name}）：${e.message ?: e.javaClass.simpleName}")
        }
        return detect(probe, file.length())
    }

    /** 对一段字节做探测（[totalBytes] 用于判断探针是否覆盖整份内容）。 */
    fun detect(probe: ByteArray, totalBytes: Long): EncodingDetection {
        if (startsWith(probe, UTF8_BOM)) {
            return EncodingDetection(
                name = "UTF-8",
                charset = Charsets.UTF_8,
                reason = "文件头有 UTF-8 BOM（EF BB BF），按 UTF-8 解码并去掉 BOM",
                bomBytes = 3,
            )
        }
        if (startsWith(probe, UTF16LE_BOM)) {
            return EncodingDetection(
                name = "UTF-16LE",
                charset = Charsets.UTF_16LE,
                reason = "文件头有 UTF-16LE BOM（FF FE），按 UTF-16LE 解码并去掉 BOM",
                bomBytes = 2,
            )
        }
        if (startsWith(probe, UTF16BE_BOM)) {
            return EncodingDetection(
                name = "UTF-16BE",
                charset = Charsets.UTF_16BE,
                reason = "文件头有 UTF-16BE BOM（FE FF），按 UTF-16BE 解码并去掉 BOM",
                bomBytes = 2,
            )
        }
        if (probe.isEmpty()) {
            return EncodingDetection("UTF-8", Charsets.UTF_8, "空文件，默认按 UTF-8 处理")
        }
        val complete = probe.size.toLong() >= totalBytes
        val scope = if (complete) "整份文件（${probe.size} 字节）" else "前 ${probe.size} 字节"
        if (isValid(probe, Charsets.UTF_8, complete)) {
            return EncodingDetection("UTF-8", Charsets.UTF_8, "无 BOM；$scope 是合法 UTF-8")
        }
        val gb18030 = runCatching { Charset.forName("GB18030") }.getOrNull()
        if (gb18030 != null && isValid(probe, gb18030, complete)) {
            return EncodingDetection(
                name = "GB18030",
                charset = gb18030,
                reason = "无 BOM；$scope 不是合法 UTF-8，但符合 GB18030（兼容 GBK）",
            )
        }
        return EncodingDetection(
            name = "ISO-8859-1",
            charset = Charsets.ISO_8859_1,
            reason = "无 BOM；$scope 既不是合法 UTF-8 也不是合法 GB18030，按 ISO-8859-1 逐字节解码（可能出现乱码）",
        )
    }

    /** 按探测结果打开文本读取器；BOM 不进入字符流（首行读出来不含 U+FEFF）。 */
    fun reader(file: File, detection: EncodingDetection): java.io.BufferedReader {
        val reader = file.inputStream().bufferedReader(detection.charset)
        if (detection.bomBytes > 0) {
            // UTF-8/UTF-16LE 的 BOM 在 JDK 解码后是 U+FEFF 字符：先看一个字符，不是 BOM 就 reset
            reader.mark(1)
            val first = reader.read()
            if (first != -1 && first != '\uFEFF'.code) reader.reset()
        }
        return reader
    }

    private fun isValid(bytes: ByteArray, charset: Charset, complete: Boolean): Boolean {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(maxOf(16, bytes.size + 1))
        return try {
            val result = decoder.decode(input, output, complete)
            if (result.isError) return false
            // 探针截断处允许落单的多字节序列（例如 1MB 边界切在一个汉字中间）
            if (!complete) true else !decoder.flush(output).isError
        } catch (e: CharacterCodingException) {
            false
        }
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun readUpTo(stream: java.io.InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = stream.read(buffer, read, limit - read)
            if (n < 0) break
            read += n
        }
        return if (read == limit) buffer else buffer.copyOf(read)
    }
}
