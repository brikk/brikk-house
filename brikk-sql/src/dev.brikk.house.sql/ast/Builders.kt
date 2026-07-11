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
 * sqlglot: expressions.builders.apply_index_offset. Like Python, this runs
 * annotate_types over `this` and the index expression (leaving `t` payloads on the
 * subtree), then adjusts INTEGER-typed indices via `simplify(expression + offset)`.
 * [simplifyAddOffset] is the slice of optimizer.simplify that reaches this call:
 * literal folding plus trailing-literal association (`X + -1` plus 1 -> `X + 0`).
 */
internal fun <T> applyIndexOffset(
    this_: Expression?,
    expressions: kotlin.collections.List<T>,
    offset: Int,
    dialect: dev.brikk.house.sql.dialects.Dialect? = null,
): kotlin.collections.List<T> {
    if (offset == 0 || expressions.size != 1) return expressions

    val expression = expressions[0] as? Expression ?: return expressions

    if (this_ != null && this_.type == null) {
        dev.brikk.house.sql.optimizer.annotateTypes(this_, dialect = dialect)
    }

    val thisType = this_?.type?.thisArg
    if (thisType != DType.UNKNOWN && thisType != DType.ARRAY) {
        return expressions
    }

    if (expression.type == null) {
        dev.brikk.house.sql.optimizer.annotateTypes(expression, dialect = dialect)
    }

    if ((expression.type as? DataType)?.thisArg in DataType.INTEGER_TYPES) {
        @Suppress("UNCHECKED_CAST")
        return listOf(simplifyAddOffset(expression, offset)) as kotlin.collections.List<T>
    }

    return expressions
}

/**
 * sqlglot: simplify(expression + offset) as invoked by apply_index_offset. Fully
 * foldable trees collapse to a literal; an `Add` whose right side folds combines the
 * trailing literal with the offset (Python's simplify keeps the resulting `+ 0`);
 * anything else becomes `Add(expression, offset)` untouched, all matching Python.
 */
private fun simplifyAddOffset(expression: Expression, offset: Int): Expression {
    foldIntLiteral(expression)?.let { return intLiteral(it + offset) }

    if (expression is Add) {
        val rightValue = foldIntLiteral(expression.right)
        if (rightValue != null) {
            return Add(
                args(
                    "this" to expression.left,
                    "expression" to intLiteral(rightValue + offset),
                )
            )
        }
    }

    // sqlglot: Expression._binop wraps Binary operands in parens (`_wrap(this, Binary)`)
    val base =
        if (expression is Binary && expression !is Add) Paren(args("this" to expression))
        else expression
    return Add(args("this" to base, "expression" to intLiteral(offset.toLong())))
}
