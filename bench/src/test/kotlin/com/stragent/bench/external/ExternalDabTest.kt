package com.stragent.bench.external

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-186 ExternalDab 负控与口径测试（判据④：注入错误 gold → 红，证明判分器真的在比对）。
 */
class ExternalDabTest {

    private fun tempDir(): Path = Files.createTempDirectory("dab-test")

    /** 写合成夹具：manifest + CSV 数据；goldError=true 时注入错误 gold */
    private fun fixture(goldError: Boolean): Path {
        val dir = tempDir()
        val data = dir.resolve("data")
        Files.createDirectories(data)
        Files.writeString(
            data.resolve("sales.csv"),
            "city,amount\n上海,12.5\n北京,29.5\n",
        )
        val gold = if (goldError) "43" else "42"
        Files.writeString(
            dir.resolve("manifest.json"),
            """
            {
              "tasks": [
                {
                  "id": "n1",
                  "dimension": "numeric",
                  "data": "sales.csv",
                  "sql": "SELECT SUM(CAST(amount AS REAL)) FROM t",
                  "gold": {"columns": ["s"], "rows": [[42.0]]}
                },
                {
                  "id": "n1-bad",
                  "dimension": "numeric",
                  "data": "sales.csv",
                  "sql": "SELECT SUM(CAST(amount AS REAL)) FROM t",
                  "gold": {"columns": ["s"], "rows": [[$gold]]}
                }
              ]
            }
            """.trimIndent(),
        )
        return dir
    }

    private fun dab(dir: Path, pythonBin: String? = "python") =
        ExternalDab(
            repoRoot = dir.toFile(),
            datasetDir = dir.resolve("data").toFile(),
            manifestFile = dir.resolve("manifest.json").toFile(),
            pythonBin = pythonBin,
        )

    @Test
    fun `numeric 负控——正确 gold 绿、注入错误 gold 红`() {
        val run = dab(fixture(true)).run()
        assertEquals(1, run.suite.passed, "正确 gold 必须绿：${run.suite.failures}")
        assertEquals(2, run.suite.total)
        assertTrue(run.suite.failures.any { it.startsWith("n1-bad") }, "错误 gold 必须红：${run.suite.failures}")
        assertTrue(!run.suite.ok, "含错误 gold 的套件不得整体通过")
    }

    @Test
    fun `tool-use 维——步骤链执行与 gold 对拍`() {
        val dir = fixture(false)
        val data = dir.resolve("data")
        Files.writeString(
            dir.resolve("manifest.json"),
            """
            {
              "tasks": [
                {
                  "id": "t1",
                  "dimension": "tool-use",
                  "data": "sales.csv",
                  "steps": [
                    "CREATE TEMP TABLE agg AS SELECT city, SUM(CAST(amount AS REAL)) s FROM src GROUP BY city",
                    "SELECT * FROM agg ORDER BY city"
                  ],
                  "gold": {"columns": ["city", "s"], "rows": [["上海", 12.5], ["北京", 29.5]]},
                  "ignore_order": false
                },
                {
                  "id": "t1-bad",
                  "dimension": "tool-use",
                  "data": "sales.csv",
                  "steps": [
                    "CREATE TEMP TABLE agg AS SELECT city, SUM(CAST(amount AS REAL)) s FROM src GROUP BY city",
                    "SELECT * FROM agg ORDER BY city"
                  ],
                  "gold": {"columns": ["city", "s"], "rows": [["上海", 12.5], ["北京", 30.0]]}
                }
              ]
            }
            """.trimIndent(),
        )
        val run = dab(dir).run()
        assertEquals(1, run.suite.passed)
        assertTrue(run.suite.failures.any { it.startsWith("t1-bad") }, "${run.suite.failures}")
    }

    @Test
    fun `数据目录缺省显式 SKIP——同 Spider 口径`() {
        val dir = tempDir()
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("manifest.json"),
            "{\"tasks\": [{\"id\": \"x\", \"dimension\": \"numeric\", \"data\": \"missing.csv\", \"sql\": \"SELECT 1\", \"gold\": {\"columns\": [], \"rows\": []}}]}",
        )
        val run = dab(dir).run()
        assertTrue(run.suite.skipped, "数据目录不存在必须显式 SKIP（Spider 口径）")
        assertTrue(run.suite.notes.contains("SKIP"), run.suite.notes)
    }

    @Test
    fun `目录在但数据文件缺——失败不伪造通过`() {
        val dir = fixture(false)
        Files.writeString(
            dir.resolve("manifest.json"),
            "{\"tasks\": [{\"id\": \"x\", \"dimension\": \"numeric\", \"data\": \"missing.csv\", \"sql\": \"SELECT 1\", \"gold\": {\"columns\": [], \"rows\": []}}]}",
        )
        val run = dab(dir).run()
        assertTrue(run.suite.failures.isNotEmpty(), "数据文件缺失必须报失败而不是静默通过：notes=${'$'}{run.suite.notes}")
    }

    @Test
    fun `空 manifest 显式 SKIP 口径`() {
        val dir = tempDir()
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("manifest.json"), "{\"tasks\": []}")
        val run = dab(dir).run()
        assertTrue(run.suite.skipped, "空题集必须显式 SKIP")
        assertTrue(run.suite.notes.contains("SKIP"), run.suite.notes)
    }

    @Test
    fun `rlm 双态——无 python 显式转验不可用、有 python 真跑 safe_runner`() {
        val dir = fixture(false)
        val noPy = dab(dir, pythonBin = null).run()
        val pyName = ExternalDab.detectPython()
        if (pyName == null) {
            assertTrue(noPy.suite.notes.contains("转验不可用") || noPy.suite.failures.isNotEmpty())
            return
        }
        // 本机有 python：rlm 维实跑（safe_runner 协议真链路）
        Files.writeString(
            dir.resolve("manifest.json"),
            """
            {
              "tasks": [
                {
                  "id": "r1",
                  "dimension": "rlm",
                  "data": "sales.csv",
                  "code": "import json
result = sum(float(r[1]) for r in rows)",
                  "gold": 42.0
                },
                {
                  "id": "r1-bad",
                  "dimension": "rlm",
                  "data": "sales.csv",
                  "code": "import json
result = sum(float(r[1]) for r in rows)",
                  "gold": 43.0
                }
              ]
            }
            """.trimIndent(),
        )
        val withPy = dab(dir, pythonBin = pyName).run()
        assertEquals(1, withPy.suite.passed, "正确 gold 的 rlm 题必须绿：${withPy.suite.failures}")
        assertTrue(withPy.suite.failures.any { it.startsWith("r1-bad") }, "错误 gold 必须红：${withPy.suite.failures}")
    }
}