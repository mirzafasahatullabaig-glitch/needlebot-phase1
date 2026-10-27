package com.needlebot.app

/**
 * Tiny safe arithmetic evaluator (+ - * / and parentheses) — Kotlin has no
 * eval, and we must not run arbitrary code. Recursive-descent parser over a
 * numeric expression string. Throws on anything it doesn't understand.
 */
object SimpleCalc {
    fun eval(expr: String): Double {
        val p = Parser(expr)
        val v = p.parseExpr()
        p.expectEnd()
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        fun parseExpr(): Double {           // + and -
            var v = parseTerm()
            while (true) {
                skip()
                when (peek()) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }
        fun parseTerm(): Double {           // * and /
            var v = parseFactor()
            while (true) {
                skip()
                when (peek()) {
                    '*' -> { i++; v *= parseFactor() }
                    '/' -> { i++; v /= parseFactor() }
                    else -> return v
                }
            }
        }
        fun parseFactor(): Double {         // number, (expr), or unary -
            skip()
            if (peek() == '(') { i++; val v = parseExpr(); skip(); require(peek() == ')'); i++; return v }
            if (peek() == '-') { i++; return -parseFactor() }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "expected a number" }
            return s.substring(start, i).toDouble()
        }
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun expectEnd() { skip(); require(i >= s.length) { "unexpected trailing input" } }
    }
}
