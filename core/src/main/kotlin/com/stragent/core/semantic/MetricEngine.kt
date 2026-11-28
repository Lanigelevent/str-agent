package com.stragent.core.semantic

import com.stragent.core.compute.ExpressionEvaluator
import com.stragent.core.dataset.Dataset
import com.stragent.core.stats.findMonthIndex
import com.stragent.core.stats.monthKey

/**
 * 指标编译器 —— 把 [MetricDef] 编译到现有的受控求值器上（T-055 / AD-031）。
 *
 * 编译分三步，每一步都可能**诚实地失败**：
 *  1. **字段解析**：表达式里的每个标识符要么是定义里声明的规范字段（按别名表找到真实列），
 *     要么是真实列名本身。找不到 / 多个列都像 / 两个字段指向同一列 → 返回
 *     [Resolution.Unresolved] 并说明原因，**绝不猜一个相近的列**。
 *  2. **表达式编译**：裸表达式按定义里的聚合方式包装（`销售额` + `sum` →
 *     `sum(销售额)`）；已经是聚合表达式的原样使用。
 *  3. **求值 + 自检**：在「只投影本指标需要的列」的视图上求值——不修改原数据集、
 *     不落盘、不调模型；结果必须是有限数值，且通过定义里的全部测试才算可用。
 *
 * 为什么不用 DuckDB / dbt：指标是**口径**不是**模型**。这里的表达式只读当前数据集，
 * 同一份数据任何时候都得到同一个数；tier-2 的列式引擎服务的是探索，不是口径治理。
 */
object MetricEngine {

    /** 与 [ExpressionEvaluator] 保持同一份聚合白名单（两处漂移会被编译测试抓住） */
    val AGGREGATES = setOf("sum", "avg", "min", "max", "count", "median", "p25", "p75", "distinct")

    private val AGGREGATE_CALL = Regex("(?i)\\b(sum|avg|min|max|count|median|p25|p75|distinct)\\s*\\(")

    /** 表达式里可出现的标识符（与求值器词法同口径：字母/汉字/下划线开头） */
    private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

    /** 列名里的「率/占比」类后缀：规范字段不是比率时，包含匹配不落到这些列上 */
    private val RATIO_LIKE = Regex("(率|占比|比例|百分比)$")

    /** 列名里的限定词前缀：规范字段本身不带时，包含匹配不落到这些列上 */
    private val QUALIFIER_LIKE = Regex("^(目标|计划|预算|预计|同比|环比)")

    /** 列名末尾常见的单位装饰（`销售额（元）` → `销售额`） */
    private val UNIT_SUFFIX = Regex("(元|万元|亿元|千元|个|件|台|单|次|人|天|%|％)$")

    private val BRACKETS = Regex("[（(][^）)]*[）)]")

    // ---------- 公开类型 ----------

    /** 一个规范字段落到的真实列 */
    data class FieldBinding(val canonical: String, val column: String, val columnIndex: Int)

    /** 字段解析结果 */
    sealed interface Resolution {
        data class Resolved(val bindings: List<FieldBinding>, val compiled: String) : Resolution
        data class Unresolved(val reason: String) : Resolution
    }

    /** 单条自检结果 */
    data class TestOutcome(val kind: String, val passed: Boolean, val detail: String)

    /** 指标在整份数据上的结果 */
    data class MetricResult(
        val metricId: String,
        val name: String,
        val value: Double,
        val unit: String,
        val rows: Int,
        /** 实际执行的表达式（写进回包，口径可核对） */
        val compiled: String,
        /** 规范字段 → 真实列 */
        val fields: Map<String, String>,
        val tests: List<TestOutcome>,
    ) {
        val passed: Boolean get() = tests.all { it.passed }
    }

    /** 分组/分时结果里的一格 */
    data class MetricValue(val label: String, val value: Double, val rows: Int)

    /** 分组或分时求值结果 */
    data class GroupedResult(
        val metric: MetricDef,
        val dimension: String?,
        val grain: String,
        val total: MetricResult,
        val values: List<MetricValue>,
        /** 因 topN 未列出的分组数 */
        val omitted: Int,
    )

    /** 两个同名（或别名相交）但口径不同的定义 */
    data class MetricConflict(val name: String, val left: String, val right: String, val reason: String)

    /** 内置 + 用户定义合并后的目录 */
    data class Catalog(val metrics: List<MetricDef>, val conflicts: List<MetricConflict>)

    // ---------- 编译 ----------

    /**
     * 把定义解析到具体数据集。
     *
     * @return [Resolution.Resolved] 带「规范字段 → 真实列」映射与实际编译后的表达式；
     *         失败时返回 [Resolution.Unresolved]（原因面向用户）
     */
    fun resolve(def: MetricDef, dataset: Dataset): Resolution {
        val expression = def.expression.trim()
        if (expression.isEmpty()) return Resolution.Unresolved("指标「${def.name}」的表达式为空")
        val tokens = IDENTIFIER.findAll(expression)
            .map { it.value }
            .filter { it.lowercase() !in AGGREGATES }
            .distinct()
            .toList()
        if (tokens.isEmpty()) return Resolution.Unresolved("指标「${def.name}」的表达式没有引用任何字段")

        val bindings = mutableListOf<FieldBinding>()
        for (token in tokens) {
            val declared = def.fields[token] != null
            val aliases = def.fields[token] ?: listOf(token)
            val column = pickColumn(token, aliases, dataset.columns)
                ?: return Resolution.Unresolved(
                    if (declared) {
                        "字段「$token」在数据列中找不到匹配（别名：${aliases.joinToString("、")}）"
                    } else {
                        "表达式里的「$token」既不是指标声明的字段，也不是数据列名"
                    },
                )
            bindings.add(FieldBinding(token, column, dataset.columns.indexOf(column)))
        }
        // 两个字段落到同一列：口径一定写错了（如「销售额」与「目标销售额」同列），宁可不用
        for (i in bindings.indices) {
            for (j in i + 1 until bindings.size) {
                if (bindings[i].columnIndex == bindings[j].columnIndex) {
                    return Resolution.Unresolved(
                        "字段「${bindings[i].canonical}」与「${bindings[j].canonical}」都指向列「${bindings[i].column}」，口径有歧义",
                    )
                }
            }
        }

        val agg = def.agg.lowercase()
        if (agg !in AGGREGATES) return Resolution.Unresolved("不支持的聚合方式：${def.agg}")
        val compiled = if (AGGREGATE_CALL.containsMatchIn(expression)) expression else "$agg($expression)"
        return Resolution.Resolved(bindings, compiled)
    }

    /** 只求总值。字段解析失败/结果非数值时抛 [IllegalArgumentException]（原因面向用户）。 */
    fun evaluate(def: MetricDef, dataset: Dataset): MetricResult {
        val resolved = resolve(def, dataset)
        if (resolved is Resolution.Unresolved) throw IllegalArgumentException(resolved.reason)
        val concrete = resolved as Resolution.Resolved
        val value = evalCompiled(concrete, dataset, dataset.rows.indices.toList(), def.name)
        return MetricResult(
            metricId = def.id,
            name = def.name,
            value = value,
            unit = def.unit,
            rows = dataset.rows.size,
            compiled = concrete.compiled,
            fields = concrete.bindings.associate { it.canonical to it.column },
            tests = runTests(def.tests, value),
        )
    }

    /**
     * 分组或分时求值：`groupBy` 走维度分组（按数值降序取 topN），否则按 [grain] 分时
     * （按时间升序全量返回）。
     *
     * @throws IllegalArgumentException 字段/维度解析失败或时间列不存在
     */
    fun evaluateGrouped(
        def: MetricDef,
        dataset: Dataset,
        groupBy: String? = null,
        grain: String? = null,
        topN: Int = 8,
    ): GroupedResult {
        require(topN > 0) { "topN 必须为正数" }
        val total = evaluate(def, dataset)
        val resolved = resolve(def, dataset) as Resolution.Resolved

        if (groupBy != null) {
            val dimIndex = resolveDimension(def, dataset, groupBy)
            val buckets = LinkedHashMap<String, MutableList<Int>>()
            for (index in dataset.rows.indices) {
                val label = (dataset.rows[index].getOrNull(dimIndex) ?: "").trim().ifEmpty { "（空）" }
                buckets.getOrPut(label) { mutableListOf() }.add(index)
            }
            val evaluated = buckets.map { (label, rows) ->
                MetricValue(label, evalCompiled(resolved, dataset, rows, "${def.name}·$label"), rows.size)
            }.sortedByDescending { it.value }
            return GroupedResult(
                metric = def,
                dimension = dataset.columns[dimIndex],
                grain = GRAIN_TOTAL,
                total = total,
                values = evaluated.take(topN),
                omitted = maxOf(0, evaluated.size - topN),
            )
        }

        val effective = (grain ?: def.grain).lowercase()
        if (effective !in METRIC_GRAINS) throw IllegalArgumentException("不支持的粒度：$effective（可用：${METRIC_GRAINS.joinToString("、")}）")
        if (effective == GRAIN_TOTAL) {
            return GroupedResult(
                metric = def,
                dimension = null,
                grain = GRAIN_TOTAL,
                total = total,
                values = listOf(MetricValue("总计", total.value, total.rows)),
                omitted = 0,
            )
        }

        val dateIndex = findMonthIndex(dataset)
        if (dateIndex < 0) throw IllegalArgumentException("数据里没有可识别的日期列，无法按 $effective 粒度求值")
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        for (index in dataset.rows.indices) {
            val month = monthKey(dataset.rows[index].getOrNull(dateIndex) ?: "") ?: continue
            val label = when (effective) {
                GRAIN_MONTH -> month
                GRAIN_QUARTER -> {
                    val year = month.substring(0, 4)
                    val quarter = (month.substring(5, 7).toInt() - 1) / 3 + 1
                    "$year-Q$quarter"
                }
                else -> month.substring(0, 4)
            }
            buckets.getOrPut(label) { mutableListOf() }.add(index)
        }
        if (buckets.isEmpty()) throw IllegalArgumentException("日期列「${dataset.columns[dateIndex]}」没有可解析的年月值")
        val evaluated = buckets.entries.sortedBy { it.key }.map { (label, rows) ->
            MetricValue(label, evalCompiled(resolved, dataset, rows, "${def.name}·$label"), rows.size)
        }
        return GroupedResult(def, dataset.columns[dateIndex], effective, total, evaluated, 0)
    }

    // ---------- 自检与冲突 ----------

    /** 按定义里的测试逐条检查（value 已是最终数值） */
    fun runTests(tests: List<MetricTest>, value: Double): List<TestOutcome> = tests.map { test ->
        val lower = test.value
        val upper = test.upper
        val passed = when (test.kind) {
            "nonNegative" -> value >= 0
            "notZero" -> value != 0.0
            "min" -> lower != null && value >= lower
            "max" -> lower != null && value <= lower
            "range" -> lower != null && upper != null && value in lower..upper
            else -> false
        }
        val detail = when (test.kind) {
            "nonNegative" -> "应 ≥ 0"
            "notZero" -> "应 ≠ 0"
            "min" -> "应 ≥ ${test.value}"
            "max" -> "应 ≤ ${test.value}"
            "range" -> "应在 ${test.value} ~ ${test.upper}"
            else -> "未知测试：${test.kind}"
        }
        val suffix = if (test.note.isNotEmpty()) "（${test.note}）" else ""
        TestOutcome(test.kind, passed, "$detail$suffix")
    }

    /** 静态校验（定义入库前用）：返回问题清单，空 = 合法 */
    fun validate(def: MetricDef): List<String> {
        val problems = mutableListOf<String>()
        if (def.id.isBlank()) problems.add("id 不能为空")
        if (def.name.isBlank()) problems.add("指标名不能为空")
        if (def.expression.isBlank()) problems.add("表达式不能为空")
        if (def.agg.lowercase() !in AGGREGATES) problems.add("聚合方式不支持：${def.agg}（可用：${AGGREGATES.joinToString("、")}）")
        if (def.grain.lowercase() !in METRIC_GRAINS) problems.add("粒度不支持：${def.grain}（可用：${METRIC_GRAINS.joinToString("、")}）")
        for (test in def.tests) {
            if (test.kind !in MetricTest.KINDS) problems.add("测试类型不支持：${test.kind}")
            if ((test.kind == "min" || test.kind == "max") && test.value == null) problems.add("测试 ${test.kind} 缺少数值")
            if (test.kind == "range" && (test.value == null || test.upper == null)) problems.add("测试 range 缺少上下界")
        }
        return problems
    }

    /** 两个定义是否同名或别名相交 */
    fun sharesName(a: MetricDef, b: MetricDef): Boolean {
        val left = setOf(a.key) + a.aliases.map(::normalizeMetricName)
        val right = setOf(b.key) + b.aliases.map(::normalizeMetricName)
        return left.any { it in right }
    }

    /**
     * 口径签名：**编译后**的表达式（去空白）+ 聚合 + 单位 + 粒度；相同 = 同一口径的重复定义。
     *
     * 裸表达式先按聚合方式包装再比较——`销售额` + `sum` 与 `sum(销售额)` 是同一个口径，
     * 若按原文比较，同一口径换个写法就会被误判成冲突（写入口会拒收正确的重复定义）。
     */
    fun signature(def: MetricDef): String {
        val raw = def.expression.replace(Regex("\\s+"), "")
        val compiled = if (AGGREGATE_CALL.containsMatchIn(raw)) raw else "${def.agg.lowercase()}($raw)"
        return compiled + "|" + def.agg.lowercase() + "|" +
            def.unit.trim() + "|" + def.grain.lowercase()
    }

    /** 找出同名/别名相交但口径不同的定义对（跨内置与用户、用户与用户） */
    fun findConflicts(defs: List<MetricDef>): List<MetricConflict> {
        val conflicts = mutableListOf<MetricConflict>()
        for (i in defs.indices) {
            for (j in i + 1 until defs.size) {
                val a = defs[i]
                val b = defs[j]
                if (!sharesName(a, b)) continue
                if (signature(a) == signature(b)) continue
                conflicts.add(
                    MetricConflict(
                        name = b.name,
                        left = a.name,
                        right = b.name,
                        reason = "同名/别名相交但口径不同：${a.expression}（${a.agg}, ${a.unit}）vs ${b.expression}（${b.agg}, ${b.unit}）",
                    ),
                )
            }
        }
        return conflicts
    }

    /**
     * 合并内置目录与用户定义：内置优先；用户定义与已有定义同名但口径不同 →
     * 进 [Catalog.conflicts] 且**不生效**（错的口径不能静默覆盖正确的那条）；
     * 同名同口径的重复定义直接忽略（不产生第二份）。
     */
    fun resolveCatalog(
        user: List<MetricDef>,
        builtins: List<MetricDef> = MetricCatalog.BUILTINS,
    ): Catalog {
        val kept = builtins.toMutableList()
        val conflicts = mutableListOf<MetricConflict>()
        for (def in user) {
            val existing = kept.firstOrNull { sharesName(it, def) }
            if (existing != null) {
                if (signature(existing) != signature(def)) {
                    conflicts.add(
                        MetricConflict(
                            name = def.name,
                            left = existing.name,
                            right = def.name,
                            reason = "与已有定义「${existing.name}」同名/别名相交但口径不同：" +
                                "${existing.expression}（${existing.agg}, ${existing.unit}）vs ${def.expression}（${def.agg}, ${def.unit}）",
                        ),
                    )
                }
                continue
            }
            kept.add(def.copy(builtin = false))
        }
        return Catalog(kept, conflicts)
    }

    // ---------- 内部实现 ----------

    private fun evalCompiled(
        resolved: Resolution.Resolved,
        dataset: Dataset,
        rowIndices: List<Int>,
        label: String,
    ): Double {
        val view = projected(dataset, resolved.bindings, rowIndices)
        val value = try {
            ExpressionEvaluator.evaluateValue(resolved.compiled, view)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("指标「$label」求值失败：${e.message}")
        }
        val number = when (value) {
            is ExpressionEvaluator.Value.Num -> value.value
            is ExpressionEvaluator.Value.Text -> throw IllegalArgumentException("指标「$label」的结果是文本（${value.value}），不是数值")
            ExpressionEvaluator.Value.Missing -> throw IllegalArgumentException("指标「$label」的结果为空（可能分母为 0 或列里没有可解析的数值）")
        }
        if (!number.isFinite()) throw IllegalArgumentException("指标「$label」的结果不是有限数（$number）")
        return number
    }

    /** 只投影指标需要的列（列名用规范名），行序不变 */
    private fun projected(
        dataset: Dataset,
        bindings: List<FieldBinding>,
        rowIndices: List<Int>,
    ): Dataset = Dataset(
        name = dataset.name,
        importedAt = dataset.importedAt,
        columns = bindings.map { it.canonical },
        rows = rowIndices.map { index ->
            val row = dataset.rows[index]
            bindings.map { row.getOrNull(it.columnIndex) ?: "" }
        },
        columnTypes = bindings.map { dataset.columnTypes[it.columnIndex] },
    )

    /**
     * 列解析：精确（含括号里的单位装饰）→ 别名精确 → 包含匹配（仅长度 ≥3 的别名，
     * 且避开「率/占比」后缀与「目标/计划」前缀的明显误配）。
     * 包含匹配出现多个候选时返回 null（歧义不猜）。
     */
    private fun pickColumn(canonical: String, aliases: List<String>, columns: List<String>): String? {
        val wanted = (listOf(canonical) + aliases)
            .map { normalizeMetricName(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (wanted.isEmpty()) return null

        // 1) 归一精确（去掉列名括号装饰与单位后缀后比较）
        val exact = columns.filter { column ->
            val normalized = normalizeMetricName(column)
            val decorated = normalizeMetricName(stripDecorations(column))
            normalized in wanted || decorated in wanted
        }.distinct()
        if (exact.size == 1) return exact.first()
        if (exact.size > 1) {
            // 多个列同样精确：先看有没有唯一的最短列名（`销售额` 优先于 `销售额(万元)`）
            val shortest = exact.minByOrNull { it.length }!!
            val tied = exact.filter { it.length == shortest.length }
            return if (tied.size == 1) shortest else null
        }

        // 2) 包含匹配（别名 ≥3 个字符才允许，2 字别名太泛）
        val candidates = LinkedHashMap<String, Int>()
        for (column in columns) {
            val normalized = normalizeMetricName(stripDecorations(column))
            var best = 0
            for (alias in wanted) {
                if (alias.length < 3) continue
                if (!normalized.contains(alias)) continue
                if (RATIO_LIKE.containsMatchIn(normalized) && !RATIO_LIKE.containsMatchIn(alias)) continue
                if (QUALIFIER_LIKE.containsMatchIn(normalized) && !QUALIFIER_LIKE.containsMatchIn(alias)) continue
                if (alias.length > best) best = alias.length
            }
            if (best > 0) candidates[column] = best
        }
        if (candidates.isEmpty()) return null
        val longest = candidates.values.max()
        val winners = candidates.filterValues { it == longest }.keys
        return if (winners.size == 1) winners.first() else null
    }

    private fun stripDecorations(column: String): String {
        var text = BRACKETS.replace(column, "").trim()
        repeat(2) {
            text = text.replace(UNIT_SUFFIX, "").trim()
        }
        return text.ifEmpty { column }
    }

    private fun resolveDimension(def: MetricDef, dataset: Dataset, groupBy: String): Int {
        val key = normalizeMetricName(groupBy)
        if (def.dimensions.isEmpty()) {
            val index = dataset.columns.indexOfFirst { normalizeMetricName(it) == key }
            if (index < 0) throw IllegalArgumentException("列「$groupBy」不存在")
            return index
        }
        for (dimension in def.dimensions) {
            val aliases = def.fields[dimension] ?: listOf(dimension)
            val column = pickColumn(dimension, aliases, dataset.columns) ?: continue
            val index = dataset.columns.indexOf(column)
            val hit = normalizeMetricName(column) == key ||
                normalizeMetricName(dimension) == key ||
                aliases.any { normalizeMetricName(it) == key }
            if (hit) return index
        }
        throw IllegalArgumentException(
            "指标「${def.name}」不支持按「$groupBy」分组（可用维度：${def.dimensions.joinToString("、")}）",
        )
    }
}
