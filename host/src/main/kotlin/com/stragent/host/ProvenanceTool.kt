package com.stragent.host

import com.stragent.core.ports.AgentTool
import com.stragent.core.ports.schemaArrayField
import com.stragent.core.ports.schemaField
import com.stragent.core.ports.schemaObject
import com.stragent.core.trace.ProvenanceManifest
import com.stragent.data.AbilityDatasets
import com.stragent.data.McpToolNames
import com.stragent.data.ProvenanceRecorder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 溯源清单工具 —— 把「这份结论是怎么来的」摊开给外部 agent 看，并**当场重放复核**。
 *
 * 这是 D10 §阶段2「溯源 manifest（强制）」的**前半段落地**：先不跑沙箱里的任意代码，
 * 但策展分析链本来就是确定性的，把它记成清单 + 重放，就能回答：
 *  ①用的是**哪份数据**（datasetHash；数据换了，哈希就变，旧结论自动作废）；
 *  ②中间**调了哪些工具、传了什么参数**（steps 里逐条带参��与哈希）；
 *  ③**重放一遍结果是否一致**（resultHash 逐条比对）。
 *
 * 等阶段 2 后半段接入受控代码执行时，同一套协议直接覆盖「模型写的代码」——
 * 那时 `env` / `deps` / `seed` 字段才有意义，**协议本身不变**。
 *
 * 重放范围：只重放**确定性**的数据与分析工具；涉及模型调用的（生成报告 / 追问）标为「不重放」。
 */
fun provenanceTool(
    recorder: ProvenanceRecorder,
    datasets: AbilityDatasets,
    replayTools: List<AgentTool>,
): AgentTool = object : AgentTool {
    override val name = "stragent_provenance"
    override val description =
        "取回本次分析的**溯源清单**并当场重放复核：用了哪份数据（datasetHash）、" +
        "调了哪些工具与参数（steps）、重放一遍结果是否一致（replay）。" +
        "用于回答「这个结论的数据与过程可不可复核」。"
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "datasetId",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "数据集编号（缺省取会话当前数据集）")
                    },
                )
            },
        )
    }

    override val cacheable = false

    /** T-053：返回结构（清单 + 当场重放结果）；T-073：步骤带 numTokens（数值面，可缺失=旧步骤） */
    override val outputSchema = schemaObject(
        "datasetId" to schemaField("string"),
        "datasetHash" to schemaField("string"),
        "datasetHashMatches" to schemaField("boolean"),
        "datasetStable" to schemaField("boolean"),
        "engine" to schemaField("string"),
        "createdAt" to schemaField("integer"),
        "stepCount" to schemaField("integer"),
        "steps" to schemaArrayField(
            schemaObject(
                "tool" to schemaField("string"),
                "argsHash" to schemaField("string"),
                "resultHash" to schemaField("string"),
                "elapsedMs" to schemaField("integer"),
                "sessionId" to schemaField("string"),
                "sqlHash" to schemaField("string"),
                "sqlMasked" to schemaField("string"),
                "numTokens" to schemaArrayField(schemaField("string")),
                "datasetAsOf" to schemaField("integer"),
                required = listOf("tool", "argsHash", "resultHash", "elapsedMs"),
            ),
        ),
        "replay" to schemaArrayField(
            schemaObject(
                "tool" to schemaField("string"),
                "ok" to schemaField("boolean"),
                "detail" to schemaField("string"),
                required = listOf("tool", "ok", "detail"),
            ),
        ),
        "verdict" to schemaField("string"),
        required = listOf("datasetId", "datasetHash", "datasetHashMatches", "steps", "replay", "verdict"),
    )

    override fun invoke(args: JsonObject): JsonObject {
        val datasetId = args.string("datasetId") ?: datasets.currentId()
            ?: throw IllegalArgumentException("尚无数据集：请先调用 stragent_import_table")
        val manifest = recorder.manifest(datasetId)
            ?: throw IllegalArgumentException(
                "该数据集还没有分析留痕：请先调用分析工具（如 stragent_session_overview / stragent_session_group_aggregate）",
            )
        val dataset = datasets.byId(datasetId)
        val currentHash = dataset?.let { ProvenanceManifest.hashOfDataset(it) }
        val datasetHashMatches = currentHash == manifest.datasetHash

        val replaySteps = manifest.steps.map { step ->
            // T-053/T-068：溯源统一记规范名；重放匹配只认规范名
            val tool = replayTools.find { McpToolNames.matches(it.name, step.tool) }
            when {
                tool == null -> buildJsonObject {
                    put("tool", step.tool)
                    put("ok", false)
                    put("detail", "工具已不在当前工具面，无法重放")
                }

                else -> {
                    val replayed = runCatching { tool.invoke(step.args) }.getOrNull()
                    val hash = replayed?.let { ProvenanceManifest.hashOf(it) }
                    val same = hash == step.resultHash
                    buildJsonObject {
                        put("tool", step.tool)
                        put("ok", same)
                        put("detail", if (same) "重放结果一致" else "重放结果与记录不一致（数据或工具行为已变）")
                    }
                }
            }
        }
        val allOk = datasetHashMatches && replaySteps.all { (it["ok"] as? JsonPrimitive)?.content == "true" }

        return buildJsonObject {
            put("datasetId", manifest.datasetId)
            put("datasetHash", manifest.datasetHash)
            put("datasetHashMatches", datasetHashMatches)
            put("datasetStable", manifest.datasetStable)
            put("engine", manifest.engine)
            put("createdAt", manifest.createdAt)
            put("stepCount", manifest.steps.size)
            // T-205：报告数据时点声明（数据集名 + 调用时捕获的导入时点）——结论基于该时点数据
            dataset?.let {
                put("datasetName", it.name)
            }
            manifest.steps.firstNotNullOfOrNull { s -> s.datasetAsOf }?.let { put("datasetAsOf", it) }
            put(
                "steps",
                JsonArray(
                    manifest.steps.map { step ->
                        buildJsonObject {
                            put("tool", step.tool)
                            put("argsHash", step.argsHash)
                            put("resultHash", step.resultHash)
                            put("elapsedMs", step.elapsedMs)
                            // T-046：SQL 只给结构与原文哈希，不把业务数据摊给外部 agent
                            step.sessionId?.let { put("sessionId", it) }
                            step.sqlHash?.let { put("sqlHash", it) }
                            step.sqlMasked?.let { put("sqlMasked", it) }
                            // T-073：数值面（可缺失 = 旧步骤）——追问归因的锚点，原样可见可审计
                            step.numTokens?.let {
                                put("numTokens", JsonArray(it.map { t -> JsonPrimitive(t) }))
                            }
                            // T-205：调用时点捕获的数据时点（可缺失 = 旧步骤），时效标注非有效性判断
                            step.datasetAsOf?.let { put("datasetAsOf", it) }
                        }
                    },
                ),
            )
            put("replay", JsonArray(replaySteps))
            // T-205：判定句带数据时点（有时点才带；标注口径「结论基于该时点数据」）
            val asOf = manifest.steps.firstNotNullOfOrNull { s -> s.datasetAsOf }
            put(
                "verdict",
                if (allOk) {
                    if (asOf != null) "可复核：数据一致且重放结果一致（结论基于数据时点 ${asOf} 导入的数据）"  // lint:allow zh-first（T-205 判定句时效标注，D19/D21 语言定位）
                    else "可复核：数据一致且重放结果一致"  // lint:allow zh-first（T-205 判定句分支，D19/D21 语言定位）
                } else "不可复核：见 replay 明细",
            )
        }
    }
}
