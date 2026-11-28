package com.stragent.core.csv

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * 大文件确定性系统抽样（T-054）—— 让百万行文件也能出**可回链**的报告。
 *
 * 为什么不是随机抽样：报告里的每个数字都要能回到明细行、也要能在下一次
 * 导入时复现。随机抽样需要随机源，随机源就是「口径漂移」的入口；系统抽样
 * （固定步长、固定起点）只依赖文件内容，同一份文件任何时候抽到的行完全一致。
 *
 * 口径（写进工具回包与报告摘要，不静默）：
 *  - 步长 step = ceil(总数据行 / 目标行数)，起点是第 1 个数据行；
 *  - 被选中的是源文件里第 1、1+step、1+2·step… 个数据行（不含表头）；
 *  - 指纹 = 整份文件字节的 SHA-256——同一文件重复导入/重启后指纹不变，
 *    文件改一个字节指纹就变，旧报告自然对不上号；
 *  - 行号映射随数据集保存：样本第 N 行 ↔ 源文件数据行 rowNumbers[N-1]。
 *
 * 实现：流式两遍扫描（第一遍数行 + 算指纹，第二遍收集被选中的逻辑行），
 * 内存只放目标行数（默认 1 万行），不会把百万行整读进堆。逻辑行切分与
 * [Csv] 同口径（引号内换行不切断、CR/LF/CRLF、空逻辑行丢弃）。
 *
 * 诚实边界：
 *  - 抽样只降**规模**，不修正**偏差**——若文件按某个维度有序排列（如按地区
 *    排序），系统抽样仍可能系统性偏移；报告只允许引用样本上的统计，并在摘要里
 *    标明「样本口径」。
 *  - 指纹证明「是不是同一份文件」，不证明「数据本身可信」。
 */
object CsvSampler {

    /** 单文件上限：两遍扫描 + 抽样只占常数内存，上限取「读得完」而非「放得下」 */
    const val MAX_FILE_BYTES: Long = 256L * 1024 * 1024

    /** 抽样计划（报告口径、明细回链与持久化都靠这份映射） */
    data class Plan(
        /** `sha256:<hex>`，整份文件字节的摘要 */
        val fingerprint: String,
        val sourceBytes: Long,
        val totalRows: Int,
        val targetRows: Int,
        val step: Int,
        /** 样本第 N 行（1 起）↔ 源文件数据行（1 起，不含表头） */
        val rowNumbers: List<Int>,
    ) {
        /** 是否真的抽了（源文件行数 > 样本行数） */
        val sampled: Boolean get() = rowNumbers.size < totalRows
    }

    data class Sample(
        val columns: List<String>,
        val columnTypes: List<ColumnType>,
        val delimiter: Char,
        val rows: List<List<String>>,
        val plan: Plan,
    )

    /**
     * 从文件抽样。
     *
     * @param targetRows 目标样本行数（默认与 [Csv.MAX_ROWS] 对齐）
     * @param charset 解码字符集；缺省 UTF-8。T-058 起调用方先做编码探测再传入，
     *   指纹仍按**原始字节**计算，与解码方式无关。
     * @throws IllegalArgumentException 文件为空、超过 [MAX_FILE_BYTES]、没有数据行
     */
    fun sampleFile(
        file: File,
        targetRows: Int = Csv.MAX_ROWS,
        charset: java.nio.charset.Charset = Charsets.UTF_8,
    ): Sample {
        require(targetRows > 0) { "targetRows 必须为正数" }
        if (!file.isFile) throw IllegalArgumentException("文件不存在：${file.path}")
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalArgumentException(
                "文件过大（${file.length() / 1024 / 1024}MB，上限 ${MAX_FILE_BYTES / 1024 / 1024}MB）：" +
                    "请先拆分文件或导出为更小的批次",
            )
        }

        // 第一遍：逻辑行计数 + 整文件指纹 + 表头
        val digest = MessageDigest.getInstance("SHA-256")
        var totalRows = -1 // 表头占第 0 个非空逻辑行
        var header: String? = null
        scan(file, digest, charset) { index, line ->
            if (index == 0) header = line else totalRows = index
        }
        val headerLine = header ?: throw IllegalArgumentException("文件为空：没有可解析的表头行")
        if (totalRows <= 0) throw IllegalArgumentException("文件只有表头、没有数据行，无法抽样分析")

        val step = ((totalRows + targetRows - 1) / targetRows).coerceAtLeast(1)
        val pickedLines = ArrayList<String>(minOf(totalRows, targetRows))
        val rowNumbers = ArrayList<Int>(minOf(totalRows, targetRows))

        // 第二遍：按固定步长收集逻辑行（0 起的数据行序号）
        scan(file, null, charset) { index, line ->
            if (index > 0) {
                val dataIndex = index - 1 // 0 起
                if (dataIndex % step == 0) {
                    pickedLines.add(line)
                    rowNumbers.add(dataIndex + 1)
                }
            }
        }

        val sampledText = (listOf(headerLine) + pickedLines).joinToString("\n")
        // 样本行数由 targetRows 控制，解析用不截断的入口——真截断会让行号映射错位
        val parsed = Csv.parseDelimitedFull(sampledText)
        if (parsed.rows.size != rowNumbers.size) {
            // 不静默：口径对不上时宁可失败，也不能把错位的行号交出去
            throw IllegalStateException(
                "抽样行数与解析结果不一致（收集 ${rowNumbers.size} 行，解析出 ${parsed.rows.size} 行）：" +
                    "文件可能在读取过程中被修改，请重试",
            )
        }

        return Sample(
            columns = parsed.columns,
            columnTypes = parsed.columnTypes,
            delimiter = parsed.delimiter,
            rows = parsed.rows,
            plan = Plan(
                fingerprint = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) },
                sourceBytes = file.length(),
                totalRows = totalRows,
                targetRows = targetRows,
                step = step,
                rowNumbers = rowNumbers,
            ),
        )
    }

    /**
     * 流式扫描：逐条非空逻辑行回调 `(逻辑行序号, 行文本)`，0 = 表头。
     * [digest] 非 null 时同时把**原始字节**喂给它（指纹按文件字节算，不按解析结果算）。
     */
    private fun scan(
        file: File,
        digest: MessageDigest?,
        charset: java.nio.charset.Charset,
        onLine: (Int, String) -> Unit,
    ) {
        val raw = BufferedInputStream(file.inputStream(), 64 * 1024)
        val counted = if (digest == null) raw else DigestInputStream(raw, digest)
        val reader = InputStreamReader(counted, charset)

        val current = StringBuilder()
        var inQuotes = false
        var pendingQuote = false // 引号内遇到 "：等下一个字符判断是不是 "" 转义
        var skipLf = false // CRLF：CR 已经算过换行，紧跟的 LF 要吞掉
        var lineIndex = 0

        fun endLine() {
            if (current.isNotBlank()) {
                onLine(lineIndex, current.toString())
                lineIndex += 1
            }
            current.setLength(0)
        }

        fun outside(ch: Char) {
            when {
                ch == '"' && Csv.isFieldStartForQuote(current) -> {
                    inQuotes = true
                    current.append(ch)
                }
                ch == '\r' -> {
                    endLine()
                    skipLf = true
                }
                ch == '\n' -> endLine()
                else -> current.append(ch)
            }
        }

        val buffer = CharArray(64 * 1024)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                val ch = buffer[i]
                if (skipLf) {
                    // CRLF：CR 已经结束逻辑行，紧跟的 LF 直接吞掉；紧跟着别的字符则恢复正常处理
                    skipLf = false
                    if (ch == '\n') continue
                }
                if (pendingQuote) {
                    if (ch == '"') {
                        // "" 转义：原样保留两个引号，交给 splitLine 的同一套规则
                        current.append('"').append('"')
                        pendingQuote = false
                    } else {
                        // 收尾引号要原样保留（丢掉会让下一段解析把后面的换行当成引号内内容）
                        current.append('"')
                        inQuotes = false
                        pendingQuote = false
                        outside(ch)
                    }
                    continue
                }
                if (inQuotes) {
                    if (ch == '"') pendingQuote = true else current.append(ch)
                    continue
                }
                outside(ch)
            }
        }
        if (pendingQuote) current.append('"') // 文件末尾落单的引号：与 splitLogicalLines 同款收尾
        if (current.isNotBlank()) {
            onLine(lineIndex, current.toString())
        }
        reader.close()
    }
}
