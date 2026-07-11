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

// sqlglot: core.SAFE_IDENTIFIER_RE
val SAFE_IDENTIFIER_RE: Regex = Regex("^[_a-zA-Z][a-zA-Z0-9_]*$")

/**
 * sqlglot: exp.to_identifier — builds an identifier from a String or copies an
 * existing Identifier. Returns null for null input, mirroring Python.
 */
fun toIdentifier(name: kotlin.Any?, quoted: kotlin.Boolean? = null, copy: kotlin.Boolean = true): Identifier? =
    when (name) {
        null -> null
        is Identifier -> if (copy) name.copy() as Identifier else name
        is kotlin.String -> Identifier(
            args("this" to name, "quoted" to (quoted ?: !SAFE_IDENTIFIER_RE.matches(name)))
        )
        else -> throw IllegalArgumentException(
            "Name needs to be a string or an Identifier, got: ${name::class.simpleName}"
        )
    }

/**
 * sqlglot: exp.column — builds a Column (or a Dot chain when [fields] are given).
 * [col]/[table]/[db]/[catalog] accept String or Identifier (or Star for [col]).
 */
fun column(
    col: kotlin.Any,
    table: kotlin.Any? = null,
    db: kotlin.Any? = null,
    catalog: kotlin.Any? = null,
    fields: kotlin.collections.List<kotlin.Any>? = null,
    quoted: kotlin.Boolean? = null,
    copy: kotlin.Boolean = true,
): Expression {
    val colExpr: Expression = if (col is Star) col else toIdentifier(col, quoted, copy)!!
    val column: Expression = Column(
        args(
            "this" to colExpr,
            "table" to toIdentifier(table, quoted, copy),
            "db" to toIdentifier(db, quoted, copy),
            "catalog" to toIdentifier(catalog, quoted, copy),
        )
    )
    if (!fields.isNullOrEmpty()) {
        return Dot.build(listOf(column) + fields.map { toIdentifier(it, quoted, copy)!! })
    }
    return column
}

/**
 * sqlglot: exp.alias_ — creates an Alias (or sets a TableAlias when [table] is true
 * or [tableColumns] are given). [alias] accepts String or Identifier.
 */
fun aliasExpression(
    expression: Expression,
    alias: kotlin.Any?,
    table: kotlin.Boolean = false,
    tableColumns: kotlin.collections.List<kotlin.Any>? = null,
    quoted: kotlin.Boolean? = null,
    copy: kotlin.Boolean = true,
): Expression {
    val exp = if (copy) expression.copy() else expression
    val aliasIdent = toIdentifier(alias, quoted = quoted)

    if (table || tableColumns != null) {
        val tableAlias = TableAlias(args("this" to aliasIdent))
        exp.set("alias", tableAlias)
        tableColumns?.forEach { tableAlias.append("columns", toIdentifier(it, quoted = quoted)) }
        return exp
    }

    // We don't set the "alias" arg for Window expressions (see Python exp.alias_).
    if ("alias" in exp.argTypes && exp !is Window) {
        exp.set("alias", aliasIdent)
        return exp
    }
    return Alias(args("this" to exp, "alias" to aliasIdent))
}

/**
 * sqlglot: exp.and_(*conditions, copy=False) over Expression operands — left-deep And
 * chain; Connector operands are wrapped in Paren (wrap=True default).
 */
fun combineAnd(conditions: kotlin.collections.List<Expression>): Expression {
    require(conditions.isNotEmpty()) { "combineAnd requires at least one condition" }
    var acc = conditions.first()
    if (conditions.size > 1 && acc is Connector) acc = Paren(args("this" to acc))
    for (condition in conditions.drop(1)) {
        val rhs = if (condition is Connector) Paren(args("this" to condition)) else condition
        acc = And(args("this" to acc, "expression" to rhs))
    }
    return acc
}

// sqlglot: exp.paren
fun paren(expression: Expression, copy: kotlin.Boolean = true): Paren =
    Paren(args("this" to (if (copy) expression.copy() else expression)))

// sqlglot: exp.func("coalesce", *args) — resolves to exp.Coalesce(this=first, expressions=rest)
fun coalesce(expressions: kotlin.collections.List<Expression>): Expression =
    Coalesce(
        args(
            "this" to expressions.first(),
            "expressions" to expressions.drop(1).ifEmpty { null },
        )
    )

// sqlglot: helper.name_sequence — returns a fresh "<prefix>0", "<prefix>1", ... generator.
fun nameSequence(prefix: kotlin.String): () -> kotlin.String {
    var counter = 0
    return { "$prefix${counter++}" }
}

// sqlglot: exp.select("*").from_(source) — the minimal builder slice qualify needs.
fun selectStarFrom(source: Expression): Select =
    Select(args("expressions" to listOf<kotlin.Any?>(Star()))).also {
        it.set("from_", From(args("this" to source)))
    }

// sqlglot: Query.subquery(alias, copy=False)
fun subquery(query: Expression, alias: kotlin.String? = null): Subquery =
    Subquery(
        args(
            "this" to query,
            "alias" to alias?.takeIf { it.isNotEmpty() }
                ?.let { TableAlias(args("this" to toIdentifier(it))) },
        )
    )

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
