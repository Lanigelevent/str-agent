package com.stragent.engine.duckdb

/**
 * tier-2（DuckDB）的 SQL 片段构造。
 *
 * 核心纪律：**数值口径必须与 core 的 `Csv.parseNumber` 逐条一致**。
 * parseNumber 的规则（见 `core/csv/CsvParser.kt`）：
 *  1. trim 前后空白；
 *  2. 去掉结尾的 `%`（`trimEnd('%')` 会去掉**全部**结尾 %）；
 *  3. 去掉所有空格；
 *  4. 仅当整串匹配标准千分位分组 `^-?\d{1,3}(,\d{3})+(\.\d+)?` 时才去掉逗号，否则原样保留；
 *  5. `toDoubleOrNull`，且必须是有限数（NaN / Infinity 视为脏值 → null）。
 *
 * 下面这套表达式逐条对齐这 5 条。**任何一条对不齐，tier-2 与 tier-1 就会给出两个数**，
 * 直接击穿「数字确定性可溯源」壁垒 —— bench 的 `engine-parity` 套件会当场抓住。
 */
internal object DuckDbSql {

    /** 标准千分位分组（与 Kotlin 侧的正则同款） */
    private const val GROUPING_REGEX = """^-?\d{1,3}(,\d{3})+(\.\d+)?${'$'}"""

    /** 标识符加引号（列名可能是中文/空格/引号） */
    fun quoteIdent(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** 统一按文本读取（列可能是 BIGINT 等类型；数值口径必须走字符串，否则 DuckDB 会自己解析数字） */
    fun asText(column: String): String = "coalesce(CAST(${quoteIdent(column)} AS VARCHAR), '')"

    /** 规则 1-3：规范化后的原始文本 */
    fun normalized(column: String): String {
        val text = asText(column)
        return "replace(rtrim(trim($text), '%'), ' ', '')"
    }

    /** 规则 4-5：数值表达式，不合规 → NULL（与 parseNumber 返回 null 同款） */
    fun numeric(column: String): String {
        val norm = normalized(column)
        val grouped = "TRY_CAST(replace($norm, ',', '') AS DOUBLE)"
        val plain = "TRY_CAST($norm AS DOUBLE)"
        val raw = "CASE WHEN regexp_matches($norm, '$GROUPING_REGEX') THEN $grouped ELSE $plain END"
        return "CASE WHEN isfinite($raw) THEN $raw ELSE NULL END"
    }

    /** 维度标签表达式：空值/空串 → 「（空）」（与 core.dataset.aggregateBy 同款） */
    fun label(column: String): String {
        val trimmed = "trim(${asText(column)})"
        return "CASE WHEN $trimmed = '' THEN '（空）' ELSE $trimmed END"
    }

    /** 非空判定（空串与 NULL 都算缺失） */
    fun nonEmpty(column: String): String = "trim(${asText(column)}) <> ''"
}
