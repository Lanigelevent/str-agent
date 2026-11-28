package com.stragent.bench.external

import com.stragent.bench.model.SuiteResult
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T-186：DAB 子集 + DABench-RLM 接入 externalBench（D11 §5 评测证明欠账）。
 *
 * 口径（与 Spider 子集同构，T-057 先例）：
 *  - 题面 manifest（bench/external/dab/manifest.json）进仓；数据集不进仓，
 *    缺省**显式 SKIP**（退出 0、评分卡不含通过数，不伪造通过也不假红）；
 *  - 三维评测，**只测执行正确性，不测报告质量**：
 *      numeric —— 参考查询（SQL）在 SQLite 数据上执行 → 结果与 gold 数字/表对拍；
 *      tool-use —— 参考工具链（SQL 步骤序列）逐步执行 → 终态与 gold 对拍；
 *      rlm     —— python 片段经 safe_runner.py（stragent_compute_python 同一底层执行路径），
 *                 stdout JSON 与 gold 对拍；**无 Python 环境显式转验不可用，不静默跳过**；
 *  - 负控（判据④）：注入错误 gold 判分器必须红——由 ExternalDabTest 用合成夹具钉住；
 *  - 数据接入时只补 manifest 题目 + 数据目录，套件零改动。
 */
class ExternalDab(
    private val repoRoot: File = resolveRepoRoot(),
    private val datasetDir: File = resolveDatasetDir(resolveRepoRoot()),
    private val manifestFile: File = File(repoRoot, "bench/external/dab/manifest.json"),
    private val pythonBin: String? = detectPython(),
) {

    data class DabRun(val suite: SuiteResult, val dimensionNotes: Map<String, String>)

    fun run(): DabRun {
        val manifest = runCatching { loadManifest() }.getOrElse { error ->
            return DabRun(
                SuiteResult(
                    name = SUITE_NAME,
                    passed = 0,
                    total = 1,
                    failures = listOf("manifest 读取失败：${error.message}"),
                    notes = "manifest：${manifestFile.absolutePath}",
                ),
                emptyMap(),
            )
        }
        if (manifest.tasks.isEmpty()) {
            return skip("DAB 数据集未接入：manifest 无题（数据缺省显式 SKIP 口径，同 Spider T-057）；" +
                "接入时补 manifest 题目 + 数据目录，套件零改动")
        }
        if (!datasetDir.isDirectory) {
            return skip("DAB 数据目录 ${datasetDir.absolutePath} 不存在；设置 $DATASET_ENV 或运行 fetch 脚本")
        }

        val failures = mutableListOf<String>()
        var passed = 0
        val dims = linkedMapOf<String, MutableList<String>>()
        val started = System.nanoTime()
        for (task in manifest.tasks) {
            val outcome = when (task.dimension) {
                "numeric" -> runCatching { runNumeric(task) }
                "tool-use" -> runCatching { runToolUse(task) }
                "rlm" -> {
                    if (pythonBin == null) {
                        // 判据②：无 Python 环境显式转验不可用，不静默跳过
                        dims.getOrPut("rlm") { mutableListOf() }
                            .add("${task.id}: 无 Python 环境，转验不可用（显式登记）")
                        continue
                    }
                    runCatching { runRlm(task) }
                }
                else -> runCatching { error("未知维度「${task.dimension}」（允许 numeric/tool-use/rlm）") }
            }
            outcome.fold(
                onSuccess = { passed++ },
                onFailure = { e -> failures += "${task.id} [${task.dimension}]: ${e.message ?: e::class.simpleName}" },
            )
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val dimNotes = dims.mapValues { it.value.joinToString("; ") }
        val notes = buildString {
            append("DAB 子集：${manifest.tasks.size} 题（numeric/tool-use/rlm 三维），")
            append("只测 SQL/数字/工具执行正确性，不测报告质量；")
            append("数据目录 ${datasetDir.absolutePath}；耗时 ${elapsedMs}ms")
            if (dims.isNotEmpty()) {
                append("；rlm 转验不可用：${dims["rlm"]?.size ?: 0} 题")
            }
        }
        return DabRun(
            SuiteResult(
                name = SUITE_NAME,
                passed = passed,
                total = manifest.tasks.size,
                failures = failures,
                notes = notes,
            ),
            dimNotes,
        )
    }

    // ------------------------------------------------------------ 三维执行器

    /** numeric：SQL 在 SQLite 数据上执行 → 表与 gold 表对拍 */
    private fun runNumeric(task: DabTask) {
        val csv = requireDataFile(task)
        val sql = task.sql ?: error("numeric 题缺参考查询 sql")
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use {
            loadCsvIntoSqlite(csv, conn)
            val pred = queryOn(conn, sql)
            val gold = task.goldTable ?: error("numeric 题缺 gold 表")
            if (!GoldComparator.compareMulti(pred, listOf(gold), ConditionCols.Empty, task.ignoreOrder)) {
                error("结果不匹配（${pred.rows.size}×${pred.columns.size}，gold ${gold.rows.size}×${gold.columns.size}）")
            }
        }
    }

    /** tool-use：SQL 步骤序列逐步执行（每步可建临时表/聚合）→ 终态 SELECT 与 gold 对拍 */
    private fun runToolUse(task: DabTask) {
        val csv = requireDataFile(task)
        val tableName = "src"
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use {
            loadCsvIntoSqlite(csv, conn, tableName)
            val steps = task.steps ?: error("tool-use 题缺参考工具链 steps")
            for (step in steps.dropLast(1)) {
                conn.createStatement().use { it.execute(step) }
            }
            val final = steps.last()
            val pred = queryOn(conn, final)
            val gold = task.goldTable ?: error("tool-use 题缺 gold 表")
            if (!GoldComparator.compareMulti(pred, listOf(gold), ConditionCols.Empty, task.ignoreOrder)) {
                error("终态不匹配（${pred.rows.size}×${pred.columns.size}）")
            }
        }
    }

    /** rlm：python 片段经 classpath safe_runner.py（stragent_compute_python 同一底层执行路径）执行 → result 与 gold 对拍 */
    private fun runRlm(task: DabTask) {
        val python = pythonBin ?: error("无 Python 环境，转验不可用（显式登记）")
        val code = task.code ?: error("rlm 题缺 python 片段 code")
        val csv = requireDataFile(task)
        val rows = readCsv(csv)
        require(rows.size >= 2) { "CSV 至少两行（表头+数据）：${csv.name}" }
        val runnerText = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("sandbox/safe_runner.py")
            ?.bufferedReader()?.readText()
            ?: error("classpath 上找不到 sandbox/safe_runner.py（bench 依赖 data 模块）")
        val dir = java.nio.file.Files.createTempDirectory("dab-rlm")
        try {
            val runnerFile = dir.resolve("runner.py")
            java.nio.file.Files.write(runnerFile, runnerText.toByteArray(Charsets.UTF_8))
            val payload = buildJsonObject {
                put("code", code)
                put("columns", JsonArray(rows.first().map { JsonPrimitive(it) }))
                put("columnTypes", JsonArray(rows.first().map { JsonPrimitive("TEXT") }))
                put("rows", JsonArray(rows.drop(1).map { r -> JsonArray(r.map { JsonPrimitive(it) }) }))
            }
            val proc = ProcessBuilder(python, "-I", "-X", "utf8", runnerFile.toAbsolutePath().toString())
                .directory(dir.toFile())
                .redirectErrorStream(false)
                .start()
            proc.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val stderr = proc.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            val exited = proc.waitFor()
            // safe_runner 协议：stdout 只留 {"result": ...}；错误走 stderr + 非零退出码（3=无 result，6=沙箱拒绝）
            if (exited != 0) error("safe_runner 退出码 $exited：${stderr.take(300).ifBlank { stdout.take(300) }}")
            val parsed = Json.parseToJsonElement(stdout).jsonObject
            val result = parsed["result"] ?: error("rlm 片段没有产出 result 变量")
            val predNum = (result as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: error("result 非数字：${result.toString().take(40)}")
            val gold = task.goldNumber ?: error("rlm 题缺 gold 数字")
            if (Math.abs(predNum - gold) > goldTolerance(gold)) {
                error("数值不匹配：pred=$predNum gold=$gold")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------ 数据与判分

    private fun requireDataFile(task: DabTask): File {
        val name = task.data ?: error("题缺 data 字段")
        val f = File(datasetDir, name)
        if (!f.isFile) error("数据文件缺失：${f.absolutePath}")
        return f
    }

    private fun goldTolerance(gold: Double): Double = maxOf(Math.abs(gold) * 1e-9, 1e-9)

    /** CSV → SQLite 内存表（列名取首行；全 TEXT 导入，判分只关心值比较） */
    private fun loadCsvIntoSqlite(csv: File, conn: java.sql.Connection? = null, tableName: String = "t"): String {
        val own = conn == null
        val c = conn ?: DriverManager.getConnection("jdbc:sqlite::memory:")
        return try {
            val rows = readCsv(csv)
            require(rows.size >= 2) { "CSV 至少两行（表头+数据）：${csv.name}" }
            val header = rows.first()
            // 列名 = 首行表头（清洗内部双引号）；判分只比对值，列名大小写不敏感（SQLite）
            val cols = header.joinToString(",") { name -> '"' + name.replace("\"", "").trim() + '"' }
            c.createStatement().use { st ->
                st.execute("CREATE TABLE $tableName ($cols)")
                val ph = header.indices.joinToString(",") { "?" }
                val ps = c.prepareStatement("INSERT INTO $tableName VALUES ($ph)")
                for (row in rows.drop(1)) {
                    for (i in header.indices) ps.setString(i + 1, row.getOrNull(i) ?: "")
                    ps.addBatch()
                }
                ps.executeBatch()
                ps.close()
            }
            tableName
        } finally {
            if (own) c.close()
        }
    }

    private fun queryOn(conn: java.sql.Connection, sql: String): Table =
        conn.createStatement().use { st ->
            st.maxRows = MAX_ROWS + 1
            st.executeQuery(sql).use { rs -> readTable(rs) }
        }

    private fun readTable(rs: ResultSet): Table {
        val meta = rs.metaData
        val n = meta.columnCount
        val columns = (1..n).map { meta.getColumnLabel(it) ?: "column$it" }
        val rows = mutableListOf<List<Cell>>()
        while (rs.next()) {
            if (rows.size == MAX_ROWS) error("结果超过 $MAX_ROWS 行，拒绝比较")
            rows += (1..n).map { i -> jdbcCell(rs.getObject(i)) }
        }
        return Table(columns, rows)
    }

    private fun jdbcCell(value: Any?): Cell = when (value) {
        null -> Cell.Missing
        is Boolean -> Cell.IntCell(if (value) 1L else 0L)
        is Byte -> Cell.IntCell(value.toLong())
        is Short -> Cell.IntCell(value.toLong())
        is Int -> Cell.IntCell(value.toLong())
        is Long -> Cell.IntCell(value)
        is Float -> Cell.NumCell(value.toDouble())
        is Double -> Cell.NumCell(value.toDouble())
        is java.math.BigDecimal -> Cell.NumCell(value.toDouble())
        is java.math.BigInteger -> Cell.IntCell(value.toLong())
        is ByteArray -> Cell.TextCell(String(value, Charsets.UTF_8))
        else -> Cell.TextCell(value.toString())
    }

    /** 极简 CSV 读取（RFC4180 子集：引号转义 "" ；与导入器口径独立——bench 自包含） */
    private fun readCsv(f: File): List<List<String>> {
        val text = f.readText(Charsets.UTF_8)
        val rows = mutableListOf<List<String>>()
        val cur = mutableListOf<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQ && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i += 2 }
                ch == '"' -> { inQ = !inQ; i += 1 }
                !inQ && ch == ',' -> { cur.add(sb.toString()); sb.clear(); i += 1 }
                !inQ && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i += 1
                    cur.add(sb.toString()); sb.clear()
                    rows.add(cur.toList()); cur.clear()
                    i += 1
                }
                else -> { sb.append(ch); i += 1 }
            }
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) {
            cur.add(sb.toString())
            rows.add(cur.toList())
        }
        return rows.filter { it.isNotEmpty() || rows.size == 1 }
    }

    // ------------------------------------------------------------ manifest

    data class DabManifest(val tasks: List<DabTask>)

    data class DabTask(
        val id: String,
        val dimension: String,
        val data: String?,
        val sql: String?,
        val steps: List<String>?,
        val code: String?,
        val goldTable: Table?,
        val goldNumber: Double?,
        val ignoreOrder: Boolean,
    )

    fun loadManifest(): DabManifest {
        val root = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: error("manifest 顶层必须是对象")
        val arr = root["tasks"] as? JsonArray ?: error("manifest 缺少 tasks 数组")
        val tasks = arr.map { el ->
            val o = el as? JsonObject ?: error("tasks 元素必须是对象")
            val goldEl = o["gold"]
            DabTask(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: error("字段 id 缺失"),
                dimension = (o["dimension"] as? JsonPrimitive)?.contentOrNull ?: error("字段 dimension 缺失"),
                data = (o["data"] as? JsonPrimitive)?.contentOrNull,
                sql = (o["sql"] as? JsonPrimitive)?.contentOrNull,
                steps = (o["steps"] as? JsonArray)?.map { (it as JsonPrimitive).content },
                code = (o["code"] as? JsonPrimitive)?.contentOrNull,
                goldTable = (goldEl as? JsonObject)?.let { goldTableOf(it) },
                goldNumber = (goldEl as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull(),
                ignoreOrder = (o["ignore_order"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        require(tasks.map { it.id }.toSet().size == tasks.size) { "manifest 中 id 重复" }
        return DabManifest(tasks)
    }

    /** gold 表：{"columns":[..],"rows":[[cell..]]}（cell 数字/字符串皆可，与 GoldComparator 对齐） */
    private fun goldTableOf(o: JsonObject): Table = Table(
        columns = (o["columns"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList(),
        rows = (o["rows"] as? JsonArray)?.map { row ->
            (row as? JsonArray)?.map { cell ->
                val p = cell as? JsonPrimitive ?: return@map Cell.TextCell("null")
                val c = p.contentOrNull ?: return@map Cell.Missing
                val d = c.toDoubleOrNull()
                if (d != null && c.matches(NUM_RE)) Cell.NumCell(d)
                else if (c.toLongOrNull() != null) Cell.IntCell(c.toLong())
                else Cell.TextCell(c)
            } ?: emptyList()
        } ?: emptyList(),
    )

    private fun skip(reason: String): DabRun = DabRun(
        SuiteResult(
            name = SUITE_NAME,
            passed = 0,
            total = 0,
            skipped = true,
            notes = "SKIP：$reason",
        ),
        emptyMap(),
    )

    companion object {
        const val SUITE_NAME = "external-dab-bench"
        const val DATASET_ENV = "STRAGENT_BENCH_EXTERNAL_DIR"

        private const val MAX_ROWS = 200_000

        /** 数字字面量判别（gold cell 走数值比较） */
        private val NUM_RE = Regex("""[+-]?\d+\.\d+""")

        fun resolveRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "bench/external/manifest.json").isFile) return dir
                dir = dir.parentFile
            }
            return File(System.getProperty("user.dir") ?: ".")
        }

        fun resolveDatasetDir(repoRoot: File): File {
            val env = com.stragent.core.config.Config.get(DATASET_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            return if (env != null) File(env).absoluteFile else File(repoRoot, "bench/external/data")
        }

        /** python 可用性探测（RLM 维转验判定；失败返回 null = 显式不可用） */
        fun detectPython(): String? = listOf("python", "python3").firstNotNullOfOrNull { name ->
            runCatching {
                val p = ProcessBuilder(name, "--version").start()
                val done = p.waitFor()
                if (done == 0) name else null
            }.getOrNull()
        }
    }
}
