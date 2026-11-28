package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.data.ElicitAction
import com.stragent.data.ElicitationBroker
import com.stragent.data.McpSessionContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * elicitation 工具面（T-056 / D17 G-15）：让外部 agent 在长任务中途向**人**问一句。
 *
 * 与「失败重试」「大胆猜一个」的区别：缺的信息只有用户知道（报告标题、口径选择、
 * 是否覆盖已有文件），猜错就得整轮重来。elicitation 把问题交给客户端弹窗/表单，
 * 服务端只等回答——三条结局都如实返回：
 *
 *  - `accept`：用户答了，`content` 里是各字段的值；
 *  - `decline`：用户读了但拒绝回答，**不要换个问法再问一遍**；
 *  - `cancel`：没有通道 / 超时 / 客户端报错，`reason` 说明原因，按缺失继续工作。
 *
 * 传输前提：stdio 自动可用；HTTP 需要客户端先建立 `GET /mcp` 服务端消息流。
 * 没有通道时**立刻**返回 cancel（不阻塞、不假装成功），见 [ElicitationBroker.request]。
 */
fun elicitInputTool(broker: ElicitationBroker): AgentTool = object : AgentTool {
    override val name = "stragent_elicit_input"
    override val description =
        "向用户提一个结构化问题并等待回答（MCP elicitation）：适合长任务中途缺失、" +
            "且只有用户才知道的信息（报告标题、指标口径、是否覆盖已有文件）。" +
                com.stragent.core.i18n.Messages.get("elicit.desc.multi_round") +
            "返回 action=accept（带 content）/decline（用户拒绝回答）/cancel（无通道或超时，带 reason）。" +
            "**拿不到回答就按 cancel 的语义继续工作，不要反复重试**。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "message",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "给用户看的问题：一句话说清要什么、为什么需要（必填）")
                    },
                )
                put(
                    "fields",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "请求回答的字段 Schema：字段名 → {type: string|number|integer|boolean, title?, description?, enum?}。" +
                                "缺省只问一个自由文本字段 value",
                        )
                        put("additionalProperties", buildJsonObject { put("type", "object") })
                    },
                )
                put(
                    "required",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "必答字段名（缺省为 fields 的全部）")
                        put("items", buildJsonObject { put("type", "string") })
                    },
                )
                put(
                    "timeoutMs",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "等待上限（毫秒，缺省 60000，范围 1000..300000）")
                    },
                )
                put(
                    "followUp",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            // 描述文案走 i18n（预算制）
                            com.stragent.core.i18n.Messages.get("elicit.followup_field_desc"),
                        )
                        put("additionalProperties", buildJsonObject { put("type", "object") })
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("message"))))
    }

    /** T-056：结局与「有没有通道」都要结构化可见——模型据此决定继续还是换路 */
    override val outputSchema = schemaObject(
        "action" to schemaField("string", "accept / decline / cancel"),
        "content" to buildJsonObject {
            put("type", "object")
            put("description", "action=accept 时用户填写的字段值")
            put("additionalProperties", true)
        },
        "reason" to schemaField("string", "action=cancel 的原因（无通道 / 超时 / 客户端报错）"),
        "channelAvailable" to schemaField("boolean", "发起时客户端是否已建立服务端消息通道"),
        required = listOf("action", "channelAvailable"),
    )

    // 问人不是查询：同参数重复调用会再弹一次窗，绝不能缓存
    override val cacheable = false

    // 不改本地数据，但结果取决于人——不是纯分析，也与外部实体交互
    override val readOnly = false
    override val openWorld = true

    override fun invoke(args: JsonObject): JsonObject {
        val message = args.string("message")
            ?: throw IllegalArgumentException("缺少 message：需要一句话说明要问用户什么、为什么需要")
        val fields = (args["fields"] as? JsonObject)?.also { validateFields(it) }
        val required = (args["required"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
        val timeoutMs = (args.int("timeoutMs")?.toLong() ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        val requestedSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                fields ?: buildJsonObject {
                    put(
                        "value",
                        buildJsonObject {
                            put("type", "string")
                            put("title", "回答")
                        },
                    )
                },
            )
            val requiredNames = required ?: fields?.keys?.toList().orEmpty()
            if (requiredNames.isNotEmpty()) {
                put("required", JsonArray(requiredNames.map { JsonPrimitive(it) }))
            }
        }

        val sessionId = McpSessionContext.current()
        // 发起前记下通道状态：之后连接断开也不影响「当时客户端是否支持」这一事实
        val channelAvailable = broker.hasChannel(sessionId)

        // ---- T-077 两轮协议：followUp 先行校验（先验参再提问，不问出口才报错）----
        val followUpSpec = args["followUp"] as? JsonObject
        var followUpMessage: String? = null
        var followUpSchema: JsonObject? = null
        if (followUpSpec != null) {
            followUpMessage = followUpSpec.string("message")
                ?: throw IllegalArgumentException(
                    com.stragent.core.i18n.Messages.get("elicit.followup_missing_message"),
                )
            // followUp 的字段结构与第一轮同一套校验（平铺原始类型，缺省自由文本 value）
            val followUpFields = (followUpSpec["fields"] as? JsonObject)?.also { validateFields(it) }
            val followUpRequired = (followUpSpec["required"] as? JsonArray)?.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
            followUpSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    followUpFields ?: buildJsonObject {
                        put(
                            "value",
                            buildJsonObject {
                                put("type", "string")
                                put("title", com.stragent.core.i18n.Messages.get("elicit.answer_title"))
                            },
                        )
                    },
                )
                val names = followUpRequired ?: followUpFields?.keys?.toList().orEmpty()
                if (names.isNotEmpty()) {
                    put("required", JsonArray(names.map { JsonPrimitive(it) }))
                }
            }
        }

        var outcome = broker.request(sessionId, message, requestedSchema, timeoutMs)
        var rounds = 1
        var firstContent: JsonObject? = null
        if (followUpSchema != null && followUpMessage != null && outcome.action == ElicitAction.ACCEPT) {
            // 第一轮 accept 才续问；复用同一 pending id（回包按 id 路由，客户端无需感知）
            firstContent = outcome.content
            outcome = broker.request(
                sessionId,
                followUpMessage,
                followUpSchema,
                timeoutMs,
                relatedId = outcome.requestId,
            )
            rounds = 2
        }

        return buildJsonObject {
            put("action", outcome.action.wire)
            outcome.content?.let { put("content", it) }
            outcome.reason?.let { put("reason", it) }
            put("channelAvailable", channelAvailable)
            if (rounds == 2) {
                put("rounds", rounds)
                firstContent?.let { put("firstContent", it) }
            }
        }
    }

    /**
     * 校验字段 Schema：MCP elicitation 只允许**平铺的原始类型**（string/number/integer/boolean，
     * 可带 `enum`），嵌套对象/数组客户端无法渲染。宁可当场报错，也不要发出去被客户端丢弃。
     */
    private fun validateFields(fields: JsonObject) {
        fields.forEach { (name, schema) ->
            val field = schema as? JsonObject
                ?: throw IllegalArgumentException("fields.$name 必须是对象（{type: …}）")
            val type = (field["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("fields.$name 缺 type（string/number/integer/boolean）")
            require(type in ELICIT_PRIMITIVE_TYPES) {
                "fields.$name 的 type=$type 不受支持：elicitation 只接受 ${ELICIT_PRIMITIVE_TYPES.joinToString("/")}"
            }
        }
    }
}

/** elicitation 只接受的平铺字段类型（嵌套对象/数组客户端无法渲染） */
private val ELICIT_PRIMITIVE_TYPES = setOf("string", "number", "integer", "boolean")

private const val DEFAULT_TIMEOUT_MS = 60_000L
private const val MIN_TIMEOUT_MS = 1_000L
private const val MAX_TIMEOUT_MS = 300_000L

