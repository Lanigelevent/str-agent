package com.stragent.host

import com.stragent.core.config.Config
import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaAnyField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.core.compute.ComputeKind
import com.stragent.core.compute.ExpressionComputeProvider
import com.stragent.data.AbilityDatasets
import com.stragent.core.ports.PrefsStore
import com.stragent.data.SessionRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 受控计算工具 —— 阶段 2「受控代码执行」对外的入口。
 *
 * **默认关闭（人类授权闸）**：只有宿主显式开启（prefs `compute.sandbox.enabled=true`
 * 或环境变量 `STRAGENT_SANDBOX=1`）才可调用；未开启时给中文错误并说明怎么开。
 * 这替代了 UI 时代的「弹窗确认」——插件形态下没有 UI，**授权必须由宿主显式做出**。
 *
 * **能做什么**：受控表达式的算术/比较/聚合，例如
 * `avg(销售额 / 订单数)`（平均客单价）、`median(销售额 - 成本)`、`count(销售额 > 1000)`。
 * **不能做什么**：函数定义、循环、IO、网络、import —— 语法层面就不支持。
 *
 * **每次执行都产出计算清单**（code / inputHash / outputHash / env / seed），
 * 且结果被记进分析链的溯源清单 → 可重放复核（数据变了或代码变了都会被抓到）。
 */
fun computeTool(datasets: AbilityDatasets, prefs: PrefsStore, registry: SessionRegistry? = null): AgentTool = object : AgentTool {
    override val name = "stragent_compute"
    override val description =
        "对当前数据集执行**受控表达式**（默认关闭，需宿主授权）：" +
        "支持算术、比较、列引用与聚合函数 sum/avg/min/max/count/median/p25/p75/distinct，" +
        "例如 avg(销售额 / 订单数)、count(销售额 > 1000)。" +
        "不支持函数定义/循环/IO/网络。每次执行产出计算清单并可重放复核。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "code",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "表达式，如 avg(销售额 / 订单数)")
                    },
                )
                put(
                    "datasetId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "数据集编号（缺省取会话当前数据集）")
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("code"))))
    }

    override val cacheable = false

    /** T-053：返回结构（结果值 + 可重放的确定性清单） */
    override val outputSchema = schemaObject(
        "kind" to schemaField("string", "curated / sandbox"),
        "engineId" to schemaField("string"),
        "value" to schemaAnyField("计算结果（标量/数组/对象）"),
        "manifest" to schemaField("object", "确定性计算清单（不含时间戳，可重放对拍）"),
        required = listOf("kind", "engineId", "value", "manifest"),
    )

    override fun invoke(args: JsonObject): JsonObject {
        if (!computeSandboxAuthorized(prefs)) {
            throw IllegalStateException(
                "受控计算默认关闭：请由宿主显式开启（设置 compute.sandbox.enabled=true，或环境变量 STRAGENT_SANDBOX=1）后重试",
            )
        }
        val code = args.string("code") ?: throw IllegalArgumentException("code 不能为空")
        val datasetId = resolveDatasetId(args, datasets, registry)
        val dataset = datasets.byId(datasetId)
            ?: throw IllegalArgumentException("数据集不存在：$datasetId")

        val outcome = ExpressionComputeProvider.execute(code, dataset)
        return buildJsonObject {
            put("kind", ComputeKind.SANDBOX.id)
            put("engineId", outcome.manifest.engineId)
            put("value", outcome.value)
            // 用**确定性形态**（不含时间戳）：否则同样输入重放的哈希必不同，重放校验永远失败
            put("manifest", outcome.manifest.toStableJson())
        }
    }
}

/** 授权判定：prefs 开关 或 环境变量，任一为真即开（受控表达式与 Python 两档共用同一把闸） */
internal fun computeSandboxAuthorized(prefs: PrefsStore): Boolean {
    if (Config.get("STRAGENT_SANDBOX") == "1") return true
    return prefs.get("compute.sandbox.enabled", "false").trim().equals("true", ignoreCase = true)
}
