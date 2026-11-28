package com.stragent.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 溯源审计脱敏（T-046 / D17 G-05）。
 *
 * 溯源清单要能回答「用过哪份数据、调过哪些工具」，但不该把**凭据**与**业务数据**
 * 抄进审计：`stragent_db_connect` 的密码、JDBC URL 里内嵌的口令、
 * `stragent_db_query` 的 SQL 字面量，都是泄露面。
 *
 * 纪律：
 *  - 凭据字段（password / secret / token / apiKey …）一律替换为 [REDACTED]，
 *    键名先归一（大小写、下划线、连字符不敏感）；
 *  - SQL 保留**结构**（关键字/表列名），字面量与注释替换成 `?`/空白；
 *    原文哈希由 `ProvenanceStep.sqlHash` 单独保存——审计看结构，比对看哈希；
 *  - JDBC URL 保留 host/db，隐去 userinfo 口令与常见敏感查询参数；
 *  - 脱敏只作用于**记录**，工具调用本身永远收到原始参数（重放语义不受影响，
 *    且 db_* 工具本就不在重放白名单里）。
 */
object ProvenanceSanitizer {

    /** 脱敏占位符（固定字符串，便于审计检索） */
    const val REDACTED = "***"

    /** 归一后的敏感键名（去大小写/下划线/连字符） */
    private val SENSITIVE_KEYS = setOf(
        "password", "passwd", "pwd", "secret", "token", "apikey", "authorization", "credential", "credentials",
    )

    private val SENSITIVE_QUERY_PARAM = Regex(
        "(?i)(password|passwd|pwd|secret|token|api[_-]?key)=([^&;\\s]*)",
    )

    /**
     * 归一后的键名是否属于凭据：整名命中，或**以凭据词结尾**（accessToken / clientSecret /
     * userPassword / serviceApiKey）。`maxTokens`、`tokenizer` 这类数量/工具名不受影响。
     */
    private fun isSensitiveKey(normalized: String): Boolean =
        normalized in SENSITIVE_KEYS ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("password") ||
            normalized.endsWith("apikey")

    /** JDBC userinfo 口令：`jdbc:postgresql://user:pass@host/db` → `user:***@` */
    private val URL_USERINFO = Regex("(://[^/@:\\s]+):([^/@\\s]+)@")

    /**
     * 脱敏一次工具调用的入参（递归处理嵌套对象与对象数组）。
     * 还原不了原文的字段一律[REDACTED]；SQL 字段改成脱敏语句。
     */
    fun sanitizeArgs(args: JsonObject): JsonObject = JsonObject(
        args.mapValues { (key, value) -> sanitizeValue(key, value) },
    )

    private fun sanitizeValue(key: String, value: JsonElement): JsonElement {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return when {
            isSensitiveKey(normalized) -> JsonPrimitive(REDACTED)
            key.equals("sql", ignoreCase = true) && value is JsonPrimitive && value.isString ->
                JsonPrimitive(maskSql(value.content))
            normalized.endsWith("url") && value is JsonPrimitive && value.isString ->
                JsonPrimitive(maskUrl(value.content))
            value is JsonObject -> sanitizeArgs(value)
            value is JsonArray -> JsonArray(
                value.map { element ->
                    if (element is JsonObject) sanitizeArgs(element) else element
                },
            )
            else -> value
        }
    }

    /**
     * SQL 脱敏：字符串字面量、数字字面量、注释替换；标识符与关键字原样保留。
     *
     * 例：`SELECT * FROM t WHERE name = 'Alice' AND id = 5 -- 用户输入`
     *   → `SELECT * FROM t WHERE name = ? AND id = ? `
     *
     * 不是 SQL 解析器（不辩方言），只做「不把值抄进审计」这一件事；
     * 语法结构足够人读、也足够比对趋势。原文哈希见 `ProvenanceStep.sqlHash`。
     */
    fun maskSql(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                // 单引号字符串（SQL 用 '' 转义单引号）
                c == '\'' -> {
                    out.append('?')
                    i++
                    while (i < sql.length) {
                        if (sql[i] == '\'') {
                            if (i + 1 < sql.length && sql[i + 1] == '\'') {
                                i += 2
                            } else {
                                i++
                                break
                            }
                        } else {
                            i++
                        }
                    }
                }
                // 行注释 -- … 到行尾
                c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                    out.append(' ')
                }
                // 块注释 /* … */（未闭合时吞到结尾）
                c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    i = if (i + 1 < sql.length) i + 2 else sql.length
                    out.append(' ')
                }
                // 数字字面量：起始边界必须是「非标识符字符」，避免 col1 / t2 被误伤
                c.isDigit() && (i == 0 || !(sql[i - 1].isLetterOrDigit() || sql[i - 1] == '_')) -> {
                    i = consumeNumber(sql, i, out)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 吃掉一个数字字面量并写入 `?`，返回新下标（支持小数、指数、0x 十六进制） */
    private fun consumeNumber(sql: String, start: Int, out: StringBuilder): Int {
        out.append('?')
        var i = start
        if (sql[i] == '0' && i + 1 < sql.length && (sql[i + 1] == 'x' || sql[i + 1] == 'X')) {
            i += 2
            while (i < sql.length && (sql[i].isDigit() || sql[i].lowercaseChar() in 'a'..'f')) i++
            return i
        }
        while (i < sql.length && sql[i].isDigit()) i++
        if (i < sql.length && sql[i] == '.') {
            i++
            while (i < sql.length && sql[i].isDigit()) i++
        }
        if (i < sql.length && (sql[i] == 'e' || sql[i] == 'E')) {
            var j = i + 1
            if (j < sql.length && (sql[j] == '+' || sql[j] == '-')) j++
            if (j < sql.length && sql[j].isDigit()) {
                i = j
                while (i < sql.length && sql[i].isDigit()) i++
            }
        }
        return i
    }

    /**
     * JDBC/URL 脱敏：保留协议、主机、库名；隐去 userinfo 口令与敏感查询参数。
     * （用户名的价值在「哪个账号连的」，可保留；口令不行。）
     */
    fun maskUrl(url: String): String {
        val withoutUserInfo = url.replace(URL_USERINFO) { match -> "${match.groupValues[1]}:$REDACTED@" }
        return withoutUserInfo.replace(SENSITIVE_QUERY_PARAM) { match -> "${match.groupValues[1]}=$REDACTED" }
    }
}
