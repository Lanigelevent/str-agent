package com.stragent.agent.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-160 · 插件装载契约钉子（D25 Q7 覆盖率盲区：DshContract/StrAgentPluginManifest
 * 此前零直接测试——这是「宿主对接」的第一道界面，回归成本最高）。
 *
 * 钉的是**结构契约**：注入缝的必需性、贡献缝的能力面、manifest 双形制（全量/the reference harness）的字段形状。
 */
class DshContractTest {

    @Test
    fun `注入缝——三个必需缝与一个可选缝（session）`() {
        val required = STR_AGENT_INJECT_SEAMS.filter { it.required }.map { it.id }
        val optional = STR_AGENT_INJECT_SEAMS.filter { !it.required }.map { it.id }
        assertEquals(listOf("model", "storage", "prefs"), required, "必需缝清单")
        assertEquals(listOf("session"), optional, "可选缝=轨迹日志（缺失走内置兜底）")
    }

    @Test
    fun `贡献缝——五条能力面固定（报告 追问 审查 编辑部 MCP 桥）`() {
        assertEquals(
            listOf("report", "followup", "review", "editorial", "mcp-bridge"),
            STR_AGENT_CONTRIBUTE_SEAMS.map { it.id },
            "能力面清单变化必须过本钉子（对外契约变更需显式 review）",
        )
        assertTrue(
            STR_AGENT_CONTRIBUTE_SEAMS.all { it.role == SeamRole.PROVIDER },
            "贡献缝全部为 PROVIDER 角色",
        )
        assertTrue(
            STR_AGENT_INJECT_SEAMS.all { it.role == SeamRole.CONSUMER },
            "注入缝全部为 CONSUMER 角色",
        )
    }

    @Test
    fun `toJsonArray——形状与 required 省略语义`() {
        val json = STR_AGENT_INJECT_SEAMS.toJsonArray()
        val first = (json as JsonArray).first().jsonObject
        assertEquals("model", first.getValue("id").jsonPrimitive.content)
        assertEquals("CONSUMER", first.getValue("role").jsonPrimitive.content)
        assertTrue(first.containsKey("contract") && first.containsKey("description"))
        // required=false 的缝显式写出；required=true 缺省省略
        val session = json.jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == "session" }
        assertEquals(false, session.getValue("required").jsonPrimitive.content.toBooleanStrict())
        val model = json.jsonArray.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == "model" }
        assertTrue(!model.containsKey("required"), "required=true 应缺省省略")
    }

    @Test
    fun `manifest——派生字段与 seam 一致（向后兼容面）`() {
        val m = StrAgentPluginManifest()
        assertEquals(STR_AGENT_INJECT_SEAMS.size, m.requiredHost.size, "requiredHost 从 inject 派生")
        assertEquals(STR_AGENT_CONTRIBUTE_SEAMS.size, m.provides.size, "provides 从 contributes 派生")
        assertEquals(m.inject.map { it.id }, m.requiredHost.map { it.name }, "派生保持顺序一致（HostRequirement.name = seam.id）")
    }

    @Test
    fun `manifest——两种导出各守其形（全量 vs the reference harness）`() {
        val m = StrAgentPluginManifest()
        // 全量形：inject/contributes 都是完整 seam 数组
        val full = m.toJson()
        assertEquals(4, full.getValue("inject").jsonArray.size, "注入缝 4 条")
        assertEquals(5, full.getValue("contributes").jsonArray.size, "贡献缝 5 条")
        assertEquals("str-agent", (full.getValue("id") as JsonPrimitive).content)
        // the reference harness 形：inject 只列 required 缝的 id；contributes 是按能力的对象形
        val dsh = m.toDshManifestJson()
        val dshInject = dsh.getValue("inject").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("model", "storage", "prefs"), dshInject, "the reference harness 形只含必需缝 id（session 可选不列）")
        val dshContributes = dsh.getValue("contributes").jsonObject
        assertEquals(setOf("tools", "services"), dshContributes.keys, "the reference harness 贡献按 tools/services 两组")
        assertEquals(
            setOf("report", "followup", "review", "editorial"),
            dshContributes.getValue("tools").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet(),
            "tools 组=agent- 前缀四能力",
        )
        assertEquals(
            listOf("mcp-bridge"),
            dshContributes.getValue("services").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
            "services 组=MCP 桥",
        )
    }
}
