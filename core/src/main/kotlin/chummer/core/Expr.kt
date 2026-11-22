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
            val v = e.cmp()
            e.skip()
            require(e.i == e.s.length) { "Unexpected '${e.s.substring(e.i)}' in \"$formula\"" }
            return v
        }
    }

    /** Comparisons give 1 or 0, as XPath's `number(Rating > 1)` does when used in a price. */
    private fun cmp(): Double {
        var v = sum()
        skip()
        loop@ while (i < s.length) {
            val op = when {
                s.startsWith(">=", i) -> ">="
                s.startsWith("<=", i) -> "<="
                s.startsWith("!=", i) -> "!="
                s[i] == '>' -> ">"
                s[i] == '<' -> "<"
                s[i] == '=' -> "="
                else -> break@loop
            }
            i += op.length
            val r = sum()
            val ok = when (op) { ">=" -> v >= r; "<=" -> v <= r; "!=" -> v != r; ">" -> v > r; "<" -> v < r; else -> v == r }
            v = if (ok) 1.0 else 0.0
            skip()
        }
        return v
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
            val v = cmp()
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
            if (name == "number" && i < s.length && s[i] == '(') return atom() // number(x) is x
            require(i >= s.length || s[i] != '(') { "Function '$name' not supported in \"$s\"" }
            return vars[name] ?: throw IllegalArgumentException("Unknown variable '$name' in \"$s\"")
        }
        throw IllegalArgumentException("Unexpected '$c' in \"$s\"")
    }
}
