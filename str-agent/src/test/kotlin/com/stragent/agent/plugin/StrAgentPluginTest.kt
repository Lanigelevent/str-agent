package com.stragent.agent.plugin

import com.stragent.agent.plugin.DshPluginType
import com.stragent.core.ports.ChatResult as PortChatResult
import com.stragent.core.ports.DatasetStore
import com.stragent.core.ports.LlmClient
import com.stragent.core.ports.PrefsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * StrAgentPlugin 作为「万物皆插件」单元的契约自测：
 * 装载即就绪、能力面可枚举、自描述清单可序列化、harness 装载/卸载闭环。
 */
class StrAgentPluginTest {

    private class ScriptedLlm(private val outputs: MutableList<String>) : LlmClient {
        override fun chat(model: String, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): PortChatResult {
            val out = if (outputs.isNotEmpty()) outputs.removeAt(0) else """{"tool":"finish","args":{"report":"ok"}}"""
            return PortChatResult(out, 10)
        }
    }

    private class MemPrefs : PrefsStore {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String, fallback: String): String = map[key] ?: fallback
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private class MemStore : DatasetStore {
        private val map = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { map[key] = value }
        override fun get(key: String): String? = map[key]
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): List<String> = map.keys.toList()
    }

    private fun tempDir(name: String): java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").also { it.mkdirs() }

    private fun installed(): StrAgentPlugin {
        val plugin = StrAgentPlugin()
        plugin.install(
            StrAgentHostContext(
                llm = ScriptedLlm(mutableListOf()),
                prefs = MemPrefs(),
                kv = MemStore(),
                pluginDir = tempDir("str-agent-plugins"),
            ),
        )
        return plugin
    }

    @Test
    fun `install wires engine and registry`() {
        val plugin = installed()
        plugin.activate()
        assertTrue(plugin.isActive)
        assertNotNull(plugin.engine)
        assertEquals(5, plugin.registryOf.list().size) // 5 内置 agent 插件（2 report / followup / review / editorial）
    }

    @Test
    fun `capabilities are enumerable and match manifest`() {
        val plugin = installed()
        val caps = plugin.capabilities()
        assertEquals(listOf("report", "followup", "review", "editorial", "mcp-bridge"), caps.map { it.id })
        assertEquals(5, plugin.manifest.provides.size)
    }

    @Test
    fun `invoke manifest returns serializable self-describing json`() {
        val plugin = installed()
        val resp = plugin.invoke(PluginRequest("manifest"))
        assertTrue(resp.ok)
        val json = Json.parseToJsonElement(resp.payload["manifest"] as String).jsonObject
        assertEquals("str-agent", json["id"]?.toString()?.trim('"'))
        assertEquals("agent-engine", json["kind"]?.toString()?.trim('"'))
        assertTrue((json["provides"] as kotlinx.serialization.json.JsonArray).size == 5)
    }

    @Test
    fun `invoke unknown capability reports error without throwing`() {
        val plugin = installed()
        val resp = plugin.invoke(PluginRequest("nope"))
        assertFalse(resp.ok)
        assertNotNull(resp.error)
    }

    /** 与宿主 AgentHost 同款：CSV -> Dataset（确定性） */
    private fun datasetOf(): com.stragent.core.dataset.Dataset =
        com.stragent.core.dataset.datasetFromParsed(
            "样本",
            0L,
            com.stragent.core.csv.Csv.limitRows(com.stragent.core.csv.Csv.parseDelimited("地,销售圖\n华东,1000\n华北,300\n")),
        )

    @Test
    fun `review dispatch——确定性复核给结构化裁定，未溯源数字如实标出`() {
        val plugin = installed()
        val ds = datasetOf()
        // 未溯源数字（8888 不在数据里）-> passed=false，问题如实列出
        val bad = plugin.invoke(PluginRequest("review", mapOf(
            "dataset" to ds,
            "report" to "华东销售额 8888 万，遥遥领先。",
        )))
        assertTrue(bad.ok, "review 应成功：" + (bad.error ?: ""))
        assertEquals(false, bad.payload["passed"], "未溯源数字必须判不通过：" + bad.payload.toString())
        assertTrue((bad.payload["unsourced"] as List<*>).isNotEmpty(), "问题清单要有未溯源项")

        // 全部数字可溯源（1000/300 在简报里，1300 是可推导合计）-> passed=true
        val good = plugin.invoke(PluginRequest("review", mapOf(
            "dataset" to ds,
            "report" to "华东销售额 1000，华北 300；两个地区合计 1300。",
        )))
        assertTrue(good.ok)
        assertEquals(true, good.payload["passed"], "全可溯源应判通过：" + good.payload.toString())
        assertTrue((good.payload["checked"] as Int) >= 3, "至少核对三个数：" + good.payload.toString())
    }

    @Test
    fun `host load and unload lifecycle`() {
        val host = StrAgentHost(
            StrAgentHostContext(
                llm = ScriptedLlm(mutableListOf()),
                prefs = MemPrefs(),
                kv = MemStore(),
                pluginDir = tempDir("str-agent-host"),
            ),
        )
        val loaded = host.load(StrAgentPlugin())
        assertEquals("str-agent", loaded.manifest.id)
        assertEquals(1, host.list().size)
        host.unload("str-agent")
        assertTrue(host.list().isEmpty())
    }

    @Test
    fun `manifest aligns with DeepSeek Harness plugin model`() {
        val m = StrAgentPlugin().manifest
        // 1) 插件类型归属 the reference harness 分类
        assertTrue(m.pluginType.contains(DshPluginType.TOOLS), "应归类为 工具与能力")
        assertTrue(m.pluginType.contains(DshPluginType.WORKFLOWS), "应归类为 工作流与自动化")
        // 2) 静态声明消费的宿主缝（capability safety：先声明后用）
        val injectIds = m.inject.map { it.id }
        assertTrue(injectIds.containsAll(listOf("model", "storage", "prefs")), "必须声明 model/storage/prefs 缝")
        assertTrue(m.inject.none { it.id == "session" && it.required }, "session 缝为可选增强，不应阻断装载")
        // 3) 贡献的能力缝
        val contributeIds = m.contributes.map { it.id }
        assertTrue(contributeIds.containsAll(listOf("report", "followup", "review", "editorial", "mcp-bridge")))
        // 4) 可导出严格 the reference harness 形态清单（dsh 字段），供 the reference harness 装载器直接消费
        val dsh = m.toDshManifestJson()
        assertEquals("str-agent", dsh["name"]?.toString()?.trim('"'))
        val dshType = (dsh["type"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
        assertTrue(dshType.containsAll(listOf("tools-capabilities", "workflows")))
        val dshInject = (dsh["inject"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
        assertTrue(dshInject.containsAll(listOf("model", "storage", "prefs")))
        assertTrue(!dshInject.contains("session"), "the reference harness 清单的 inject 只列必需缝")
    }
}
