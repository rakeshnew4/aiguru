package com.aiguruapp.student.calculator

import kotlin.math.*

// ─── Top-level private math helpers ──────────────────────────────────────────

private fun calcFactorial(n: Int): Double {
    require(n >= 0) { "Factorial of negative is undefined" }
    require(n <= 170) { "Number too large — max factorial is 170!" }
    var r = 1.0
    for (k in 2..n) r *= k
    return r
}

private fun calcNPr(n: Int, r: Int): Double {
    require(r in 0..n) { "nPr requires 0 ≤ r ≤ n" }
    return calcFactorial(n) / calcFactorial(n - r)
}

private fun calcNCr(n: Int, r: Int): Double {
    require(r in 0..n) { "nCr requires 0 ≤ r ≤ n" }
    val safeR = minOf(r, n - r)   // symmetry: C(n,r) = C(n,n-r)
    return calcNPr(n, safeR) / calcFactorial(safeR)
}

// ─── CalculatorEngine ─────────────────────────────────────────────────────────

/**
 * Evaluates mathematical expression strings for grades 1–12.
 *
 * Supported:
 *  - Arithmetic : + − * / % ^ ( )
 *  - Functions  : sin cos tan asin acos atan log ln sqrt cbrt abs exp
 *  - Constants  : pi  e  ans
 *  - Postfix    : n!
 *  - Combinatorics: npr(n,r)  ncr(n,r)
 *  - Angle mode : DEG (default) or RAD (affects all trig functions)
 *
 * The `×` and `÷` Unicode symbols are silently normalised to * and /.
 */
class CalculatorEngine {

    enum class AngleMode { DEG, RAD }

    var angleMode = AngleMode.DEG
    var lastAnswer = 0.0

    // ── Public API ────────────────────────────────────────────────────────────

    /** Evaluate [expr] and return a human-readable result. Returns "Error" on failure. */
    fun evaluate(expr: String): String {
        if (expr.isBlank()) return ""
        return try {
            val result = Parser(expr.normalize()).parseAll()
            lastAnswer = result
            format(result)
        } catch (e: ArithmeticException) {
            e.message ?: "Math Error"
        } catch (e: IllegalArgumentException) {
            e.message ?: "Error"
        } catch (_: Exception) {
            "Error"
        }
    }

    /** Format a Double for display; whole numbers omit the decimal point. */
    fun format(v: Double): String = when {
        v.isNaN()      -> "Error"
        v.isInfinite() -> if (v > 0) "∞" else "-∞"
        // integer check: only use %.0f for values that fit in a Long
        v == floor(v) && abs(v) < 1e15 -> "%.0f".format(v)
        else -> "%.10g".format(v).let { s ->
            if ('.' in s) s.trimEnd('0').trimEnd('.') else s
        }
    }

    // ── Normalisation ─────────────────────────────────────────────────────────

    private fun String.normalize() = replace("×", "*").replace("÷", "/")

    // ── Recursive-descent parser ──────────────────────────────────────────────
    //
    // Grammar (lowest → highest precedence):
    //   expr    = addSub
    //   addSub  = mulMod ( ('+' | '-') mulMod )*
    //   mulMod  = pow    ( ('*' | '/' | '%') pow )*
    //   pow     = unary  ( '^' unary )*          ← right-associative
    //   unary   = ('-' | '+') unary | postfix
    //   postfix = primary ('!')*
    //   primary = number | '(' expr ')' | func '(' args ')' | constant

    private inner class Parser(private val src: String) {
        private var i = 0

        fun parseAll(): Double {
            val v = parseExpr()
            ws()
            if (i < src.length) throw IllegalArgumentException("Unexpected '${src[i]}'")
            return v
        }

        private fun parseExpr() = parseAddSub()

        private fun parseAddSub(): Double {
            var v = parseMulMod()
            while (i < src.length) {
                when (src[i]) {
                    '+' -> { i++; v += parseMulMod() }
                    '-' -> { i++; v -= parseMulMod() }
                    else -> break
                }
            }
            return v
        }

        private fun parseMulMod(): Double {
            var v = parsePow()
            while (i < src.length) {
                when (src[i]) {
                    '*' -> { i++; v *= parsePow() }
                    '/' -> {
                        i++
                        val d = parsePow()
                        if (d == 0.0) throw ArithmeticException("Division by zero")
                        v /= d
                    }
                    '%' -> { i++; v %= parsePow() }
                    else -> break
                }
            }
            return v
        }

        // Right-associative: 2^3^2 = 2^(3^2) = 512
        private fun parsePow(): Double {
            val b = parseUnary()
            return if (i < src.length && src[i] == '^') { i++; b.pow(parsePow()) } else b
        }

        private fun parseUnary(): Double {
            ws()
            return when {
                i < src.length && src[i] == '-' -> { i++; -parsePostfix() }
                i < src.length && src[i] == '+' -> { i++; parsePostfix() }
                else -> parsePostfix()
            }
        }

        private fun parsePostfix(): Double {
            var v = parsePrimary()
            ws()
            while (i < src.length && src[i] == '!') {
                i++
                if (v < 0 || v != floor(v)) throw ArithmeticException("Factorial requires a non-negative integer")
                v = calcFactorial(v.toInt())
            }
            return v
        }

        private fun parsePrimary(): Double {
            ws()
            if (i >= src.length) throw IllegalArgumentException("Unexpected end of expression")
            val c = src[i]
            if (c.isDigit() || c == '.') return parseNumber()
            if (c == '(') {
                i++
                val v = parseExpr()
                ws(); consume(')')
                return v
            }
            return dispatch(readWord())
        }

        private fun dispatch(word: String): Double = when (word.lowercase()) {
            "sin"            -> arg1 { sin(toRad(it)) }
            "cos"            -> arg1 { cos(toRad(it)) }
            "tan"            -> arg1 {
                val a = toRad(it)
                if (abs(cos(a)) < 1e-10) throw ArithmeticException("tan is undefined at this angle")
                tan(a)
            }
            "asin", "arcsin" -> arg1 { fromRad(asin(it.coerceIn(-1.0, 1.0))) }
            "acos", "arccos" -> arg1 { fromRad(acos(it.coerceIn(-1.0, 1.0))) }
            "atan", "arctan" -> arg1 { fromRad(atan(it)) }
            "log"            -> arg1 {
                if (it <= 0) throw ArithmeticException("log: argument must be positive")
                log10(it)
            }
            "log2"           -> arg1 {
                if (it <= 0) throw ArithmeticException("log2: argument must be positive")
                log2(it)
            }
            "ln"             -> arg1 {
                if (it <= 0) throw ArithmeticException("ln: argument must be positive")
                ln(it)
            }
            "sqrt"           -> arg1 {
                if (it < 0) throw ArithmeticException("sqrt: argument must be ≥ 0")
                sqrt(it)
            }
            "cbrt"           -> arg1 { cbrt(it) }
            "abs"            -> arg1 { abs(it) }
            "exp"            -> arg1 { exp(it) }
            "npr"            -> { val (n, r) = arg2(); calcNPr(n.toInt(), r.toInt()) }
            "ncr"            -> { val (n, r) = arg2(); calcNCr(n.toInt(), r.toInt()) }
            "pi", "π"        -> PI
            "e"              -> E
            "ans"            -> lastAnswer
            else             -> throw IllegalArgumentException("Unknown: '$word'")
        }

        private fun readWord(): String {
            ws()
            val start = i
            while (i < src.length && (src[i].isLetter() || src[i] == 'π')) i++
            if (i == start) throw IllegalArgumentException("Unexpected '${src[i]}'")
            return src.substring(start, i)
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            return src.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("Malformed number")
        }

        /** Parse a single parenthesised argument: (expr) */
        private fun arg1(op: (Double) -> Double): Double {
            ws(); consume('(')
            val v = parseExpr()
            ws(); consume(')')
            return op(v)
        }

        /** Parse two comma-separated arguments: (expr, expr) */
        private fun arg2(): Pair<Double, Double> {
            ws(); consume('(')
            val n = parseExpr()
            ws(); consume(',')
            val r = parseExpr()
            ws(); consume(')')
            return n to r
        }

        private fun consume(expected: Char) {
            if (i < src.length && src[i] == expected) i++
            // Intentionally lenient — tolerate missing closing bracket
        }

        private fun ws() { while (i < src.length && src[i] == ' ') i++ }

        private fun toRad(a: Double)  = if (angleMode == AngleMode.DEG) Math.toRadians(a) else a
        private fun fromRad(r: Double) = if (angleMode == AngleMode.DEG) Math.toDegrees(r) else r
    }
}
