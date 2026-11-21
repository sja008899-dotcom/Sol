package com.example.sol.brain

import com.example.sol.text.PersianNumbers
import com.example.sol.text.PersianTextNormalizer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

sealed interface CalculationResult {
    data class Success(
        val value: Double,
        val formattedFa: String,
        val expressionSummary: String,
    ) : CalculationResult

    data class Failure(val reasonFa: String) : CalculationResult
}

/**
 * Pure Kotlin hand-written tokenizer and recursive-descent arithmetic evaluator (ZERO eval/ScriptEngine).
 * Supports Latin/Persian digits, decimals, parentheses, +, -, *, /, ^, %, sqrt, and Persian spoken phrases
 * such as "دو ضرب در سه", "۲۵ درصد ۸۰۰", and "صد به علاوه پنجاه تقسیم بر دو".
 */
object SafeCalculator {

    private sealed interface Token {
        data class Num(val value: Double) : Token
        data class Op(val symbol: String) : Token // "+", "-", "*", "/", "^", "%OF", "%", "SQRT"
        data object LParen : Token
        data object RParen : Token
    }

    fun evaluate(rawInput: String): CalculationResult {
        val prepared = preprocessExpression(rawInput)
        if (prepared.isBlank()) {
            return CalculationResult.Failure("عبارت ریاضی برای محاسبه وارد نشده است.")
        }

        val tokens = tokenize(prepared)
            ?: return CalculationResult.Failure("عبارت ریاضی قابل تشخیص نیست (فقط اعداد و عملگرهای +، -، ×، ÷، توان، درصد و جذر پشتیبانی می‌شوند).")

        if (tokens.none { it is Token.Num }) {
            return CalculationResult.Failure("عددی در عبارت ریاضی پیدا نشد.")
        }

        return try {
            val parser = RecursiveDescentParser(tokens)
            val value = parser.parseAll()
            if (value.isNaN() || value.isInfinite()) {
                CalculationResult.Failure("نتیجهٔ محاسبه تعریف‌نشده یا بیش از حد بزرگ است.")
            } else {
                CalculationResult.Success(
                    value = value,
                    formattedFa = formatNumber(value),
                    expressionSummary = prepared,
                )
            }
        } catch (e: ArithmeticException) {
            CalculationResult.Failure(e.message ?: "خطای محاسباتی (احتمالاً تقسیم بر صفر).")
        } catch (_: IllegalArgumentException) {
            CalculationResult.Failure("ساختار عبارت ریاضی معتبر نیست.")
        }
    }

    internal fun preprocessExpression(rawInput: String): String {
        if (rawInput.isBlank()) return ""
        var s = PersianTextNormalizer.cleanDisplayText(rawInput)
            .replace('×', '*')
            .replace('x', '*')
            .replace('X', '*')
            .replace('÷', '/')
            .replace('−', '-')
            .replace('–', '-')
            .replace('٫', '.')
            .replace('٪', '%')
            .replace('؟', ' ')
            .replace('?', ' ')

        // Strip conversational filler
        s = FILLER_REGEX.replace(s, " ")

        // Replace multi-word Persian operators BEFORE word-number substitution so "در" in "ضرب در" isn't split badly
        s = s
            .replace(Regex("\\s*(?:به\\s+علاوه|بعلاوه|به\\s+اضافه|جمع\\s+با|اضافه\\s+بر|اضافه)\\s*"), " + ")
            .replace(Regex("\\s*(?:منهای|منها|کسر\\s+از)\\s*"), " - ")
            .replace(Regex("\\s*(?:ضربدر|ضرب\\s+در|ضرب)\\s*"), " * ")
            .replace(Regex("\\s*(?:تقسیم\\s+بر|تقسیم\\s+به|تقسیم)\\s*"), " / ")
            .replace(Regex("\\s*(?:به\\s+توان|توان)\\s*"), " ^ ")
            .replace(Regex("(?<!\\p{L})(?:جذر|رادیکال)(?!\\p{L})"), " sqrt ")

        // Pad punctuation/operators for clean word-number boundary splitting
        s = s
            .replace("(", " ( ")
            .replace(")", " ) ")
            .replace("+", " + ")
            .replace("*", " * ")
            .replace("/", " / ")
            .replace("^", " ^ ")
            .replace("%", " درصد ")
            .replace(Regex("(?<=\\d)-(?=\\d)"), " - ")
            .replace(Regex("\\s+-"), " -")

        // Normalize Persian number words inside spaces while keeping math operators intact
        s = convertSegmentWordNumbers(s)

        // Turn "25 درصد 800" or "25 درصد از 800" into explicit "25 %OF 800", and standalone "25 درصد" into "25 %"
        s = Regex("درصد\\s+(?:از\\s+)?(?=(?:[+\\-]\\s*)?(?:\\d|sqrt|\\())").replace(s, " %OF ")
        s = s.replace("درصد", " % ")

        // Handle colloquial "2 در 3" -> "2 * 3" only between digits/parens
        s = Regex("(?<=\\d|\\))\\s+در\\s+(?=\\d|\\()").replace(s, " * ")

        // Re-fix unary minus spacing: "- 5" at start or after operator/paren, binary minus otherwise
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun convertSegmentWordNumbers(input: String): String {
        val parts = input.split(Regex("\\s+")).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        val buffer = mutableListOf<String>()

        fun flushBuffer() {
            if (buffer.isEmpty()) return
            val joined = buffer.joinToString(" ")
            val converted = PersianNumbers.replaceWordNumbersWithDigits(joined)
            result.add(converted)
            buffer.clear()
        }

        val mathTokens = setOf("+", "-", "*", "/", "^", "(", ")", "sqrt", "درصد", "%OF", "%")
        for (p in parts) {
            if (p in mathTokens) {
                flushBuffer()
                result.add(p)
            } else {
                buffer.add(p)
            }
        }
        flushBuffer()
        return result.joinToString(" ")
    }

    private fun tokenize(expr: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = expr.length
        while (i < len) {
            val ch = expr[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            when {
                ch.isDigit() || ch == '.' -> {
                    val start = i
                    var dotSeen = ch == '.'
                    i++
                    while (i < len) {
                        val c = expr[i]
                        if (c.isDigit()) {
                            i++
                        } else if (c == '.' && !dotSeen) {
                            dotSeen = true
                            i++
                        } else {
                            break
                        }
                    }
                    val numStr = expr.substring(start, i)
                    val parsed = numStr.toDoubleOrNull() ?: return null
                    tokens.add(Token.Num(parsed))
                }
                ch == '(' -> {
                    tokens.add(Token.LParen)
                    i++
                }
                ch == ')' -> {
                    tokens.add(Token.RParen)
                    i++
                }
                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '^' -> {
                    tokens.add(Token.Op(ch.toString()))
                    i++
                }
                expr.startsWith("%OF", i) -> {
                    tokens.add(Token.Op("%OF"))
                    i += 3
                }
                ch == '%' -> {
                    tokens.add(Token.Op("%"))
                    i++
                }
                expr.startsWith("sqrt", i) -> {
                    tokens.add(Token.Op("SQRT"))
                    i += 4
                }
                else -> return null
            }
        }
        return tokens
    }

    private class RecursiveDescentParser(private val tokens: List<Token>) {
        private var pos = 0

        fun parseAll(): Double {
            val value = parseExpression()
            if (pos < tokens.size) {
                throw IllegalArgumentException("Unexpected trailing tokens at position $pos")
            }
            return value
        }

        private fun peek(): Token? = tokens.getOrNull(pos)

        private fun consume(): Token = tokens[pos++]

        private fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                val next = peek()
                if (next is Token.Op && (next.symbol == "+" || next.symbol == "-")) {
                    consume()
                    val right = parseTerm()
                    left = if (next.symbol == "+") left + right else left - right
                } else {
                    break
                }
            }
            return left
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (true) {
                val next = peek()
                if (next is Token.Op && (next.symbol == "*" || next.symbol == "/" || next.symbol == "%OF")) {
                    consume()
                    val right = parsePower()
                    left = when (next.symbol) {
                        "*" -> left * right
                        "%OF" -> (left / 100.0) * right
                        "/" -> {
                            if (abs(right) < 1e-12) {
                                throw ArithmeticException("تقسیم بر صفر ممکن نیست.")
                            }
                            left / right
                        }
                        else -> left
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            val next = peek()
            return if (next is Token.Op && next.symbol == "^") {
                consume()
                val exponent = parsePower()
                base.pow(exponent)
            } else {
                base
            }
        }

        private fun parseUnary(): Double {
            val next = peek()
            if (next is Token.Op) {
                when (next.symbol) {
                    "+" -> {
                        consume()
                        return parseUnary()
                    }
                    "-" -> {
                        consume()
                        return -parseUnary()
                    }
                    "SQRT" -> {
                        consume()
                        val v = parseUnary()
                        if (v < 0.0) throw ArithmeticException("جذر عدد منفی در اعداد حقیقی تعریف نشده است.")
                        return sqrt(v)
                    }
                }
            }
            return parsePostfix()
        }

        private fun parsePostfix(): Double {
            var value = parsePrimary()
            while (true) {
                val next = peek()
                if (next is Token.Op && next.symbol == "%") {
                    consume()
                    value /= 100.0
                } else {
                    break
                }
            }
            return value
        }

        private fun parsePrimary(): Double {
            return when (val tok = peek()) {
                is Token.Num -> {
                    consume()
                    tok.value
                }
                is Token.LParen -> {
                    consume()
                    val inner = parseExpression()
                    val closing = peek()
                    if (closing !is Token.RParen) {
                        throw IllegalArgumentException("پرانتز بسته نشده است.")
                    }
                    consume()
                    inner
                }
                else -> throw IllegalArgumentException("Unexpected token: $tok")
            }
        }
    }

    internal fun formatNumber(value: Double): String {
        val rounded = if (abs(value - value.toLong().toDouble()) < 1e-9 && abs(value) <= Long.MAX_VALUE / 2.0) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
        }
        return rounded
    }

    private val FILLER_REGEX = Regex(
        "(?:حساب کن|محاسبه کن|حاصل|مقدار|نتیجه|میشه چند|چند میشه|چند می‌شود|چند میشود|مساوی چنده|مساوی است با|چنده|چند است|برام|برای من|لطفا|لطفاً|=)"
    )
}
