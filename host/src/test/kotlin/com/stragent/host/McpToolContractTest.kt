package com.stragent.host

import com.stragent.data.McpToolNames
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MCP 工具面契约测试（T-053 / D17 G-11）。
 *
 * 验证的是**边界契约**而不是工具业务：
 *  1. 53 条注册工具对外一律 `stragent_` 规范名，annotations 100% 且含 5 个字段；
 *  2. 16 个旧裸名的别名 1.1.0 起退役：目录只公布规范名，旧名调用报错并带迁移指引；
 *  3. 输入 schema 结构合法（object / properties / required 有依据 / 字段有类型）；
 *  4. 声明 outputSchema 的工具双发 structuredContent，且与 text 完全一致；
 *  5. 行为提示逐工具声明（写操作不能标只读，代码执行不能标封闭世界）；
 *  6. 数值参数按声明类型读取（负数对照：旧的字符串式解析会静默忽略数字）。
 */
class McpToolContractTest {

    private fun tempHome() = File(System.getProperty("java.io.tmpdir"), "str-agent-contract-" + System.nanoTime())
        .also { it.mkdirs() }

    private fun bootstrap() = StrAgentHostBootstrap.create(tempHome(), llm = FakeLlmClient())

    /** 全量形态（沙箱开启）：prefs 文件预置 compute.sandbox.enabled=true */
    private fun bootstrapFull(): StrAgentHostBootstrap {
        val home = tempHome().apply {
            // 全量形态（53）：沙箱开启 + 视觉就绪 + db 授权（T-155/T-190 后全量覆盖需三就绪）
            java.io.File(this, "prefs.properties").writeText("compute.sandbox.enabled=true\nstepfun.apiKey=sk-test-vision-ready\ntools.db.enabled=true\n")
        }
        return StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
    }

    private fun listTools(b: StrAgentHostBootstrap): JsonArray =
        b.mcpBridge.dispatch("tools/list", JsonObject(emptyMap()))["tools"]!!.jsonArray

    private fun call(b: StrAgentHostBootstrap, name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        b.mcpBridge.dispatch(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", args)
            },
        )

    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun structured(result: JsonObject): JsonObject? = result["structuredContent"]?.jsonObject

    private fun importDataset(b: StrAgentHostBootstrap, csv: String = STORE_CSV, name: String = "门店"): String {
        val r = call(b, "stragent_import_table", buildJsonObject { put("csv", csv); put("name", name) })
        return structured(r)!!["datasetId"]!!.jsonPrimitive.content
    }

    // ---------------- 1. 命名与 annotations ----------------

    @Test
    fun `工具面默认形态 44 条（沙箱未开裁受控计算 2 条 + 视觉未就绪裁 analyze_image + db 未授权裁 db_* 6 条）——规范名与 annotations 齐全`() {
        val b = bootstrap()
        try {
            val tools = listTools(b)
            // T-155/T-190：默认形态 = 53 - 受控计算 2 条 - analyze_image - db_* 6 条（db 未授权）= 44；形态矩阵见后
            assertEquals(44, tools.size, "默认形态工具面数量变化需同步文档快照")

            val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertEquals(names.size, names.toSet().size, "工具名重复：$names")
            names.forEach { name ->
                assertTrue(name.startsWith("stragent_"), "工具面仍暴露裸名：$name")
            }

            tools.forEach { element ->
                val tool = element.jsonObject
                val name = tool["name"]!!.jsonPrimitive.content
                val annotations = tool["annotations"]?.jsonObject
                assertNotNull(annotations, "$name 缺 annotations")
                assertEquals(5, annotations.size, "$name 的 annotations 字段数不对：$annotations")
                assertEquals(name, annotations["title"]!!.jsonPrimitive.content, "$name 的 title 应与工具名一致")
                listOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint").forEach { key ->
                    assertNotNull(
                        (annotations[key] as? JsonPrimitive)?.booleanOrNull,
                        "$name 的 $key 必须是布尔值",
                    )
                }
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun `旧裸名 1_1_0 起退役——调用报错带迁移指引，规范名正常`() {
        val b = bootstrap()
        try {
            importDataset(b)
            // 旧裸名：-32602 且错误信息带规范名指引（T-068 兑现 T-053「一版兼容期后删除」）
            val error = assertFailsWith<com.stragent.data.McpInvalidParamsException> {
                call(b, "get_report_context", buildJsonObject { put("query", "分组") })
            }
            assertTrue(
                error.message!!.contains("stragent_session_report_context"),
                "错误信息应带规范名指引：${error.message}",
            )
            assertTrue(error.message!!.contains("1.1.0"), "错误信息应注明退役版本：${error.message}")

            // 规范名照常命中；映射表中每个内部名都有对应规范工具
            val canonical = call(b, "stragent_session_report_context", buildJsonObject { put("query", "分组") })
            assertTrue(textOf(canonical).isNotEmpty(), "规范名必须正常工作")
            val names = listTools(b).map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            McpToolNames.CANONICAL_NAMES.forEach { (internalName, canonicalName) ->
                assertTrue(canonicalName in names, "映射表里的 $internalName -> $canonicalName 没有对应工具")
            }
        } finally {
            b.close()
        }
    }

    // ---------------- 2. schema 结构 ----------------

    @Test
    fun `输入 schema 结构合法且 required 有依据`() {
        val b = bootstrap()
        try {
            listTools(b).forEach { element ->
                val tool = element.jsonObject
                val name = tool["name"]!!.jsonPrimitive.content
                val schema = tool["inputSchema"]!!.jsonObject
                assertEquals("object", schema["type"]!!.jsonPrimitive.content, "$name 的 inputSchema 必须是 object")
                val properties = schema["properties"]?.jsonObject
                    ?: throw AssertionError("$name 缺 properties")
                // 允许零参数工具（properties 为空），但 required 必须同样为空
                assertTrue(properties.isNotEmpty() || (schema["required"]?.jsonArray?.isEmpty() != false), "$name 无参数却有 required")

                properties.forEach { (propName, propSchema) ->
                    val prop = propSchema.jsonObject
                    assertTrue(
                        prop.containsKey("type") || prop.containsKey("enum"),
                        "$name 的参数 $propName 既没有 type 也没有 enum",
                    )
                }

                val required = schema["required"]?.jsonArray ?: return@forEach
                required.forEach { req ->
                    val key = req.jsonPrimitive.content
                    assertTrue(key in properties, "$name 的 required 含未声明参数：$key")
                }
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun `outputSchema 全面化——所有工具都声明，严格 14 条 + 宽松对象兜底`() {
        val b = bootstrap()
        try {
            val tools = listTools(b).map { it.jsonObject }
            val declared = tools.associate { it["name"]!!.jsonPrimitive.content to it["outputSchema"]!!.jsonObject }
            assertEquals(tools.size, declared.size, "T-056：所有工具都必须声明 outputSchema")

            // 严格 schema = 有具名字段的那些；其余是宽松对象（只承诺返回 JSON 对象）
            val strict = declared.filterValues { it["properties"]?.jsonObject?.isNotEmpty() == true }
            assertEquals(EXPECTED_STRICT, strict.keys, "严格 outputSchema 集合变了，需同步测试与文档")

            declared.forEach { (name, schema) ->
                assertEquals("object", schema["type"]!!.jsonPrimitive.content, "$name 的 outputSchema 必须是 object")
            }
            declared.filterKeys { it !in EXPECTED_STRICT }.forEach { (name, schema) ->
                assertEquals(
                    "true",
                    schema["additionalProperties"]?.jsonPrimitive?.content,
                    "$name 未收紧字段时必须声明 additionalProperties=true（不固化临时字段）",
                )
            }
        } finally {
            b.close()
        }
    }

    // ---------------- 3. structuredContent 双发 ----------------

    @Test
    fun `声明 outputSchema 的工具双发 structuredContent 且与 text 一致`() {
        val b = bootstrap()
        try {
            val datasetId = importDataset(b)
            val results = mutableMapOf<String, JsonObject>()

            results["stragent_list_rows"] = call(
                b,
                "stragent_list_rows",
                buildJsonObject { put("datasetId", datasetId); put("limit", 2) },
            )
            results["stragent_get_column_profile"] = call(
                b,
                "stragent_get_column_profile",
                buildJsonObject { put("datasetId", datasetId); put("column", "销售额") },
            )
            results["stragent_get_group_aggregate"] = call(
                b,
                "stragent_get_group_aggregate",
                buildJsonObject {
                    put("datasetId", datasetId); put("dimension", "门店"); put("metric", "销售额")
                },
            )
            results["stragent_render_chart"] = call(
                b,
                "stragent_render_chart",
                buildJsonObject {
                    put("datasetId", datasetId); put("valueColumn", "销售额"); put("categoryColumn", "门店")
                },
            )
            // T-055：指标求值也声明了 outputSchema——数值/字段映射/自检都要能结构化核对
            results["stragent_evaluate_metric"] = call(
                b,
                "stragent_evaluate_metric",
                buildJsonObject { put("datasetId", datasetId); put("metric", "销售额") },
            )

            results.forEach { (name, result) ->
                val body = structured(result)
                assertNotNull(body, "$name 声明了 outputSchema，却没有 structuredContent")
                assertEquals(
                    Json.parseToJsonElement(textOf(result)),
                    body,
                    "$name 的 structuredContent 与 text 内容必须逐字一致",
                )
            }

            // T-056：宽松契约的工具也双发——客户端拿到的仍是对象，只是字段不固化
            val plain = call(
                b,
                "stragent_session_overview",
                buildJsonObject { put("datasetId", datasetId) },
            )
            val plainBody = structured(plain)
            assertNotNull(plainBody, "宽松 outputSchema 的工具也必须双发 structuredContent")
            assertEquals(Json.parseToJsonElement(textOf(plain)), plainBody, "宽松契约同样要与 text 一致")
        } finally {
            b.close()
        }
    }

    // ---------------- 4. 行为提示逐工具声明 ----------------

    @Test
    fun `annotations 逐工具声明：写操作不标只读、代码执行不标封闭世界`() {
        // 全量形态（沙箱开启 + 视觉就绪）才能覆盖 compute 两档与 analyze_image 的 annotations
        val b = bootstrapFull()
        try {
            val annotations = listTools(b).associate { element ->
                val tool = element.jsonObject
                tool["name"]!!.jsonPrimitive.content to tool["annotations"]!!.jsonObject
            }

            fun readOnly(name: String) = annotations.getValue(name)["readOnlyHint"]!!.jsonPrimitive.booleanOrNull!!
            fun destructive(name: String) = annotations.getValue(name)["destructiveHint"]!!.jsonPrimitive.booleanOrNull!!
            fun openWorld(name: String) = annotations.getValue(name)["openWorldHint"]!!.jsonPrimitive.booleanOrNull!!

            // 改状态/写盘的不能标只读
            listOf(
                "stragent_import_table",
                "stragent_import_file",
                "stragent_import_pdf",
                "stragent_export_report",
                "stragent_compute_python",
                "stragent_generate_report",
                "stragent_ask_followup",
                "stragent_db_connect",
                "stragent_define_metric",
            ).forEach { assertFalse(readOnly(it), "$it 改了状态却标成只读") }

            // 代码执行：破坏性 + 开放世界；导出覆盖文件：破坏性
            assertTrue(destructive("stragent_compute_python"), "执行任意代码必须标破坏性")
            assertTrue(openWorld("stragent_compute_python"), "执行任意代码必须标开放世界")
            assertTrue(destructive("stragent_export_report"), "覆盖写文件必须标破坏性")

            // 模型/数据库是开放世界
            listOf(
                "stragent_generate_report",
                "stragent_ask_followup",
                "stragent_analyze_image",
                "stragent_db_query",
            ).forEach { assertTrue(openWorld(it), "$it 与外部实体交互，应标开放世界") }

            // 问人（T-056 elicitation）：不改本地数据但依赖外部回答——不标只读/不标封闭世界/不得幂等
            val elicit = "stragent_elicit_input"
            assertFalse(readOnly(elicit), "$elicit 依赖外部人回答，不应标只读")
            assertFalse(destructive(elicit), "$elicit 不破坏任何数据")
            assertTrue(openWorld(elicit), "$elicit 与外部人交互，应标开放世界")
            assertFalse(
                annotations.getValue(elicit)["idempotentHint"]!!.jsonPrimitive.booleanOrNull!!,
                "$elicit 重复调用会再弹一次窗，不得标幂等",
            )

            // 纯分析：只读、非破坏、封闭世界
            listOf(
                "stragent_session_overview",
                "stragent_session_group_aggregate",
                "stragent_get_group_aggregate",
                "stragent_forecast",
                "stragent_whatif",
                "stragent_provenance",
                "stragent_evaluate_metric",
            ).forEach {
                assertTrue(readOnly(it), "$it 是纯分析，应标只读")
                assertFalse(destructive(it), "$it 是纯分析，不应标破坏性")
                assertFalse(openWorld(it), "$it 只读本地数据，不应标开放世界")
            }
        } finally {
            b.close()
        }
    }

    // ---------------- 5. schema 与实现同型（数值参数） ----------------

    @Test
    fun `数值参数按声明类型读取（结构化 1 即可区分静默忽略）`() {
        val b = bootstrap()
        try {
            val storeId = importDataset(b)

            // chart topN=1 + 3 个分组 → 1 个明细 + 「其他 2 项」= 2 个点；
            // 如果实现把数字当字符串读，会退回默认 10 → 3 个点（本测试就会红）
            val chart = call(
                b,
                "stragent_render_chart",
                buildJsonObject {
                    put("datasetId", storeId)
                    put("valueColumn", "销售额")
                    put("categoryColumn", "门店")
                    put("topN", 1)
                },
            )
            assertEquals(2, structured(chart)!!["dataPoints"]!!.jsonPrimitive.content.toInt(), "topN 数字参数被忽略")

            // whatif factor 传数字 1.1：旧实现按字符串读会直接报「factor 不能为空」
            val whatIf = call(
                b,
                "stragent_whatif",
                buildJsonObject { put("datasetId", storeId); put("factor", 1.1) },
            )
            val before = structured(whatIf)!!["before"]!!.jsonArray[0].jsonObject["sum"]!!.jsonPrimitive.doubleOrNull!!
            val after = structured(whatIf)!!["after"]!!.jsonArray[0].jsonObject["sum"]!!.jsonPrimitive.doubleOrNull!!
            assertEquals(before * 1.1, after, 1e-6, "factor 数字参数未生效")

            // forecast horizon 传数字 5：旧实现按字符串读会退回默认 3
            val trendId = importDataset(b, TREND_CSV, "趋势")
            val forecast = call(
                b,
                "stragent_forecast",
                buildJsonObject { put("datasetId", trendId); put("horizon", 5) },
            )
            assertEquals(5, structured(forecast)!!["horizon"]!!.jsonPrimitive.content.toInt(), "horizon 数字参数被忽略")
            assertEquals(5, structured(forecast)!!["forecast"]!!.jsonArray.size, "预测期数未按 horizon 生效")
        } finally {
            b.close()
        }
    }

    // ---------------- 6. 别名与命名约束的负控 ----------------

    @Test
    fun `负控：映射表不得把两个规范名指到同一个工具`() {
        val b = bootstrap()
        try {
            val names = listTools(b).map { it.jsonObject["name"]!!.jsonPrimitive.content }
            // 规范名两两不同
            assertEquals(names.size, names.toSet().size)
            // 映射目标两两不同（否则一个请求可能命中两个工具，取决于查找顺序）
            val targets = McpToolNames.CANONICAL_NAMES.values.toList()
            assertEquals(targets.size, targets.toSet().size, "映射表有重复目标：$targets")
            // 规范名不得与任何内部裸名同名（否则 exposed() 会自映射出歧义）
            names.forEach { name ->
                assertFalse(name in McpToolNames.CANONICAL_NAMES.keys, "规范名 $name 与内部裸名重名")
            }
        } finally {
            b.close()
        }
    }

    companion object {
        private val STORE_CSV = """
            门店,销售额,数量
            东店,1200,10
            西店,800,8
            南店,300,3
            东店,300,3
            西店,200,2
            南店,100,1
        """.trimIndent()

        private val TREND_CSV = buildString {
            appendLine("月份,销售额")
            for (i in 0 until 12) {
                appendLine("2024-${(i + 1).toString().padStart(2, '0')},${100 + i * 10 + (i % 3) * 2.5}")
            }
        }.trimIndent()

        /** 严格 outputSchema（有具名字段）的 14 条（T-053 范围 + T-055 指标求值 + T-056 elicitation；新增必须同步这里与文档快照） */
        private val EXPECTED_STRICT = setOf(
            "stragent_import_table",
            "stragent_get_column_profile",
            "stragent_get_group_aggregate",
            "stragent_list_rows",
            "stragent_render_chart",
            "stragent_export_report",
            "stragent_forecast",
            "stragent_whatif",
            "stragent_regression",
            "stragent_hypothesis_test",
            "stragent_provenance",
            "stragent_evaluate_metric",
            "stragent_elicit_input",
        )
    }

    @Test
    fun `负控——沙箱开启形态恢复 46 条（T-155 与 T-190 裁剪只作用于目录；视觉与 db 仍未授权）`() {
        val home = tempHome().apply {
            java.io.File(this, "prefs.properties").writeText("compute.sandbox.enabled=true\n")
        }
        val b = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        try {
            val tools = listTools(b)
            assertEquals(46, tools.size, "沙箱开启 = 53 - analyze_image（视觉未就绪）- db_* 6 条（db 未授权，T-190）")
            assertTrue(
                tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "stragent_compute_python" },
                "compute_python 应随沙箱开启回归工具面",
            )
            assertTrue(
                tools.none { it.jsonObject.getValue("name").jsonPrimitive.content == "stragent_analyze_image" },
                "视觉未就绪时 analyze_image 仍不应下发",
            )
        } finally {
            b.close()
        }
    }

    @Test
    fun `正控——视觉就绪形态 45 条（analyze_image 回归工具面；db 仍未授权）`() {
        val home = tempHome().apply {
            // JvmKeyStore.decrypt 对裸明文恒等回退（遗留口径）——prefs 直写明文即可通过就绪判定
            java.io.File(this, "prefs.properties").writeText("stepfun.apiKey=sk-test-vision-ready\n")
        }
        val b = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        try {
            val tools = listTools(b)
            assertEquals(45, tools.size, "视觉就绪 = 53 - 受控计算 2 条 - db_* 6 条 = 45")
            assertTrue(
                tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "stragent_analyze_image" },
                "视觉就绪后 analyze_image 应回归工具面",
            )
        } finally {
            b.close()
        }
    }

    @Test
    fun `正控——双就绪形态 47 条（沙箱 + 视觉；db 未授权）`() {
        val home = tempHome().apply {
            java.io.File(this, "prefs.properties").writeText(
                "compute.sandbox.enabled=true\nstepfun.apiKey=sk-test-vision-ready\n",
            )
        }
        val b = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        try {
            val tools = listTools(b)
            assertEquals(47, tools.size, "双就绪 = 53 - db_* 6 条（db 未授权，T-190）")
        } finally {
            b.close()
        }
    }

    @Test
    fun `负控——db 未授权时 db_* 6 条不下发（T-190）`() {
        val b = bootstrap()
        try {
            val names = listTools(b).map { it.jsonObject.getValue("name").jsonPrimitive.content }
            listOf(
                "stragent_db_connect",
                "stragent_db_list_tables",
                "stragent_db_query",
                "stragent_db_describe",
                "stragent_db_sample",
                "stragent_db_column_stats",
            ).forEach { db ->
                assertTrue(names.none { it == db }, "db 未授权时 $db 不应出现在默认目录") // lint:allow zh-first（测试断言消息）
            }
        } finally {
            b.close()
        }
    }

    @Test
    fun `正控——db 授权形态 50 条（db_* 6 条回归目录；T-190）`() {
        val home = tempHome().apply {
            java.io.File(this, "prefs.properties").writeText("tools.db.enabled=true\n")
        }
        val b = StrAgentHostBootstrap.create(home, llm = FakeLlmClient())
        try {
            val tools = listTools(b)
            assertEquals(50, tools.size, "db 授权 = 默认 44 + db_* 6 条")
            assertTrue(
                tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "stragent_db_connect" },
                "db 授权后 stragent_db_connect 应回归工具面",
            )
        } finally {
            b.close()
        }
    }
}
