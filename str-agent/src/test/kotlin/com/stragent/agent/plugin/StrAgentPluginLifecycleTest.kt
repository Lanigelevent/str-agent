package com.stragent.agent.plugin

import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import com.stragent.core.ports.ChatResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T-093 · StrAgentPlugin 插件壳的生命周期测试（覆盖率补强靶点：此前 34%）。
 *
 * 覆盖：install 装配（引擎/注册表就位）→ activate 激活 → capabilities 声明
 * （kind=agent-engine 与能力条目）→ 重复 install 的幂等性 → uninstall 卸载后
 * 未安装状态的中文报错（engine/registryOf 的守护路径）。
 * generateInsight/askFollowup 的端到端委托由 host 层集成测试覆盖（诚实边界：
 * 本套件只测壳本身）。
 */
class StrAgentPluginLifecycleTest {

    private class StubLlm : LlmClient {
        override fun chat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
        ): ChatResult = ChatResult(text = "# 报告", totalTokens = 1, truncated = false)

        override fun streamChat(
            model: String,
            messages: List<Pair<String, String>>,
            temperature: Double,
            maxTokens: Int,
            onToken: ((String) -> Unit)?,
        ): ChatResult = chat(model, messages, temperature, maxTokens)
    }

    private class MapPrefs : PrefsStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class MapStore : DatasetStore {
        private val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private fun tempDir(): File = Files.createTempDirectory("stragent-plugin-test").toFile()

    private fun context(home: File) = StrAgentHostContext(
        llm = StubLlm(),
        prefs = MapPrefs(),
        kv = MapStore(),
        pluginDir = File(home, "plugins"),
        checkpointDir = File(home, "checkpoints"),
    )

    @Test
    fun `install 激活后——引擎与注册表就位，capabilities 声明 kind（T-093）`() {
        val home = tempDir()
        val plugin = StrAgentPlugin()
        try {
            plugin.install(context(home))
            plugin.activate()
            assertTrue(plugin.isActive, "activate 后应处于激活态")
            assertEquals("agent-engine", plugin.manifest.kind)
            assertTrue(plugin.capabilities().isNotEmpty(), "能力声明不应为空")
            assertEquals("str-agent", plugin.manifest.id)
            // 引擎与注册表在安装后可用（守护路径不抛）
            plugin.engine
            plugin.registryOf
        } finally {
            plugin.uninstall()
            home.deleteRecursively()
        }
    }

    @Test
    fun `重复 install——重建装配不崩（幂等替换语义）`() {
        val home = tempDir()
        val plugin = StrAgentPlugin()
        try {
            plugin.install(context(home))
            plugin.install(context(home))
            plugin.activate()
            assertTrue(plugin.isActive)
        } finally {
            plugin.uninstall()
            home.deleteRecursively()
        }
    }

    @Test
    fun `未安装时访问引擎——中文报错指路 install（守护路径，T-093）`() {
        val plugin = StrAgentPlugin()
        for (action in listOf({ plugin.engine }, { plugin.registryOf })) {
            try {
                action()
                fail("未安装应抛")
            } catch (e: IllegalStateException) {
                assertTrue(
                    e.message!!.contains("未安装") && e.message!!.contains("install"),
                    "守护错误应指路 install：${e.message}",
                )
            }
        }
    }

    @Test
    fun `uninstall 后 isActive 复位——再激活前不可用`() {
        val home = tempDir()
        val plugin = StrAgentPlugin()
        try {
            plugin.install(context(home))
            plugin.activate()
            plugin.deactivate()
            assertTrue(!plugin.isActive, "deactivate 后应退出激活态")
            plugin.activate()
            assertTrue(plugin.isActive, "可再激活")
        } finally {
            plugin.uninstall()
            home.deleteRecursively()
        }
    }
}
