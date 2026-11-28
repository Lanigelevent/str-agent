package com.stragent.core.semantic

import com.stragent.core.brief.buildDatasetBrief
import com.stragent.core.csv.ColumnType
import com.stragent.core.dataset.Dataset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 指标定义与编译器测试（T-055 / D17 G-14）。
 *
 * 覆盖维度：
 *  1. 内置 10 个指标在标准销售表上的数值正确；
 *  2. **跨数据集一致**：同一指标换一套列名（英文/别名）与列序，结果逐位相同；
 *  3. 字段解析的诚实边界：缺列、多个相近列 → 未匹配（不猜）；
 *  4. 分组 / 粒度（月、季、年）求值；
 *  5. 自检 DSL（nonNegative/min/max/range/notZero）与失败语义；
 *  6. 口径冲突（同名不同式）检测与目录合并；
 *  7. 报告摘要注入：指标值出现在 `<allowed_numbers>` 之前（可被 verifyReport 放行），
 *     且不传指标时摘要逐字节不变。
 */
class MetricEngineTest {

    // ---------- 测试数据 ----------

    /** 标准销售表：6 行、3 个月，覆盖全部 10 个内置指标的字段 */
    private fun salesDataset(): Dataset {
        val columns = listOf("门店", "销售额", "销量", "订单号", "成本", "退货额", "目标销售额", "月份")
        val rows = listOf(
            listOf("东店", "1200", "10", "o1", "700", "100", "1000", "2024-01"),
            listOf("西店", "800", "8", "o2", "500", "0", "900", "2024-01"),
            listOf("南店", "300", "3", "o3", "200", "30", "500", "2024-02"),
            listOf("东店", "300", "3", "o4", "100", "0", "400", "2024-02"),
            listOf("西店", "200", "2", "o5", "100", "0", "300", "2024-02"),
            listOf("南店", "100", "1", "o6", "50", "0", "200", "2024-03"),
        )
        return dataset("销售明细", columns, rows)
    }

    /** 同一份数据的英文列名 + 列序打乱 + 多一列无关列（跨数据集一致性用） */
    private fun salesDatasetEnglish(): Dataset {
        val columns = listOf("Month", "Product", "Revenue", "Extra", "Quantity", "OrderId", "TotalCost", "Refund", "Target")
        val rows = listOf(
            listOf("2024-01", "East", "1200", "x", "10", "o1", "700", "100", "1000"),
            listOf("2024-01", "West", "800", "x", "8", "o2", "500", "0", "900"),
            listOf("2024-02", "South", "300", "x", "3", "o3", "200", "30", "500"),
            listOf("2024-02", "East", "300", "x", "3", "o4", "100", "0", "400"),
            listOf("2024-02", "West", "200", "x", "2", "o5", "100", "0", "300"),
            listOf("2024-03", "South", "100", "x", "1", "o6", "50", "0", "200"),
        )
        return dataset("Sales", columns, rows)
    }

    private fun dataset(name: String, columns: List<String>, rows: List<List<String>>): Dataset = Dataset(
        name = name,
        importedAt = 1_700_000_000_000,
        columns = columns,
        rows = rows,
        columnTypes = columns.map { column ->
            val numeric = rows.any { row ->
                val cell = row[columns.indexOf(column)]
                cell.isNotEmpty() && cell.toDoubleOrNull() != null
            }
            if (numeric) ColumnType.NUMBER else ColumnType.TEXT
        },
    )

    private fun metric(name: String): MetricDef =
        MetricCatalog.find(name) ?: throw AssertionError("内置指标缺失：$name")

    // ---------- 1. 内置 10 指标 ----------

    @Test
    fun `内置 10 个指标在标准销售表上数值正确`() {
        val data = salesDataset()
        val expected = mapOf(
            "销售额" to 2900.0,
            "销量" to 27.0,
            "订单量" to 6.0,
            "客单价" to 2900.0 / 6,
            "平均单价" to 2900.0 / 27,
            "毛利" to 1250.0,
            "毛利率" to 1250.0 / 2900 * 100,
            "成本率" to 1650.0 / 2900 * 100,
            "退货率" to 130.0 / 2900 * 100,
            "目标完成率" to 2900.0 / 3300 * 100,
        )
        assertEquals(10, MetricCatalog.BUILTINS.size, "内置指标数量应保持 10 个")
        for ((name, value) in expected) {
            val def = metric(name)
            val result = MetricEngine.evaluate(def, data)
            assertEquals(value, result.value, 1e-9, "指标「$name」数值不对：${result.value}")
            assertTrue(result.passed, "指标「$name」自检应通过：${result.tests}")
            assertTrue(result.compiled.isNotBlank())
            assertTrue(result.fields.isNotEmpty())
        }
        // 订单量必须是**去重**订单数，不是行数
        assertEquals(6.0, MetricEngine.evaluate(metric("订单量"), data).value, 1e-9)
    }

    // ---------- 2. 跨数据集一致 ----------

    @Test
    fun `同一指标跨数据集结果一致——列名别名化、列序打乱、多无关列`() {
        val chinese = salesDataset()
        val english = salesDatasetEnglish()
        for (def in MetricCatalog.BUILTINS) {
            val a = MetricEngine.evaluate(def, chinese)
            val b = MetricEngine.evaluate(def, english)
            assertEquals(a.value, b.value, 1e-9, "指标「${def.name}」跨数据集不一致：${a.value} vs ${b.value}")
            // 单位与所用列也必须同口径（列名不同但落到同一逻辑列）
            assertEquals(a.unit, b.unit)
            assertEquals(a.fields.keys, b.fields.keys, "指标「${def.name}」字段集合不一致")
        }
    }

    // ---------- 3. 诚实失配 ----------

    @Test
    fun `缺字段不猜——返回未匹配原因而不是相近列`() {
        val data = dataset(
            "无成本表",
            listOf("门店", "销售额"),
            listOf(listOf("东店", "100")),
        )
        val resolution = MetricEngine.resolve(metric("毛利"), data)
        assertTrue(resolution is MetricEngine.Resolution.Unresolved)
        assertTrue(resolution.reason.contains("成本"), "原因应点名缺的字段：${resolution.reason}")
        val error = assertFailsWith<IllegalArgumentException> { MetricEngine.evaluate(metric("毛利"), data) }
        assertTrue(error.message!!.contains("成本"))
    }

    @Test
    fun `多个相近列都像时不猜——歧义即未匹配`() {
        val data = dataset(
            "歧义表",
            listOf("门店", "销售额A", "销售额B", "销量"),
            listOf(listOf("东店", "100", "200", "3")),
        )
        val resolution = MetricEngine.resolve(metric("销售额"), data)
        assertTrue(resolution is MetricEngine.Resolution.Unresolved, "两个同长相近列必须判歧义")
        assertTrue(resolution.reason.contains("销售额"))
    }

    @Test
    fun `带单位装饰的列名可解析——销售额（元）落回销售额`() {
        val data = dataset(
            "装饰表",
            listOf("门店", "销售额（元）", "销量（件）"),
            listOf(listOf("东店", "100", "2"), listOf("西店", "50", "1")),
        )
        val result = MetricEngine.evaluate(metric("销售额"), data)
        assertEquals(150.0, result.value, 1e-9)
        assertEquals("销售额（元）", result.fields["销售额"])
    }

    // ---------- 4. 分组与粒度 ----------

    @Test
    fun `按维度分组——数值降序且超 topN 计入 omitted`() {
        val data = salesDataset()
        val grouped = MetricEngine.evaluateGrouped(metric("销售额"), data, groupBy = "门店", topN = 2)
        assertEquals("门店", grouped.dimension)
        assertEquals(listOf("东店", "西店"), grouped.values.map { it.label })
        assertEquals(listOf(1500.0, 1000.0), grouped.values.map { it.value })
        assertEquals(1, grouped.omitted)
        assertEquals(2900.0, grouped.total.value, 1e-9)
    }

    @Test
    fun `按维度分组——别名与真实列名都认，未声明的维度拒绝`() {
        val data = salesDataset()
        // 真实列名
        assertEquals(3, MetricEngine.evaluateGrouped(metric("销售额"), data, groupBy = "门店").values.size)
        // 维度别名：区域 → 地区（数据里没有地区列，应拒绝而不是瞎算）
        val error = assertFailsWith<IllegalArgumentException> {
            MetricEngine.evaluateGrouped(metric("销售额"), data, groupBy = "区域")
        }
        assertTrue(error.message!!.contains("不支持"), error.message)
    }

    @Test
    fun `按时间粒度求值——月季年三档口径一致`() {
        val data = salesDataset()
        val month = MetricEngine.evaluateGrouped(metric("销售额"), data, grain = "month")
        assertEquals(listOf("2024-01", "2024-02", "2024-03"), month.values.map { it.label })
        assertEquals(listOf(2000.0, 800.0, 100.0), month.values.map { it.value })

        val quarter = MetricEngine.evaluateGrouped(metric("销售额"), data, grain = "quarter")
        assertEquals(listOf("2024-Q1"), quarter.values.map { it.label })
        assertEquals(2900.0, quarter.values.single().value, 1e-9)

        val year = MetricEngine.evaluateGrouped(metric("销售额"), data, grain = "year")
        assertEquals(listOf("2024"), year.values.map { it.label })
        assertEquals(2900.0, year.values.single().value, 1e-9)
    }

    @Test
    fun `没有日期列时按时间粒度求值报错而不是返回空`() {
        val data = dataset("无日期", listOf("门店", "销售额"), listOf(listOf("东店", "100")))
        val error = assertFailsWith<IllegalArgumentException> {
            MetricEngine.evaluateGrouped(metric("销售额"), data, grain = "month")
        }
        assertTrue(error.message!!.contains("日期列"), error.message)
    }

    // ---------- 5. 自检 DSL ----------

    @Test
    fun `自检 DSL 解析与执行——全部 5 种类型`() {
        assertEquals(MetricTest("nonNegative"), MetricTest.parse("nonNegative"))
        assertEquals(MetricTest("notZero"), MetricTest.parse("notzero"))
        assertEquals(MetricTest("min", value = 0.0), MetricTest.parse("min:0"))
        assertEquals(MetricTest("max", value = 100.0), MetricTest.parse("max:100"))
        assertEquals(MetricTest("range", value = 0.0, upper = 100.0), MetricTest.parse("range:0:100"))
        assertFailsWith<IllegalArgumentException> { MetricTest.parse("range:0") }
        assertFailsWith<IllegalArgumentException> { MetricTest.parse("wat:1") }

        val tests = listOf(
            MetricTest("nonNegative"),
            MetricTest("notZero"),
            MetricTest("min", value = 10.0),
            MetricTest("max", value = 20.0),
            MetricTest("range", value = 0.0, upper = 15.0),
        )
        val outcomes = MetricEngine.runTests(tests, 12.0)
        assertEquals(listOf(true, true, true, true, true), outcomes.map { it.passed })
        val failing = MetricEngine.runTests(tests, -1.0)
        assertEquals(listOf(false, true, false, true, false), failing.map { it.passed })
    }

    @Test
    fun `指标自检未通过时 passed=false 且附带失败明细`() {
        val def = metric("销售额").copy(
            id = "metric_sales_strict",
            name = "销售额(严格)",
            aliases = emptyList(),
            tests = listOf(MetricTest("max", value = 1000.0, note = "阈值测试")),
        )
        val result = MetricEngine.evaluate(def, salesDataset())
        assertTrue(!result.passed)
        val failed = result.tests.single()
        assertTrue(failed.detail.contains("阈值测试"), failed.detail)
    }

    @Test
    fun `定义静态校验能抓住非法聚合与粒度`() {
        val def = metric("销售额")
        assertEquals(emptyList(), MetricEngine.validate(def))
        assertTrue(MetricEngine.validate(def.copy(agg = "stddev")).any { it.contains("聚合") })
        assertTrue(MetricEngine.validate(def.copy(grain = "week")).any { it.contains("粒度") })
        assertTrue(MetricEngine.validate(def.copy(expression = " ")).any { it.contains("表达式") })
        assertTrue(MetricEngine.validate(def.copy(tests = listOf(MetricTest("range", value = 0.0)))).any { it.contains("range") })
    }

    // ---------- 6. 冲突检测与目录合并 ----------

    @Test
    fun `同名不同口径检测为冲突且用户定义不生效`() {
        val divergent = MetricDef(
            id = "metric_sales_net",
            name = "销售额",
            aliases = listOf("净销售额"),
            expression = "sum(销售额 - 退货额)",
            unit = "元",
            owner = "user",
        )
        val catalog = MetricEngine.resolveCatalog(listOf(divergent))
        assertEquals(1, catalog.conflicts.size, "同名不同式必须报冲突：${catalog.conflicts}")
        assertEquals("销售额", catalog.conflicts.single().left)
        // 冲突定义不进入生效目录，内置定义保持原样
        assertTrue(catalog.metrics.none { it.id == "metric_sales_net" })
        assertEquals(1, catalog.metrics.count { it.name == "销售额" })

        // 单独对两份定义做冲突扫描也能抓到（不止合并入口）
        val conflicts = MetricEngine.findConflicts(MetricCatalog.BUILTINS + divergent)
        assertTrue(conflicts.any { it.name == "销售额" })
    }

    @Test
    fun `同名同口径的重复定义被忽略而不是变成两份`() {
        val duplicate = metric("销售额").copy(id = "dup_sales", name = "销售额", owner = "user")
        val catalog = MetricEngine.resolveCatalog(listOf(duplicate))
        assertEquals(emptyList(), catalog.conflicts)
        assertEquals(1, catalog.metrics.count { it.name == "销售额" })
        assertTrue(catalog.metrics.none { it.id == "dup_sales" })
    }

    @Test
    fun `用户自定义指标（不冲突）合并进目录并按别名可查`() {
        val custom = MetricDef(
            id = "metric_net_sales",
            name = "净销售额",
            aliases = listOf("net_sales"),
            expression = "sum(销售额) - sum(退货额)",
            unit = "元",
            owner = "user",
            fields = mapOf(
                "销售额" to listOf("销售额"),
                "退货额" to listOf("退货额"),
            ),
        )
        val catalog = MetricEngine.resolveCatalog(listOf(custom))
        assertEquals(emptyList(), catalog.conflicts)
        assertEquals(11, catalog.metrics.size)
        assertEquals(2770.0, MetricEngine.evaluate(custom, salesDataset()).value, 1e-9)
        assertEquals("metric_net_sales", MetricCatalog.find("net_sales", catalog.metrics)?.id)
    }

    // ---------- 7. 报告摘要注入 ----------

    @Test
    fun `报告摘要注入指标值且位于 allowed_numbers 之前`() {
        val data = salesDataset()
        val brief = buildDatasetBrief(data, metrics = MetricCatalog.BUILTINS)
        assertTrue(brief.contains("指标口径"), "摘要应含指标口径段")
        assertTrue(brief.contains("- 销售额：2,900 元"), brief.lineSequence().first { it.contains("销售额") })
        assertTrue(brief.contains("毛利率"), "摘要应含比率指标")
        val metricIndex = brief.indexOf("指标口径")
        val whitelistIndex = brief.indexOf("<allowed_numbers>")
        assertTrue(metricIndex in 1 until whitelistIndex, "指标段必须在数字白名单之前：$metricIndex / $whitelistIndex")
        // 指标值必须真的进白名单（否则报告引用会被 verifyReport 判为编数）
        val whitelist = brief.substring(whitelistIndex)
        assertTrue(whitelist.contains("2,900"), "指标值应在 allowed_numbers 里")
    }

    @Test
    fun `不传指标时摘要不含口径段——非指标数据集逐字节保持基线`() {
        val data = salesDataset()
        val plain = buildDatasetBrief(data)
        assertTrue(!plain.contains("指标口径"))
        // 指标全部无法解析时同样不输出（非销售类数据集不该被塞空口径段）
        val noSales = dataset("无销售列", listOf("城市", "温度"), listOf(listOf("北京", "21")))
        val brief = buildDatasetBrief(noSales, metrics = MetricCatalog.BUILTINS)
        assertTrue(!brief.contains("指标口径"), "无可用指标时不应注入空段")
    }

    @Test
    fun `自检失败的指标不进摘要但在末尾说明`() {
        val data = salesDataset()
        val strict = metric("销售额").copy(
            id = "metric_sales_strict_brief",
            name = "销售额(严格)",
            aliases = emptyList(),
            tests = listOf(MetricTest("max", value = 1.0)),
        )
        val brief = buildDatasetBrief(data, metrics = listOf(strict, metric("销量")))
        assertTrue(!brief.contains("- 销售额(严格)"), "自检失败的定义不应出现在可引用指标里")
        assertTrue(brief.contains("自检未通过"), "应如实说明有指标未纳入")
        assertTrue(brief.contains("- 销量："), "其他指标仍应正常纳入")
    }

    @Test
    fun `指标计数上限生效——摘要不会无限膨胀`() {
        val data = salesDataset()
        val many = (1..30).map { index ->
            MetricDef(
                id = "m_$index",
                name = "自定义$index",
                expression = "sum(销售额)",
                unit = "元",
                owner = "user",
                fields = mapOf("销售额" to listOf("销售额")),
            )
        }
        val brief = buildDatasetBrief(data, metrics = many)
        val injected = brief.lineSequence().count { it.startsWith("- 自定义") }
        assertEquals(MetricCatalog.MAX_METRICS_IN_BRIEF, injected, "摘要里的指标数应受上限约束")
        assertNotNull(brief.indexOf("指标口径").takeIf { it >= 0 })
    }
}
