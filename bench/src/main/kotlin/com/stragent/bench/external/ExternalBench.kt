package com.stragent.bench.external

import com.stragent.core.config.Config
import com.stragent.bench.model.SuiteResult
import java.io.File
import java.sql.DriverManager
import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * T-057：外部 SQL 基准（Spider 2.0-Lite SQLite 子集，50 题 / 20 库）。
 *
 * 设计口径：
 *  - 题目、SQL、金标在仓库里（bench/external/），数据库（约 950MB）不进仓库；
 *    用 scripts/fetch-external-bench.py 下载并校验 SHA256，目录通过
 *    `STRAGENT_BENCH_EXTERNAL_DIR` 指定（默认 bench/external/data）。
 *  - 数据不在时**显式 SKIP**（不伪造通过也不红）：本地开发与未提供数据的 CI 可以跑；
 *    数据集在但文件大小与 db-checksums.json 不符 → FAIL（坏数据不能伪装成 SKIP）。
 *  - 只评测 SQL 执行正确性（给定 SQL 能否复现金标），不评测自然语言理解；
 *    因此不经过 Agent/LLM，任何模型替换都不影响本套件。
 *  - 官方 gold 自相矛盾的题（见 manifest.knownFailure）不计分，但保留在集内并显式记账。
 */
class ExternalBench(
    private val repoRoot: File = resolveRepoRoot(),
    private val datasetDir: File = resolveDatasetDir(resolveRepoRoot()),
    private val manifestFile: File = File(repoRoot, "bench/external/manifest.json"),
    private val goldDir: File = File(repoRoot, "bench/external/gold/exec_result"),
    private val sqlDir: File = File(repoRoot, "bench/external/sql"),
) {
    data class ExternalRun(
        val suite: SuiteResult,
        val knownFailures: List<String>,
        val datasetPresent: Boolean,
    )

    fun run(): ExternalRun {
        val manifest = runCatching { loadManifest() }.getOrElse { error ->
            return ExternalRun(
                suite = SuiteResult(
                    name = SUITE_NAME,
                    passed = 0,
                    total = 1,
                    failures = listOf("manifest 读取失败：${error.message}"),
                    notes = "manifest：${manifestFile.absolutePath}",
                ),
                knownFailures = emptyList(),
                datasetPresent = false,
            )
        }
        val scored = manifest.tasks.filter { it.knownFailure == null }
        val knownFailures = manifest.tasks.filter { it.knownFailure != null }.map { it.id }

        val dbDir = File(datasetDir, "db")
        val dbs = scored.map { it.db }.distinct().sorted()
        val missing = dbs.filter { !File(dbDir, "$it.sqlite").isFile }
        if (missing.isNotEmpty()) {
            return ExternalRun(
                suite = SuiteResult(
                    name = SUITE_NAME,
                    passed = 0,
                    total = 0,
                    skipped = true,
                    notes = "SKIP：数据目录 ${datasetDir.absolutePath} 缺少 ${missing.size} 个数据库" +
                        "（${missing.joinToString(", ")}）；先运行 scripts/fetch-external-bench.py 或设置 $DATASET_ENV",
                ),
                knownFailures = knownFailures,
                datasetPresent = false,
            )
        }

        val sizeErrors = checkDatabaseSizes(dbDir, dbs)
        if (sizeErrors.isNotEmpty()) {
            return ExternalRun(
                suite = SuiteResult(
                    name = SUITE_NAME,
                    passed = 0,
                    total = 1,
                    failures = sizeErrors,
                    notes = "数据集存在但校验失败：数据库文件大小与 db-checksums.json 不一致（重新下载）",
                ),
                knownFailures = knownFailures,
                datasetPresent = true,
            )
        }

        val failures = mutableListOf<String>()
        val passedIds = mutableListOf<String>()
        val goldCache = mutableMapOf<String, Table>()
        var passed = 0
        val started = System.nanoTime()

        for (task in scored) {
            val sqlFile = File(sqlDir, "${task.id}.sql")
            val outcome = runCatching {
                if (!sqlFile.isFile) error("缺少 SQL 文件 ${sqlFile.name}")
                val sql = sqlFile.readText(Charsets.UTF_8)
                val pred = executeQuery(File(dbDir, "${task.db}.sqlite"), sql)
                val golds = task.gold.map { name ->
                    goldCache.getOrPut(name) {
                        val f = File(goldDir, name)
                        if (!f.isFile) error("缺少金标文件 $name")
                        GoldComparator.parseGoldCsv(f.readText(Charsets.UTF_8))
                    }
                }
                val ok = GoldComparator.compareMulti(pred, golds, task.conditionCols, task.ignoreOrder)
                if (!ok) {
                    error(
                        "结果不匹配（${pred.rows.size} 行 × ${pred.columns.size} 列，gold=" +
                            "${golds.joinToString("/") { "${it.rows.size}×${it.columns.size}" }}）",
                    )
                }
            }
            outcome.fold(
                onSuccess = { passed++; passedIds += task.id },
                onFailure = { e -> failures += "${task.id} [${task.db}]: ${e.message ?: e::class.simpleName}" },
            )
        }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val recovered = knownFailures.filter { it in passedIds }
        val notes = buildString {
            append("Spider 2.0-Lite SQLite 子集：${manifest.tasks.size} 题 / ${dbs.size} 库，")
            append("计分 ${scored.size} 题；只评 SQL 执行正确性（不经 Agent/LLM）；")
            append("数据目录 ${datasetDir.absolutePath}；耗时 ${elapsedMs}ms")
            if (knownFailures.isNotEmpty()) {
                append("；官方 gold 自相矛盾、不计分：${knownFailures.joinToString(", ")}")
            }
            if (recovered.isNotEmpty()) {
                append("；注意：已知失败题意外通过：${recovered.joinToString(", ")}（可考虑移出豁免）")
            }
        }
        return ExternalRun(
            suite = SuiteResult(
                name = SUITE_NAME,
                passed = passed,
                total = scored.size,
                failures = failures,
                notes = notes,
            ),
            knownFailures = knownFailures,
            datasetPresent = true,
        )
    }

    // ---------------------------------------------------------------- manifest

    data class Manifest(val tasks: List<ExternalTask>, val sourceSqliteZipSha256: String?)

    fun loadManifest(): Manifest {
        val root = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)) as? JsonObject
            ?: error("manifest 顶层必须是对象")
        val arr = root["tasks"] as? JsonArray ?: error("manifest 缺少 tasks 数组")
        val tasks = arr.map { element ->
            val o = element as? JsonObject ?: error("tasks 元素必须是对象")
            ExternalTask(
                id = o.requiredString("id"),
                db = o.requiredString("db"),
                question = o.requiredString("question"),
                gold = (o["gold"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList(),
                conditionCols = parseConditionCols(o["condition_cols"]),
                ignoreOrder = (o["ignore_order"] as? JsonPrimitive)?.booleanOrNull ?: false,
                knownFailure = (o["knownFailure"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        require(tasks.map { it.id }.toSet().size == tasks.size) { "manifest 中 id 重复" }
        val source = (root["source"] as? JsonObject)
        return Manifest(tasks, (source?.get("sqliteZipSha256") as? JsonPrimitive)?.contentOrNull)
    }

    private fun parseConditionCols(element: JsonElement?): ConditionCols {
        val arr = element as? JsonArray ?: return ConditionCols.Empty
        if (arr.isEmpty()) return ConditionCols.Empty
        return if (arr.first() is JsonArray) {
            ConditionCols.PerGold(
                arr.map { inner ->
                    (inner as? JsonArray ?: error("condition_cols 嵌套元素必须是数组"))
                        .map { primitive -> (primitive as? JsonPrimitive)?.intOrNull ?: error("condition_cols 下标必须是整数") }
                },
            )
        } else {
            ConditionCols.Flat(
                arr.map { primitive -> (primitive as? JsonPrimitive)?.intOrNull ?: error("condition_cols 下标必须是整数") },
            )
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: error("字段 $key 缺失")

    // ---------------------------------------------------------------- dataset

    /** 只查文件大小（哈希由 fetch 脚本负责）；大小不符说明数据被截断/换版本。 */
    private fun checkDatabaseSizes(dbDir: File, dbs: List<String>): List<String> {
        val checksumFile = File(repoRoot, "bench/external/db-checksums.json")
        if (!checksumFile.isFile) return emptyList()
        val root = runCatching {
            Json.parseToJsonElement(checksumFile.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return emptyList()
        return dbs.mapNotNull { db ->
            val expected = ((root[db] as? JsonObject)?.get("bytes") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: return@mapNotNull null
            val actual = File(dbDir, "$db.sqlite").length()
            if (actual != expected) "$db.sqlite 大小 ${actual}B ≠ 期望 ${expected}B" else null
        }
    }

    // ---------------------------------------------------------------- query

    private fun executeQuery(dbFile: File, sql: String): Table {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.maxRows = MAX_ROWS + 1
                st.executeQuery(sql).use { rs -> return readTable(rs) }
            }
        }
    }

    private fun readTable(rs: ResultSet): Table {
        val meta = rs.metaData
        val n = meta.columnCount
        val columns = (1..n).map { meta.getColumnLabel(it) ?: "column$it" }
        val rows = mutableListOf<List<Cell>>()
        while (rs.next()) {
            if (rows.size == MAX_ROWS) error("结果超过 $MAX_ROWS 行，拒绝比较（题面应聚合到小结果集）")
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
        is Double -> Cell.NumCell(value)
        is java.math.BigDecimal -> Cell.NumCell(value.toDouble())
        is java.math.BigInteger -> Cell.IntCell(value.toLong())
        is ByteArray -> Cell.TextCell(String(value, Charsets.UTF_8))
        else -> Cell.TextCell(value.toString())
    }

    companion object {
        const val SUITE_NAME = "external-sql-bench"
        const val DATASET_ENV = "STRAGENT_BENCH_EXTERNAL_DIR"
        private const val MAX_ROWS = 200_000

        /** 从当前目录向上找仓库根（含 bench/external/manifest.json 的那层）。 */
        fun resolveRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                if (File(dir, "bench/external/manifest.json").isFile) return dir
                dir = dir.parentFile
            }
            return File(System.getProperty("user.dir") ?: ".")
        }

        fun resolveDatasetDir(repoRoot: File): File {
            val env = Config.get(DATASET_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            return if (env != null) File(env).absoluteFile else File(repoRoot, "bench/external/data")
        }
    }
}

data class ExternalTask(
    val id: String,
    val db: String,
    val question: String,
    val gold: List<String>,
    val conditionCols: ConditionCols,
    val ignoreOrder: Boolean,
    val knownFailure: String?,
)
