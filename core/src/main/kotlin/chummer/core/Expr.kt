package chummer.core

/**
 * Minimal evaluator for the arithmetic formulas Chummer data uses in fields such as
 * `<ess>`, `<cost>` and `<avail>`: numbers, + - * / div, parentheses, unary minus and
 * named variables (Rating, Force, ... or {Name}). Chummer substitutes the variable and evaluates
 * with XPath math; this covers the same grammar. Function calls (FixedValues, Variable,
 * and so on) are not handled yet and throw.
 */
class Expr private constructor(private val s: String, private val vars: Map<String, Double>) {
    private var i = 0

    companion object {
        /** The value of [formula], or null when it is not plain arithmetic (for example "Special"). */
        fun evalOrNull(formula: String, vars: Map<String, Double> = emptyMap()): Double? =
            runCatching { eval(formula, vars) }.getOrNull()

        fun eval(formula: String, vars: Map<String, Double> = emptyMap()): Double {
            val e = Expr(formula.trim(), vars)
            val v = e.sum()
            e.skip()
            require(e.i == e.s.length) { "Unexpected '${e.s.substring(e.i)}' in \"$formula\"" }
            return v
        }
    }

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun sum(): Double {
        var v = product()
        while (true) {
            skip()
            if (i < s.length && s[i] == '+') { i++; v += product() }
            else if (i < s.length && s[i] == '-') { i++; v -= product() }
            else return v
        }
    }

    private fun product(): Double {
        var v = unary()
        while (true) {
            skip()
            if (i < s.length && s[i] == '*') { i++; v *= unary() }
            else if (i < s.length && s[i] == '/') { i++; v /= unary() }
            else if (s.startsWith("div", i)) { i += 3; v /= unary() }
            else return v
        }
    }

    private fun unary(): Double {
        skip()
        if (i < s.length && s[i] == '-') { i++; return -unary() }
        return atom()
    }

    private fun atom(): Double {
        skip()
        require(i < s.length) { "Unexpected end of \"$s\"" }
        val c = s[i]
        if (c == '(') {
            i++
            val v = sum()
            skip()
            require(i < s.length && s[i] == ')') { "Missing ) in \"$s\"" }
            i++
            return v
        }
        if (c.isDigit() || c == '.') {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
        if (c == '{') {
            // Settings formulas write variables as {Name}, for example {STR} * 15.
            val end = s.indexOf('}', i)
            require(end > i) { "Missing } in \"$s\"" }
            val name = s.substring(i + 1, end)
            i = end + 1
            return vars[name] ?: throw IllegalArgumentException("Unknown variable '$name' in \"$s\"")
        }
        if (c.isLetter()) {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val name = s.substring(st, i)
            skip()
            require(i >= s.length || s[i] != '(') { "Function '$name' not supported in \"$s\"" }
            return vars[name] ?: throw IllegalArgumentException("Unknown variable '$name' in \"$s\"")
        }
        throw IllegalArgumentException("Unexpected '$c' in \"$s\"")
    }
}
