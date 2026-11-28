package com.stragent.core.semantic

/**
 * 指标定义（T-055 / AD-031）—— 语义层从「文本备忘」升级为「可编译的口径」。
 *
 * 为什么需要它：
 *   `SemanticTerm` 只是把用户说过的话原样存下来，再按列名注入提示词——模型仍要
 *   自己决定「销售额」怎么算、单位是什么、哪些列参与。同一份数据两天写出的口径
 *   可以不一样，报告里的数字就失去了可比性。
 *
 *   `MetricDef` 把「指标 = 表达式 + 聚合 + 单位 + 粒度 + 维度 + 自检」固化成结构：
 *   表达式编译到现有的受控求值器（[com.stragent.core.compute.ExpressionEvaluator]），
 *   同一份数据、同一个定义，任何一次调用都得到同一个数字；数字还能作为
 *   「已知统计」进入报告摘要的 `<allowed_numbers>`，被 `verifyReport` 放行。
 *
 * 纪律（与 semantic/caliber/memory 同源）：
 *   a) **确定性**：不引入随机、不调用模型；定义里的每个字段都解析到真实列，
 *      解析不了就是「未匹配」，绝不猜一个相近的列；
 *   b) **诚实失配**：字段缺失 / 歧义 / 测试未通过时返回原因，不返回一个看起来
 *      合理的数——错的口径比没有口径更危险；
 *   c) **不接管 dbt**：这里只编译表达式，不做模型、不做血缘、不做调度。
 *
 * 字段说明：
 *  - [expression] 是**字段表达式**：可以写裸表达式（如 `销售额 - 成本`，按 [agg]
 *    包装成 `sum(销售额 - 成本)`），也可以写完整聚合表达式（如
 *    `sum(销售额) / distinct(订单号)`，此时 [agg] 只用于说明口径）。
 *  - [fields] 是「规范字段名 → 别名」映射：同一指标换一份数据、列名不同（中英混写、
 *    加单位后缀）也能落回同一列；没有映射的标识符按**字面列名**解析。
 *  - [dimensions] 是允许的分组维度（规范名，别名同样走 [fields]）；空 = 不限制。
 *  - [grain] 是时间粒度：`total`（不分组）/ `month` / `quarter` / `year`。
 *  - [tests] 是定义自检：结果不满足时该指标**不进入报告摘要**，而不是静默引用。
 *  - [version] / [owner] 是口径治理信息：修改一次 +1，owner 标明谁负责。
 */
data class MetricDef(
    /** 稳定标识（如 `metric_sales`）；同名更新复用同一 id */
    val id: String,
    /** 指标名（如「销售额」），归一后是唯一键 */
    val name: String,
    /** 别名（如 `revenue`、`销售金额`），用于按指标名查找与冲突检测 */
    val aliases: List<String> = emptyList(),
    /** 人读口径说明（一句话，进报告摘要与工具回包） */
    val definition: String = "",
    /** 字段表达式（裸表达式或完整聚合表达式） */
    val expression: String,
    /** 聚合方式：sum / avg / min / max / count / median / p25 / p75 / distinct */
    val agg: String = "sum",
    /** 单位（元 / 件 / 单 / % …）；空 = 无量纲 */
    val unit: String = "",
    /** 允许的分组维度（规范名） */
    val dimensions: List<String> = emptyList(),
    /** 时间粒度：total / month / quarter / year */
    val grain: String = GRAIN_TOTAL,
    /** 定义自检（结果不满足 → 不进报告摘要） */
    val tests: List<MetricTest> = emptyList(),
    /** 版本号：每次更新 +1（与 SemanticTerm 同纪律，旧值不删） */
    val version: Int = 1,
    /** 负责方：builtin / 用户名 / 团队名 */
    val owner: String = "builtin",
    /** 规范字段名 → 别名（含维度名） */
    val fields: Map<String, List<String>> = emptyMap(),
    /** 是否内置指标（内置指标不允许被同名用户定义覆盖） */
    val builtin: Boolean = false,
) {
    /** 归一后的名字键（冲突检测与查找共用） */
    val key: String get() = normalizeMetricName(name)
}

/** 一条定义自检 */
data class MetricTest(
    /** nonNegative / min / max / range / notZero */
    val kind: String,
    /** min/max/range 的下界（range 的下界与 min 同义） */
    val value: Double? = null,
    /** range 的上界 */
    val upper: Double? = null,
    /** 说明（给人看，失败时一并返回） */
    val note: String = "",
) {
    companion object {
        val KINDS = setOf("nonNegative", "min", "max", "range", "notZero")

        /**
         * 从字符串解析（MCP 工具的参数形态）：
         * `nonNegative`、`notZero`、`min:0`、`max:100`、`range:0:100`。
         */
        fun parse(text: String): MetricTest {
            val parts = text.trim().split(':').map { it.trim() }
            val head = parts.firstOrNull()?.lowercase().orEmpty()
            fun number(index: Int): Double {
                val raw = parts.getOrNull(index)
                    ?: throw IllegalArgumentException("测试「$text」缺少数值（例：min:0、range:0:100）")
                return raw.toDoubleOrNull()
                    ?: throw IllegalArgumentException("测试「$text」的数值不是数字：$raw")
            }
            return when (head) {
                "nonnegative" -> MetricTest("nonNegative")
                "notzero" -> MetricTest("notZero")
                "min" -> MetricTest("min", value = number(1))
                "max" -> MetricTest("max", value = number(1))
                "range" -> MetricTest("range", value = number(1), upper = number(2))
                else -> throw IllegalArgumentException(
                    "不支持的测试：$text（可用：${KINDS.joinToString("、")}）",
                )
            }
        }
    }
}

/** 时间粒度常量 */
const val GRAIN_TOTAL = "total"
const val GRAIN_MONTH = "month"
const val GRAIN_QUARTER = "quarter"
const val GRAIN_YEAR = "year"

val METRIC_GRAINS = setOf(GRAIN_TOTAL, GRAIN_MONTH, GRAIN_QUARTER, GRAIN_YEAR)

/** 指标名归一：小写、去空白/下划线/连字符/间隔号（「客单价」与「客 单价」视为同名） */
fun normalizeMetricName(name: String): String =
    name.trim().lowercase().replace(Regex("[\\s_\\-·]+"), "")

/** 内置指标目录（10 个销售/财务核心指标，全部编译到现有引擎） */
object MetricCatalog {

    /** 指标结果进入报告摘要的上限（再多就把摘要挤爆了） */
    const val MAX_METRICS_IN_BRIEF: Int = 12

    /** 通用维度：内置指标共用一份维度别名表 */
    private val DIM_FIELDS: Map<String, List<String>> = mapOf(
        "地区" to listOf("地区", "区域", "大区", "省份", "城市", "region", "area", "province", "city"),
        "产品" to listOf("产品", "商品", "品类", "类别", "product", "category", "sku"),
        "渠道" to listOf("渠道", "平台", "来源", "channel", "platform", "source"),
        "门店" to listOf("门店", "店铺", "store", "shop"),
    )

    private val DIMENSIONS: List<String> = DIM_FIELDS.keys.toList()

    private val SALES_FIELD = "销售额" to listOf(
        "销售额", "销售金额", "营收", "收入", "销售收入", "销售总额", "营业额", "gmv", "revenue", "sales", "sales_amount",
    )
    private val QTY_FIELD = "销量" to listOf(
        "销量", "销售量", "数量", "件数", "台数", "qty", "quantity", "units", "volume",
    )
    private val ORDER_FIELD = "订单号" to listOf(
        "订单号", "订单编号", "订单id", "order_id", "orderid", "order_no", "order",
    )
    private val COST_FIELD = "成本" to listOf(
        "成本", "销售成本", "总成本", "cost", "total_cost", "cogs",
    )
    private val RETURN_FIELD = "退货额" to listOf(
        "退货额", "退货金额", "退款额", "退款金额", "退货款", "returns", "refund", "return_amount",
    )
    private val TARGET_FIELD = "目标销售额" to listOf(
        "目标销售额", "销售目标", "目标", "目标金额", "target", "sales_target", "quota",
    )

    private fun fieldsOf(vararg measures: Pair<String, List<String>>): Map<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>()
        for (measure in measures) map[measure.first] = measure.second
        map.putAll(DIM_FIELDS)
        return map
    }

    /** 内置指标：顺序即报告摘要里的展示顺序（先规模、后效率、再比率） */
    val BUILTINS: List<MetricDef> = listOf(
        MetricDef(
            id = "metric_sales",
            name = "销售额",
            aliases = listOf("销售金额", "营收", "收入", "销售收入", "销售总额", "营业额", "revenue", "sales"),
            definition = "销售额合计（对销售额列求和）",
            expression = "销售额",
            agg = "sum",
            unit = "元",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("nonNegative", note = "合计为负说明退货/冲销占主导，口径需人工确认")),
            fields = fieldsOf(SALES_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_units",
            name = "销量",
            aliases = listOf("销售量", "数量", "件数", "qty", "quantity"),
            definition = "销量合计（对销量列求和）",
            expression = "销量",
            agg = "sum",
            unit = "件",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("nonNegative")),
            fields = fieldsOf(QTY_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_orders",
            name = "订单量",
            aliases = listOf("订单数", "订单数量", "order_count"),
            definition = "去重订单数（对订单号列去重计数）",
            expression = "distinct(订单号)",
            agg = "distinct",
            unit = "单",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("nonNegative")),
            fields = fieldsOf(ORDER_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_aov",
            name = "客单价",
            aliases = listOf("平均订单金额", "aov", "average_order_value"),
            definition = "销售额合计 ÷ 去重订单数",
            expression = "sum(销售额) / distinct(订单号)",
            agg = "avg",
            unit = "元/单",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("nonNegative")),
            fields = fieldsOf(SALES_FIELD, ORDER_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_unit_price",
            name = "平均单价",
            aliases = listOf("均价", "单价", "平均售价", "unit_price", "asp"),
            definition = "销售额合计 ÷ 销量合计",
            expression = "sum(销售额) / sum(销量)",
            agg = "avg",
            unit = "元/件",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("nonNegative")),
            fields = fieldsOf(SALES_FIELD, QTY_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_gross_profit",
            name = "毛利",
            aliases = listOf("毛利润", "gross_profit"),
            definition = "销售额合计 − 成本合计",
            expression = "sum(销售额) - sum(成本)",
            agg = "sum",
            unit = "元",
            dimensions = DIMENSIONS,
            fields = fieldsOf(SALES_FIELD, COST_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_gross_margin",
            name = "毛利率",
            aliases = listOf("毛利率%", "gross_margin", "gross_margin_rate"),
            definition = "（销售额合计 − 成本合计）÷ 销售额合计 × 100",
            expression = "(sum(销售额) - sum(成本)) / sum(销售额) * 100",
            agg = "avg",
            unit = "%",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("range", value = -100.0, upper = 100.0, note = "正常成本结构下毛利率应在 −100%~100%")),
            fields = fieldsOf(SALES_FIELD, COST_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_cost_rate",
            name = "成本率",
            aliases = listOf("成本占比", "cost_rate"),
            definition = "成本合计 ÷ 销售额合计 × 100",
            expression = "sum(成本) / sum(销售额) * 100",
            agg = "avg",
            unit = "%",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("range", value = 0.0, upper = 1000.0, note = "成本率为负或超过 1000% 说明成本列口径异常")),
            fields = fieldsOf(COST_FIELD, SALES_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_return_rate",
            name = "退货率",
            aliases = listOf("退款率", "return_rate", "refund_rate"),
            definition = "退货额合计 ÷ 销售额合计 × 100",
            expression = "sum(退货额) / sum(销售额) * 100",
            agg = "avg",
            unit = "%",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("range", value = 0.0, upper = 100.0, note = "退货额大于销售额时口径或数据有误")),
            fields = fieldsOf(RETURN_FIELD, SALES_FIELD),
            builtin = true,
        ),
        MetricDef(
            id = "metric_target_rate",
            name = "目标完成率",
            aliases = listOf("完成率", "达成率", "target_rate", "quota_attainment"),
            definition = "销售额合计 ÷ 目标销售额合计 × 100",
            expression = "sum(销售额) / sum(目标销售额) * 100",
            agg = "avg",
            unit = "%",
            dimensions = DIMENSIONS,
            tests = listOf(MetricTest("nonNegative")),
            fields = fieldsOf(SALES_FIELD, TARGET_FIELD),
            builtin = true,
        ),
    )

    /** 按名字/别名查找（归一比较） */
    fun find(name: String, metrics: List<MetricDef> = BUILTINS): MetricDef? {
        val key = normalizeMetricName(name)
        return metrics.firstOrNull { def ->
            def.key == key || def.aliases.any { normalizeMetricName(it) == key }
        }
    }
}
