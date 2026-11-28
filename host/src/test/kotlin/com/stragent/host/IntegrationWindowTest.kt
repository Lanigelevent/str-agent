package com.stragent.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-112 RGB 信息整合窗口覆盖（arXiv 2309.01431 第三能力）：需要两处事实共同回答的问题，
 * 单查询 BM25 窗口应同时容纳两个远距离事实段（整合的前置条件是「双源同窗」）。
 * 业界轻量多跳路线（查询分解/HopRAG 伪查询/SAG 查询期连接）已调研登记；本测试钉现状基线，
 * 分解式升级（进 rewrite 三模式族）留作后续任务（依赖评审）。
 */
class IntegrationWindowTest {

    @Test
    fun `信息整合——双远距事实同窗`() {
        val factA = "专项事实登记 JA-118：东侧栈桥在观测期初完成了荷载复核。"
        val factB = "专项事实登记 JB-336：西侧防渗墙在观测期末完成了闭合验证。"
        val plain = "常规监测数据按统一口径逐日登记，各项读数均在允许范围内波动，记录完整可复核。"
        val segs = buildList {
            repeat(30) { add(plain) }
            add(factA)
            repeat(60) { add(plain) }
            add(factB)
            repeat(30) { add(plain) }
        }
        val f = File.createTempFile("t112-integ", ".txt")
        f.writeText(segs.joinToString("\n"), Charsets.UTF_8)
        val fake = FakeLlmClient()
        val b = StrAgentHostBootstrap.create(
            File(System.getProperty("java.io.tmpdir"), "str-agent-itw-" + System.nanoTime()).also { it.mkdirs() },
            llm = fake,
        )
        try {
            val dsId = call(b, "stragent_import_text", buildJsonObject {
                put("filePath", f.absolutePath); put("name", "整合材料")
            })["structuredContent"]!!.jsonObject.getValue("datasetId").jsonPrimitive.content
            val q = "JA-118 东侧栈桥与 JB-336 西侧防渗墙各自完成了什么？"
            fake.prompts.clear()
            call(b, "stragent_ask_followup", buildJsonObject {
                put("datasetId", dsId); put("question", q)
            })
            val w = (fake.prompts.lastOrNull() ?: "").replace(q, "")
            assertTrue(w.contains("JA-118") && w.contains("荷载复核"), "事实 A 入窗")
            assertTrue(w.contains("JB-336") && w.contains("闭合验证"), "事实 B 同窗（双源同窗=整合前置）")
        } finally {
            b.close()
        }
    }

    private fun call(b: StrAgentHostBootstrap, name: String, args: kotlinx.serialization.json.JsonObject) =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject { put("name", name); put("arguments", args) },
        )
}
