package com.stragent.host

import com.stragent.data.ElicitAction
import com.stragent.data.ElicitationBroker
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-077 · 多轮 elicitation（D21 F-5）：「表单 → 校验 → 再问」两轮协议。
 *
 * 验的是协议语义：
 *  1. 两轮往返：第一轮 accept 后发第二轮，**复用同一 pending id**（客户端按普通请求路由，
 *     id 复用是本项目的续问关联语义——2026-07-28 规范允许多次 elicitation/create）；
 *  2. 第一轮 decline/cancel 时尊重回答，不追问（rounds 不出现在回执）；
 *  3. 回执带 rounds=2 与 firstContent（第一轮的 accept 内容）；
 *  4. followUp 结构错误如实报错。
 */
class ElicitMultiRoundTest {

    /** 捕获服务端发出的 elicitation/create 帧 */
    private class CapturingChannel {
        val frames = LinkedBlockingQueue<String>()
        val sender: (String) -> Boolean = { payload -> frames.offer(payload); true }

        fun awaitFrame(): JsonObject {
            val line = frames.poll(3, TimeUnit.SECONDS) ?: throw AssertionError("3 秒内没有收到服务端请求帧")
            return Json.parseToJsonElement(line).jsonObject
        }
    }

    /** 预置应答序列：第 N 帧 → answers[N](id) 生成的回包交给 broker.resolve */
    private fun responder(
        broker: ElicitationBroker,
        channel: CapturingChannel,
        answers: List<(String) -> JsonObject>,
        sameIdFlag: AtomicReference<Boolean>? = null,
    ) = thread {
        var previousId: String? = null
        for (answer in answers) {
            val frame = channel.awaitFrame()
            val id = frame["id"]!!.jsonPrimitive.content
            if (previousId != null && sameIdFlag != null) {
                sameIdFlag.set(id == previousId)
            }
            previousId = id
            broker.resolve("default", id, answer(id))
        }
    }

    private fun accept(content: JsonObject): (String) -> JsonObject = { _ ->
        buildJsonObject { put("action", "accept"); put("content", content) }
    }

    private fun invokeTool(broker: ElicitationBroker, args: JsonObject): JsonObject =
        elicitInputTool(broker).invoke(args)

    private val baseArgs = buildJsonObject {
        put("message", "报告标题叫什么？")
        put("fields", buildJsonObject { put("title", buildJsonObject { put("type", "string") }) })
    }

    @Test
    fun `两轮往返-同一pending id续问且回执带firstContent`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("default", channel.sender)
        val sameId = AtomicReference<Boolean>(false)
        responder(
            broker, channel,
            listOf(
                accept(buildJsonObject { put("title", "季度复盘") }),
                accept(buildJsonObject { put("confirm", true) }),
            ),
            sameId,
        )

        val receipt = invokeTool(
            broker,
            buildJsonObject {
                put("message", "报告标题叫什么？")
                put("fields", buildJsonObject { put("title", buildJsonObject { put("type", "string") }) })
                put(
                    "followUp",
                    buildJsonObject {
                        put("message", "确认用这个标题生成吗？")
                        put("fields", buildJsonObject { put("confirm", buildJsonObject { put("type", "boolean") }) })
                    },
                )
            },
        )

        assertEquals("accept", receipt.str("action"))
        assertEquals(2, receipt.str("rounds").toInt(), "两轮协议要在回执标明 rounds")
        assertEquals("true", receipt.json("content")["confirm"]!!.jsonPrimitive.content, "content 是第二轮的回答")
        assertEquals(
            "季度复盘",
            receipt.json("firstContent")!!["title"]!!.jsonPrimitive.content,
            "firstContent 是第一轮的回答",
        )
        assertTrue(sameId.get(), "第二轮必须复用第一轮的同一 pending id")
        assertEquals(0, broker.pendingCount(), "收口后不得留下在途请求")
    }

    @Test
    fun `第一轮decline-尊重回答不追问`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("default", channel.sender)
        responder(
            broker, channel,
            listOf { _ -> buildJsonObject { put("action", "decline") } },
        )

        val receipt = invokeTool(
            broker,
            buildJsonObject {
                put("message", "报告标题叫什么？")
                put(
                    "followUp",
                    buildJsonObject { put("message", "这个问题不该被问到") },
                )
            },
        )

        assertEquals("decline", receipt.str("action"))
        assertNull(receipt["rounds"], "没走第二轮就不该有 rounds 字段")
        // 不追问：预置的第二帧永远不会来（responder 已经退出，队列保持空）
        assertNull(channel.frames.poll(300, TimeUnit.MILLISECONDS), "decline 后不得发出第二轮")
    }

    @Test
    fun `无通道-一轮收场如实降级`() {
        val broker = ElicitationBroker() // 未注册任何通道
        val receipt = invokeTool(broker, baseArgs)
        assertEquals(ElicitAction.CANCEL.wire, receipt.str("action"))
        assertTrue(receipt.str("reason").contains("通道"), "原因要指向通道缺失：${receipt.str("reason")}")
        assertNull(receipt["rounds"], "降级路径没有第二轮")
    }

    @Test
    fun `followUp缺message-如实报错`() {
        val broker = ElicitationBroker()
        val channel = CapturingChannel()
        broker.register("default", channel.sender)
        // 不需要 responder：错误发生在发起第一轮之前
        val error = assertFailsWith<IllegalArgumentException> {
            invokeTool(
                broker,
                buildJsonObject {
                    put("message", "第一轮")
                    put("followUp", buildJsonObject { put("fields", buildJsonObject { }) })
                },
            )
        }
        assertTrue(error.message!!.contains("message"), "报错要指明缺 message：${error.message}")
        assertTrue(channel.frames.isEmpty(), "校验失败不得发出任何提问")
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: error("缺少字段 $key：$this")

    private fun JsonObject.json(key: String): JsonObject =
        (this[key] as? JsonObject) ?: error("缺少对象字段 $key：$this")
}
