package com.stragent.data

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * ZIP 解压总量预算（D17 §8.3 / T-059）。
 *
 * xlsx / ods / docx 都是「ZIP + XML」：压缩比可以做到 1000:1，只看压缩包大小
 * 挡不住 zip bomb（几百 KB 解出几个 GB）。所有 entry 的读取必须经过同一个预算，
 * 累计解压字节超过 [maxBytes] 立即抛 [IOException]——调用方按导入失败处理，
 * 而不是等 OOM 或解析超时。
 *
 * 预算按「一次解析」创建（见 `parseXlsx` / `parseOds` / `parseDocxTables`），
 * 不跨请求复用；上限可注入是为了测试能用一个很小的值触发。
 */
internal class ZipBudget(private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private var used = 0L

    /** 包装一个 entry 的输入流；读取/跳过都计入同一预算。 */
    fun open(input: InputStream): InputStream = object : FilterInputStream(input) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read.toLong())
            return read
        }

        override fun skip(count: Long): Long {
            val skipped = super.skip(count)
            if (skipped > 0) account(skipped)
            return skipped
        }

        private fun account(bytes: Long) {
            used += bytes
            if (used > maxBytes) {
                // 上限可能被测试调小到 1MB 以下，此时按字节显示，避免出现「(0MB)」
                val limit = if (maxBytes >= 1024 * 1024) "${maxBytes / (1024 * 1024)}MB" else "$maxBytes 字节"
                throw IOException("解压后内容超过上限（$limit），疑似压缩炸弹")
            }
        }
    }

    companion object {
        /** 单个文档解压后的总上限：正常报表远小于此值。 */
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024
    }
}
