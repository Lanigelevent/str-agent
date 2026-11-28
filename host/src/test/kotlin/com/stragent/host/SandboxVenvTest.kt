package com.stragent.host

import com.stragent.core.compute.SandboxLevel
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2 Pandas 代码执行 —— SandboxLevel + venv 支持 + deps 传递测试（D11 v3）。
 *
 * 验证维度：
 *  1. 无 venv 时 sandboxLevel = STANDARD
 *  2. 无 venv 时 manifest deps 为空
 *  3. 有 venvPath 但路径不存在时退化为 STANDARD
 *  4. safe_runner.py 从 resources 加载（代码执行成功 = runner 加载成功）
 *  5. 工具描述提及 pandas/venv
 */
class SandboxVenvTest {

    private fun sampleDataset(): Dataset = Dataset(
        name = "test",
        importedAt = System.currentTimeMillis(),
        columns = listOf("地区", "销售额"),
        rows = listOf(listOf("华东", "1000"), listOf("华北", "300")),
        columnTypes = listOf(ColumnType.TEXT, ColumnType.NUMBER),
    )

    @Test
    fun `无 venv 时 sandboxLevel 为 STANDARD`() {
        val provider = SubprocessPythonProvider()
        assertEquals(SandboxLevel.STANDARD, provider.sandboxLevel)
    }

    @Test
    fun `venv 路径不存在时退化为 STANDARD`() {
        val provider = SubprocessPythonProvider(venvPath = "/nonexistent/venv/path")
        // probe() 尚未调用时 venvResolved = false
        assertEquals(SandboxLevel.STANDARD, provider.sandboxLevel)
    }

    @Test
    fun `无 venv 时 manifest deps 为空`() {
        val provider = SubprocessPythonProvider()
        if (!provider.isAvailable()) return  // 无 Python 环境时跳过
        val ds = sampleDataset()
        val outcome = provider.execute("result = sum(float(r[1]) for r in rows)", ds)
        assertEquals(0, outcome.manifest.deps.size, "标准模式下 deps 应为空")
        assertTrue(outcome.manifest.env.startsWith("cpython-"), "env 应以 cpython- 开头")
    }

    @Test
    fun `safe_runner 从 resources 加载成功`() {
        val provider = SubprocessPythonProvider()
        if (!provider.isAvailable()) return
        val ds = sampleDataset()
        // safe_runner.py 支持 columns/columnTypes/rows 变量（与原 RUNNER 兼容）
        val outcome = provider.execute("result = len(rows)", ds)
        assertEquals(2, outcome.value.toString().toIntOrNull(), "应返回行数 2")
    }

    @Test
    fun `pythonComputeTool 描述提及 pandas 或 venv`() {
        val tool = pythonComputeTool(
            datasets = com.stragent.data.AbilityDatasets(),
            prefs = object : com.stragent.core.ports.PrefsStore {
                override fun get(key: String, fallback: String) = fallback
                override fun set(key: String, value: String) {}
                override fun remove(key: String) {}
            },
        )
        val desc = tool.description
        assertTrue(desc.contains("pandas") || desc.contains("venv") || desc.contains("DataFrame"),
            "描述应提及 pandas/venv/DataFrame: ${desc.take(100)}")
    }

    @Test
    fun `pythonComputeTool 接受 venvPath 参数`() {
        // 验证 pythonComputeTool 函数签名包含 venvPath
        val tool = pythonComputeTool(
            datasets = com.stragent.data.AbilityDatasets(),
            prefs = object : com.stragent.core.ports.PrefsStore {
                override fun get(key: String, fallback: String) = fallback
                override fun set(key: String, value: String) {}
                override fun remove(key: String) {}
            },
            venvPath = null,  // 显式传 null，验证参数存在
        )
        assertEquals("stragent_compute_python", tool.name)
    }
}
