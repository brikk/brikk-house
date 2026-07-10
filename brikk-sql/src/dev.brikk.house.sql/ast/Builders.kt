package dev.brikk.house.sql.ast

/**
 * Ports of small sqlglot/expressions/builders.py helpers shared by the parser and
 * the generator.
 */

// Negative constants mirror Python's Literal.number(-n) shape: Neg(Literal(n)).
internal fun intLiteral(value: Long): Expression =
    if (value < 0) Neg(args("this" to Literal.number((-value).toString())))
    else Literal.number(value.toString())

/**
 * Constant-folds an integer-literal arithmetic tree (`+`, `-`, `*`, negation and
 * parens); null when not foldable. Long overflow also bails, deferring to the
 * unfolded expression. This is the slice of optimizer.simplify's literal folding
 * needed by [applyIndexOffset] and Generator.LIMIT_ONLY_LITERALS.
 */
internal fun foldIntLiteral(expression: Expression?): Long? {
    when (expression) {
        is Literal -> {
            if (expression.isString) return null
            return (expression.thisArg as? kotlin.String)?.toLongOrNull()
        }
        is Paren -> return foldIntLiteral(expression.thisArg as? Expression)
        is Neg -> {
            val inner = foldIntLiteral(expression.thisArg as? Expression) ?: return null
            if (inner == Long.MIN_VALUE) return null
            return -inner
        }
        is Add, is Sub, is Mul -> {
            val binary = expression as Binary
            val l = foldIntLiteral(binary.left) ?: return null
            val r = foldIntLiteral(binary.right) ?: return null
            return when (expression) {
                is Add -> {
                    val result = l + r
                    if (((l xor result) and (r xor result)) < 0) null else result
                }
                is Sub -> {
                    val result = l - r
                    if (((l xor r) and (l xor result)) < 0) null else result
                }
                else -> {
                    if (l == 0L || r == 0L) 0L
                    else {
                        val result = l * r
                        if (result / r != l || (l == Long.MIN_VALUE && r == -1L)) null
                        else result
                    }
                }
            }
        }
        else -> return null
    }
}

/**
 * sqlglot: expressions.builders.apply_index_offset — Python runs annotate_types +
 * simplify; approximated here: `this` counts as UNKNOWN/ARRAY unless a Cast (or the
 * expression's own type slot) reveals a known non-array type, and only integer
 * constant indices are adjusted (columns annotate to UNKNOWN in Python and are left
 * alone there too).
 */
internal fun <T> applyIndexOffset(
    this_: Expression?,
    expressions: kotlin.collections.List<T>,
    offset: Int,
): kotlin.collections.List<T> {
    if (offset == 0 || expressions.size != 1) return expressions

    val expression = expressions[0] as? Expression ?: return expressions

    val thisType = this_?.type?.thisArg as? DType
    if (thisType != null && thisType != DType.UNKNOWN && thisType != DType.ARRAY) {
        return expressions
    }

    val value = foldIntLiteral(expression) ?: return expressions

    @Suppress("UNCHECKED_CAST")
    return listOf(intLiteral(value + offset)) as kotlin.collections.List<T>
}
