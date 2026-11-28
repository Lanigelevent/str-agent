package com.stragent.core.compute

import com.stragent.core.csv.Csv
import com.stragent.core.dataset.Dataset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 受控表达式求值器 —— 阶段 2「受控代码执行」的第一落地（`sandbox` 档）。
 *
 * ## 为什么不是「任意 Python」
 * D10 §阶段2 设想的是 Pyodide-WASM 或子进程沙箱；两者都要额外带一个几十 MB 的运行时，
 * 而**本仓当前形态是纯 JVM 插件**，落地成本与体积都不划算。于是把目标收窄成
 * **「策展工具做不到、但表达式能表达」的那部分分析**——例如
 * `avg(销售额 / 订单数)`（平均客单价）、`median(销售额 - 成本)`（中位毛利）、
 * `count(销售额 > 1000)`（达标行数）。
 *
 * ## 安全边界（这不是「沙箱」能有多强，而是「它压根不该强」）
 *  - **白名单语法**：只有算术、比较、列引用、数字/字符串字面量、有限个聚合函数；
 *  - **没有**函数定义、循环、赋值、import、反射、IO、网络 —— 想做也做不了；
 *  - **无状态**：只读数据集，不写任何文件；
 *  - **确定性**：同样的输入必然同样的输出 → 溯源 manifest 的**重放校验恒成立**（这是它最大的优点）。
 *
 * ## 口径纪律
 * 数值解析走 `Csv.parseNumber`（与 tier-1 逐条同规则），
 * 聚合的 count/missing 语义同 `summarizeColumn`：**count 是实际参与统计的样本数**。
 */
object ExpressionEvaluator {

    /** 表达式长度上限（防超长输入） */
    const val MAX_CODE_LENGTH = 2000

    /** 嵌套深度上限（防栈溢出） */
    const val MAX_DEPTH = 32

    /** 受支持的聚合函数 */
    private val AGGREGATES = setOf("sum", "avg", "min", "max", "count", "median", "p25", "p75", "distinct")

    /** 求值结果：数字或文本 */
    sealed interface Value {
        data class Num(val value: Double) : Value
        data class Text(val value: String) : Value
        data object Missing : Value
    }

    class ComputeException(message: String) : IllegalArgumentException(message)

    /**
     * 对数据集求值一条表达式。
     *
     * @return 单个值（聚合结果）或文本；无法聚合的表达式返回异常
     */
    fun evaluate(code: String, dataset: Dataset): JsonElement {
        val value = evaluateValue(code, dataset)
        return when (value) {
            is Value.Num -> JsonPrimitive(value.value)
            is Value.Text -> JsonPrimitive(value.value)
            Value.Missing -> throw ComputeException("表达式结果为空（没有可用的数值）")
        }
    }

    /** 求值（内部类型，供测试与后续扩展） */
    fun evaluateValue(code: String, dataset: Dataset): Value {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) throw ComputeException("表达式为空")
        if (trimmed.length > MAX_CODE_LENGTH) throw ComputeException("表达式过长（上限 $MAX_CODE_LENGTH 字符）")
        if (containsForbidden(trimmed)) {
            throw ComputeException("表达式含有不允许的构造（只支持算术、比较、列引用与聚合函数）")
        }
        val tokens = lex(trimmed)
        val parser = Parser(tokens, dataset.columns)
        val ast = parser.parseExpression()
        parser.expectEnd()
        val ctx = EvalContext(dataset)
        return ctx.eval(ast, 0, null)
    }

    // ---------- 词法 ----------

    private sealed interface Token {
        data class Num(val value: Double) : Token
        data class Ident(val name: String) : Token
        data class Op(val text: String) : Token
    }

    private fun lex(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || (ch == '.' && i + 1 < input.length && input[i + 1].isDigit()) -> {
                    var j = i
                    while (j < input.length && (input[j].isDigit() || input[j] == '.')) j++
                    val text = input.substring(i, j)
                    val value = text.toDoubleOrNull() ?: throw ComputeException("数字格式不对：$text")
                    tokens.add(Token.Num(value))
                    i = j
                }

                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < input.length && (input[j].isLetterOrDigit() || input[j] == '_' || input[j] >= '一' && input[j] <= '龥')) j++
                    tokens.add(Token.Ident(input.substring(i, j)))
                    i = j
                }

                else -> {
                    val two = if (i + 1 < input.length) input.substring(i, i + 2) else ""
                    when (two) {
                        ">=", "<=", "==", "!=" -> {
                            tokens.add(Token.Op(two)); i += 2
                        }

                        else -> {
                            when (ch) {
                                '+', '-', '*', '/', '(', ')', ',', '>', '<' -> {
                                    tokens.add(Token.Op(ch.toString())); i++
                                }

                                else -> throw ComputeException("无法识别的字符：'$ch'")
                            }
                        }
                    }
                }
            }
        }
        return tokens
    }

    /** 禁用构造的字面检测（词法之外的兜底：即使解析器将来放宽，这些也一律拒绝） */
    private fun containsForbidden(code: String): Boolean {
        val lower = code.lowercase()
        val forbidden = listOf("import", "def ", "lambda", "__", "eval(", "exec(", "open(", "system(", "os.", "subprocess", "socket", "require", "process")
        return forbidden.any { lower.contains(it) }
    }

    // ---------- 语法（递归下降） ----------

    private sealed interface Ast {
        data class Literal(val value: Value) : Ast
        data class Column(val name: String) : Ast
        data class Binary(val op: String, val left: Ast, val right: Ast) : Ast
        data class UnaryMinus(val operand: Ast) : Ast
        data class Call(val fn: String, val arg: Ast?) : Ast
    }

    private class Parser(private val tokens: List<Token>, private val columns: List<String>) {
        private var pos = 0

        /**
         * 比较（最低优先级）：`a > b`。
         * 上层是加法 → 乘法 → 一元 → 因子，四级标准优先级。
         */
        fun parseExpression(): Ast {
            val left = parseAdditive()
            val op = peekOp()
            if (op == ">" || op == "<" || op == ">=" || op == "<=" || op == "==" || op == "!=") {
                pos++
                return Ast.Binary(op, left, parseAdditive())
            }
            return left
        }

        /** 加减 */
        private fun parseAdditive(): Ast {
            var left = parseMultiplicative()
            while (true) {
                val op = peekOp()
                if (op != "+" && op != "-") return left
                pos++
                left = Ast.Binary(op, left, parseMultiplicative())
            }
        }

        /** 乘除 */
        private fun parseMultiplicative(): Ast {
            var left = parseUnary()
            while (true) {
                val op = peekOp()
                if (op != "*" && op != "/") return left
                pos++
                left = Ast.Binary(op, left, parseUnary())
            }
        }

        /** 一元负号 */
        private fun parseUnary(): Ast {
            if (peekOp() == "-") {
                pos++
                return Ast.UnaryMinus(parseUnary())
            }
            return parseFactor()
        }

        private fun parseFactor(): Ast {
            val token = peek() ?: throw ComputeException("表达式不完整")
            return when (token) {
                is Token.Num -> {
                    pos++
                    Ast.Literal(Value.Num(token.value))
                }

                is Token.Ident -> {
                    pos++
                    if (peekOp() == "(") {
                        pos++
                        val arg = if (peekOp() == ")") null else parseExpression()
                        expect(")")
                        val name = token.name.lowercase()
                        if (name !in AGGREGATES) {
                            throw ComputeException("不支持的函数：${token.name}（可用：${AGGREGATES.joinToString(", ")}）")
                        }
                        return Ast.Call(name, arg)
                    }
                    if (token.name !in columns) {
                        throw ComputeException("列「${token.name}」不存在（可用列：${columns.joinToString("、")}）")
                    }
                    Ast.Column(token.name)
                }

                is Token.Op -> {
                    if (token.text == "(") {
                        pos++
                        val inner = parseExpression()
                        expect(")")
                        return inner
                    }
                    throw ComputeException("表达式不完整：'${token.text}'")
                }
            }
        }

        fun expectEnd() {
            if (pos < tokens.size) throw ComputeException("表达式尾部有多余内容")
        }

        private fun expect(text: String) {
            val op = peekOp()
            if (op != text) throw ComputeException("缺少 '$text'")
            pos++
        }

        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun peekOp(): String? = (tokens.getOrNull(pos) as? Token.Op)?.text
    }

    // ---------- 求值 ----------

    private class EvalContext(val dataset: Dataset) {

        /**
         * 求值。
         *
         * @param row 当前行；**null = 顶层（无行上下文）** —— 此时列引用非法，
         *            列只能出现在聚合函数内部。
         */
        fun eval(ast: Ast, depth: Int, row: List<String>?): Value {
            if (depth > MAX_DEPTH) throw ComputeException("表达式嵌套过深（上限 $MAX_DEPTH）")
            return when (ast) {
                is Ast.Literal -> ast.value
                is Ast.Column -> {
                    if (row == null) {
                        throw ComputeException("列「${ast.name}」只能出现在聚合函数里（如 sum(${ast.name})）")
                    }
                    cellValue(ast.name, row)
                }

                is Ast.UnaryMinus -> {
                    val v = eval(ast.operand, depth + 1, row)
                    if (v is Value.Num) Value.Num(-v.value) else Value.Missing
                }

                is Ast.Binary -> evalBinary(ast, depth, row)
                is Ast.Call -> evalAggregate(ast, depth)
            }
        }

        private fun evalBinary(ast: Ast.Binary, depth: Int, row: List<String>?): Value {
            val left = eval(ast.left, depth + 1, row)
            val right = eval(ast.right, depth + 1, row)
            if (ast.op == "==" || ast.op == "!=") {
                val same = when {
                    left is Value.Num && right is Value.Num -> left.value == right.value
                    left is Value.Text && right is Value.Text -> left.value == right.value
                    else -> false
                }
                return Value.Num(if (if (ast.op == "==") same else !same) 1.0 else 0.0)
            }
            val a = left as? Value.Num ?: return Value.Missing
            val b = right as? Value.Num ?: return Value.Missing
            return when (ast.op) {
                "+" -> Value.Num(a.value + b.value)
                "-" -> Value.Num(a.value - b.value)
                "*" -> Value.Num(a.value * b.value)
                "/" -> if (b.value == 0.0) Value.Missing else Value.Num(a.value / b.value)
                ">" -> Value.Num(if (a.value > b.value) 1.0 else 0.0)
                "<" -> Value.Num(if (a.value < b.value) 1.0 else 0.0)
                ">=" -> Value.Num(if (a.value >= b.value) 1.0 else 0.0)
                "<=" -> Value.Num(if (a.value <= b.value) 1.0 else 0.0)
                else -> throw ComputeException("不支持的运算符：${ast.op}")
            }
        }

        /** 聚合：逐行求值 → 汇总（不支持嵌套聚合） */
        private fun evalAggregate(ast: Ast.Call, depth: Int): Value {
            val values = if (ast.arg == null) {
                dataset.rows.indices.map { Value.Num(1.0) }
            } else {
                if (ast.arg is Ast.Call) throw ComputeException("不支持嵌套聚合")
                dataset.rows.map { row -> eval(ast.arg, depth + 1, row) }
            }
            val numbers = values.mapNotNull { (it as? Value.Num)?.value }
            val texts = values.mapNotNull { (it as? Value.Text)?.value }
            return when (ast.fn) {
                "count" -> Value.Num(numbers.count { it != 0.0 }.toDouble())
                "sum" -> Value.Num(numbers.sum())
                "avg" -> if (numbers.isEmpty()) Value.Missing else Value.Num(numbers.average())
                "min" -> numbers.minOrNull()?.let { Value.Num(it) } ?: Value.Missing
                "max" -> numbers.maxOrNull()?.let { Value.Num(it) } ?: Value.Missing
                "median" -> median(numbers)?.let { Value.Num(it) } ?: Value.Missing
                "p25" -> quantile(numbers, 0.25)?.let { Value.Num(it) } ?: Value.Missing
                "p75" -> quantile(numbers, 0.75)?.let { Value.Num(it) } ?: Value.Missing
                "distinct" -> Value.Num((numbers + texts).distinct().size.toDouble())
                else -> throw ComputeException("不支持的聚合函数：${ast.fn}")
            }
        }

        /** 单元格取值：数值按 tier-1 同一套 parseNumber 解析；空 → 缺失；非数值 → 文本 */
        private fun cellValue(column: String, row: List<String>): Value {
            val index = dataset.columns.indexOfFirst { it == column }
            val cell = row.getOrNull(index) ?: ""
            val number = Csv.parseNumber(cell)
            return if (number != null) Value.Num(number) else if (cell.isBlank()) Value.Missing else Value.Text(cell)
        }

        private fun median(sorted: List<Double>): Double? {
            if (sorted.isEmpty()) return null
            val s = sorted.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
        }

        private fun quantile(values: List<Double>, q: Double): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val position = (s.size - 1) * q
            val lower = kotlin.math.floor(position).toInt()
            val upper = kotlin.math.ceil(position).toInt()
            if (lower == upper) return s[lower]
            return s[lower] + (s[upper] - s[lower]) * (position - lower)
        }
    }
}
