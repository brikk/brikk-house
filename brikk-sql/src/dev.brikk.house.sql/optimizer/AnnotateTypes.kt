package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.AnnotatorRef
import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Bracket
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Connector
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DataTypeParam
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Explode
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.In
import dev.brikk.house.sql.ast.Lateral
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Not
import dev.brikk.house.sql.ast.PercentileDisc
import dev.brikk.house.sql.ast.Pivot
import dev.brikk.house.sql.ast.PivotAlias
import dev.brikk.house.sql.ast.Predicate
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.SetOperation
import dev.brikk.house.sql.ast.Slice
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableFromRows
import dev.brikk.house.sql.ast.Tuple
import dev.brikk.house.sql.ast.TypingSpec
import dev.brikk.house.sql.ast.UDTF
import dev.brikk.house.sql.ast.Unnest
import dev.brikk.house.sql.ast.VarMap
import dev.brikk.house.sql.ast.aliasColumnNames
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.ast.fields
import dev.brikk.house.sql.ast.outputColumns
import dev.brikk.house.sql.ast.outputName
import dev.brikk.house.sql.ast.selects
import dev.brikk.house.sql.ast.unpivot
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

/**
 * Port of sqlglot's type annotator (reference/sqlglot/sqlglot/optimizer/annotate_types.py).
 *
 * Python-isms kept:
 *  - types flow as `DataType | DType` unions -> Kotlin `Any` values constrained to
 *    [DataType]/[DType] (helpers accept `Any?` and normalize like Python's _set_type);
 *  - id()-keyed caches use [Expression.objectId].
 */

// sqlglot: helper.seq_get
private fun <T> seqGet(seq: List<T>?, index: Int): T? = seq?.getOrNull(index)

/** Python truthiness for arg values (None/False/""/empty list are falsy). */
private fun truthy(value: Any?): Boolean = when (value) {
    null, false, "" -> false
    is List<*> -> value.isNotEmpty()
    else -> true
}

// sqlglot: helper.DATE_UNITS (interval units that operate on date components)
private val DATE_UNITS = setOf("day", "week", "month", "quarter", "year", "year_month")

// sqlglot: helper.is_date_unit
internal fun isDateUnit(expression: Expression?): Boolean =
    expression != null && expression.name.lowercase() in DATE_UNITS

private val DAYS_IN_MONTH = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

private fun isValidYmd(year: Int, month: Int, day: Int): Boolean {
    if (year < 1 || month !in 1..12 || day < 1) return false
    val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val maxDay = if (month == 2 && leap) 29 else DAYS_IN_MONTH[month - 1]
    return day <= maxDay
}

/**
 * sqlglot: helper.is_iso_date — Python uses datetime.date.fromisoformat. Supports the
 * ISO shapes that appear in SQL literals: YYYY-MM-DD and the compact YYYYMMDD.
 */
internal fun isIsoDate(text: String): Boolean {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(text)
        ?: Regex("^(\\d{4})(\\d{2})(\\d{2})$").matchEntire(text)
        ?: return false
    val (y, mo, d) = m.destructured
    return isValidYmd(y.toInt(), mo.toInt(), d.toInt())
}

/**
 * sqlglot: helper.is_iso_datetime — Python uses datetime.datetime.fromisoformat: an
 * ISO date optionally followed by a single separator character (usually 'T' or ' '),
 * a time HH[:MM[:SS[.f+]]], and an optional UTC offset / 'Z'.
 */
internal fun isIsoDatetime(text: String): Boolean {
    val dateLen = when {
        Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(text) -> 10
        Regex("^\\d{8}").containsMatchIn(text) -> 8
        else -> return false
    }
    val datePart = text.substring(0, dateLen)
    if (!isIsoDate(datePart)) return false
    if (text.length == dateLen) return true
    if (text.length == dateLen + 1) return false // bare separator, no time
    val timePart = text.substring(dateLen + 1)
    val time = Regex(
        "^\\d{2}(:\\d{2}(:\\d{2}(\\.\\d+)?)?|\\d{2}(\\d{2}(\\.\\d+)?)?)?" +
            "(Z|[+-]\\d{2}(:?\\d{2}(:?\\d{2}(\\.\\d+)?)?)?)?$"
    )
    return time.matches(timePart)
}

// sqlglot: core.SAFE_IDENTIFIER_RE (for exp.to_identifier)
private val SAFE_IDENTIFIER_RE = Regex("^[_a-zA-Z][a-zA-Z0-9_]*$")

// Python int(text) acceptance for number-literal texts (Literal.is_int)
private val INT_RE = Regex("^[+-]?\\d+$")

// sqlglot: exp.to_identifier(name) with default quoting
private fun toIdentifier(name: String): Identifier =
    Identifier(args("this" to name, "quoted" to !SAFE_IDENTIFIER_RE.matches(name)))

// sqlglot: annotate_types._coerce_date_literal
private fun coerceDateLiteral(l: Expression, unit: Expression?): DType {
    val dateText = l.name
    val isIsoDate_ = isIsoDate(dateText)

    if (isIsoDate_ && isDateUnit(unit)) {
        return DType.DATE
    }

    // An ISO date is also an ISO datetime, but not vice versa
    if (isIsoDate_ || isIsoDatetime(dateText)) {
        return DType.DATETIME
    }

    return DType.UNKNOWN
}

// sqlglot: annotate_types._coerce_date
private fun coerceDate(l: Expression, unit: Expression?): Any {
    if (!isDateUnit(unit)) {
        return DType.DATETIME
    }
    val type = l.type as? DataType
    return type?.thisArg ?: DType.UNKNOWN
}

// sqlglot: annotate_types.BinaryCoercionFunc
internal typealias BinaryCoercionFunc = (Expression, Expression) -> Any?

// sqlglot: annotate_types.swap_all (includes swap_args)
private fun swapAll(
    coercions: Map<Pair<DType, DType>, BinaryCoercionFunc>,
): Map<Pair<DType, DType>, BinaryCoercionFunc> {
    val result = LinkedHashMap(coercions)
    for ((key, func) in coercions) {
        result[Pair(key.second, key.first)] = { l, r -> func(r, l) }
    }
    return result
}

/**
 * sqlglot: sqlglot.optimizer.annotate_types.annotate_types — infers the types of an
 * expression, annotating its AST accordingly.
 */
fun <E : Expression> annotateTypes(
    expression: E,
    schema: Any? = null,
    expressionMetadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec>? = null,
    coercesTo: Map<DType, Set<DType>>? = null,
    dialect: Dialect? = null,
    overwriteTypes: Boolean = true,
): E {
    val ensured = ensureSchema(schema, dialect = dialect)
    return TypeAnnotator(
        schema = ensured,
        expressionMetadata = expressionMetadata,
        coercesTo = coercesTo,
        overwriteTypes = overwriteTypes,
    ).annotate(expression)
}

/** [annotateTypes] with a dialect name (mirrors Python's DialectType strings). */
fun <E : Expression> annotateTypes(
    expression: E,
    schema: Any? = null,
    dialect: String,
): E = annotateTypes(expression, schema = schema, dialect = Dialects.forName(dialect))

// sqlglot: annotate_types.TypeAnnotator
class TypeAnnotator(
    val schema: Schema,
    expressionMetadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec>? = null,
    coercesTo: Map<DType, Set<DType>>? = null,
    binaryCoercions: Map<Pair<DType, DType>, BinaryCoercionFunc>? = null,
    private val overwriteTypes: Boolean = true,
) {
    val dialect: Dialect = schema.dialect ?: Dialects.BASE
    private val expressionMetadata: Map<kotlin.reflect.KClass<out Expression>, TypingSpec> =
        expressionMetadata ?: dialect.expressionMetadata
    private val coercesTo: Map<DType, Set<DType>> =
        coercesTo ?: dialect.coercesTo ?: COERCES_TO
    private val binaryCoercions: Map<Pair<DType, DType>, BinaryCoercionFunc> =
        binaryCoercions ?: BINARY_COERCIONS

    // Caches the ids of annotated sub-expressions, to ensure we only visit them once
    private val visited = HashSet<Long>()

    // Caches NULL-annotated expressions to set them to UNKNOWN after type inference completes
    private val nullExpressions = LinkedHashMap<Long, Expression>()

    // Databricks and Spark >=v3 actually support NULL (i.e., VOID) as a type
    private val supportsNullType = dialect.supportsNullType

    // Maps a SetOperation's id (e.g. UNION) to its projection types
    private val setopColumnTypes = HashMap<Long, Map<String, Any>>()

    // Maps (Scope, source_name) to its column projections and types
    private val scopeSourceSelects = HashMap<Pair<Scope, String>, Map<String, Any>>()

    companion object {
        // sqlglot: TypeAnnotator.NESTED_TYPES
        val NESTED_TYPES: Set<DType> = setOf(DType.ARRAY)

        // sqlglot: TypeAnnotator.COERCES_TO (built by _build_coerces_to; generated)
        val COERCES_TO: Map<DType, Set<DType>> = GeneratedTypingMetadata.COERCES_TO

        /**
         * sqlglot: TypeAnnotator.BINARY_COERCIONS — coercion functions for binary
         * operations, keyed by the (left, right) DType pair.
         */
        val BINARY_COERCIONS: Map<Pair<DType, DType>, BinaryCoercionFunc> = buildMap {
            putAll(
                swapAll(
                    DataType.TEXT_TYPES.associate { t ->
                        Pair(t, DType.INTERVAL) to { l: Expression, r: Expression ->
                            coerceDateLiteral(l, r.args["unit"] as? Expression)
                        }
                    }
                )
            )
            putAll(
                swapAll(
                    buildMap {
                        // text + numeric will yield the numeric type to match most
                        // dialects' semantics. NOTE: Python's lambda reads
                        // `l.type if l.type in exp.DataType.NUMERIC_TYPES else r.type`;
                        // l.type is a DataType instance and NUMERIC_TYPES is a set of
                        // DType members, so the membership test is always False and the
                        // lambda always returns r.type. Ported with that exact behavior.
                        for (text in DataType.TEXT_TYPES) {
                            for (numeric in DataType.NUMERIC_TYPES) {
                                put(
                                    Pair(text, numeric),
                                    { _: Expression, r: Expression -> r.type }
                                )
                            }
                        }
                    }
                )
            )
            putAll(
                swapAll(
                    mapOf(
                        Pair(DType.DATE, DType.INTERVAL) to { l: Expression, r: Expression ->
                            coerceDate(l, r.args["unit"] as? Expression)
                        }
                    )
                )
            )
        }
    }

    // sqlglot: TypeAnnotator.clear
    fun clear() {
        visited.clear()
        nullExpressions.clear()
        setopColumnTypes.clear()
        scopeSourceSelects.clear()
    }

    // sqlglot: TypeAnnotator._set_type
    private fun setType(expression: Expression, targetType: Any?): Expression {
        val prevType = expression.type
        val expressionId = expression.objectId

        val dtype = targetType ?: DType.UNKNOWN
        expression.typeSlot = when (dtype) {
            is DataType -> dtype
            is DType -> dtype.intoExpr()
            else -> throw IllegalArgumentException(
                "Invalid target type: ${dtype::class.simpleName}"
            )
        }
        visited.add(expressionId)

        if (!supportsNullType && (expression.type as DataType).thisArg == DType.NULL) {
            nullExpressions[expressionId] = expression
        } else if (prevType != null && prevType.thisArg == DType.NULL) {
            nullExpressions.remove(expressionId)
        }

        return expression
    }

    // sqlglot: TypeAnnotator.annotate
    fun <E : Expression> annotate(expression: E, annotateScope: Boolean = true): E {
        // This flag is used to avoid costly scope traversals when we only care about
        // annotating non-column expressions (partial type inference)
        if (annotateScope) {
            for (scope in traverseScope(expression)) {
                this.annotateScope(scope)
            }
        }

        // This takes care of non-traversable expressions
        annotateExpression(expression, null)

        // Replace NULL type with the default type of the targeted dialect, since the
        // former is not an actual type; it is mostly used to aid type coercion.
        for (expr in nullExpressions.values.toList()) {
            setType(expr, dialect.defaultNullType)
        }

        return expression
    }

    // sqlglot: TypeAnnotator._get_scope_source_selects
    private fun getScopeSourceSelects(scope: Scope, sourceName: String): Map<String, Any> {
        val key = Pair(scope, sourceName)
        var selects = scopeSourceSelects[key]

        if (selects == null) {
            var result: Map<String, Any> = LinkedHashMap()
            val source = scope.sources[sourceName]

            if (source is Scope) {
                val expression = source.expression

                if (expression is UDTF) {
                    var values: List<Expression> = emptyList()

                    if (expression is Lateral) {
                        val lateralThis = expression.thisArg
                        if (lateralThis is Explode) {
                            values = listOf(lateralThis.thisArg as Expression)
                        }
                    } else if (expression is Unnest) {
                        values = listOf(expression)
                    } else if (expression !is TableFromRows) {
                        values = (expression.expressionsArg[0] as Expression)
                            .expressionsArg.filterIsInstance<Expression>()
                    }

                    if (values.isEmpty()) {
                        return emptyMap()
                    }

                    val aliasColumnNames = expression.aliasColumnNames

                    val expType: Expression? = when {
                        expression is Unnest -> expression.type
                        expression is Lateral && expression.thisArg is Explode ->
                            (expression.thisArg as Expression).type
                        else -> null
                    }

                    val structType =
                        if (expType is DataType && expType.isType(DType.STRUCT)) expType else null

                    if (structType != null) {
                        val map = LinkedHashMap<String, Any>()
                        for (colDef in structType.expressionsArg) {
                            if (colDef is ColumnDef && colDef.args["kind"] != null) {
                                map[colDef.name] = colDef.args["kind"] as Any
                            }
                        }
                        result = map
                    } else {
                        val map = LinkedHashMap<String, Any>()
                        for ((alias, column) in aliasColumnNames.zip(values)) {
                            column.type?.let { map[alias] = it }
                        }
                        result = map
                    }
                } else if (
                    expression is SetOperation &&
                    expression.left.selects.size == expression.right.selects.size
                ) {
                    result = getSetopColumnTypes(expression)
                } else if (expression is dev.brikk.house.sql.ast.Selectable) {
                    val map = LinkedHashMap<String, Any>()
                    for (s in expression.selects) {
                        s.type?.let { map[s.aliasOrName] = it }
                    }
                    result = map
                }
            } else {
                val pivots: List<Expression> =
                    if (source is Table) {
                        (source.args["pivots"] as? List<*>)?.filterIsInstance<Expression>()
                            ?: emptyList()
                    } else {
                        scope.pivots
                    }
                for (pivot in pivots) {
                    if (pivot !is Pivot) continue
                    if (pivot.aliasOrName == sourceName) {
                        val parent = pivot.parent
                        val parentSource =
                            if (parent != null) scope.sources[parent.aliasOrName] else null

                        val srcTypes: Map<String, Any> = when {
                            parent != null && parentSource is Scope ->
                                getScopeSourceSelects(scope, parent.aliasOrName)
                            parent is Table && schema is MappingSchema -> {
                                val found = schema.find(
                                    parent, raiseOnMissing = false, ensureDataTypes = true
                                ) as? Map<*, *>
                                val map = LinkedHashMap<String, Any>()
                                if (found != null) {
                                    for ((c, kind) in found) {
                                        if (kind != null) map[c.toString()] = kind
                                    }
                                }
                                map
                            }
                            else -> emptyMap()
                        }

                        result = if (pivot.unpivot) {
                            getUnpivotColumnTypes(pivot, srcTypes)
                        } else {
                            getPivotColumnTypes(pivot, srcTypes)
                        }
                        break
                    }
                }
            }

            scopeSourceSelects[key] = result
            selects = result
        }

        return selects
    }

    // sqlglot: TypeAnnotator.annotate_scope
    fun annotateScope(scope: Scope) {
        val mappingSchema = schema as? MappingSchema
        if (mappingSchema != null) {
            for (tableColumn in scope.tableColumns) {
                val source = scope.sources[tableColumn.name]

                if (source is Table) {
                    val tableSchema = mappingSchema.find(
                        source, raiseOnMissing = false, ensureDataTypes = true
                    ) as? Map<*, *> ?: continue

                    val structType = DataType(
                        args(
                            "this" to DType.STRUCT,
                            "expressions" to tableSchema.map { (c, kind) ->
                                ColumnDef(
                                    args("this" to toIdentifier(c.toString()), "kind" to kind)
                                )
                            },
                            "nested" to true,
                        )
                    )
                    setType(tableColumn, structType)
                } else if (source is Scope && source.expression is Query) {
                    val queryType =
                        source.expression.metaOrNull?.get("query_type") as? Expression
                            ?: DType.UNKNOWN.intoExpr()
                    if ((queryType as DataType).isType(DType.STRUCT)) {
                        setType(
                            tableColumn,
                            source.expression.meta["query_type"] as Expression
                        )
                    }
                }
            }
        }

        // Iterate through all the expressions of the current scope in post-order, and annotate
        annotateExpression(scope.expression, scope)
        fixupOrderByAliases(scope)

        if (dialect.queryResultsAreStructs && scope.expression is Query) {
            val structType = DataType(
                args(
                    "this" to DType.STRUCT,
                    "expressions" to scope.expression.selects.map { select ->
                        ColumnDef(
                            args(
                                "this" to toIdentifier(select.outputName),
                                "kind" to (select.type as? DataType)?.copy(),
                            )
                        )
                    },
                    "nested" to true,
                )
            )

            val anyUnknown = structType.expressionsArg.any { cd ->
                cd is ColumnDef && (cd.args["kind"] as? DataType)?.isType(DType.UNKNOWN) == true
            }
            if (!anyUnknown) {
                // We don't use `_set_type` on purpose here (see the Python comment):
                // annotating the query directly could make e.g. ARRAY(<query>)
                // interpret it as a STRUCT value.
                scope.expression.meta["query_type"] = structType
            }
        }
    }

    // sqlglot: TypeAnnotator._annotate_expression
    private fun annotateExpression(expression: Expression, scope: Scope?) {
        val stack = ArrayDeque<Pair<Expression, Boolean>>()
        stack.addLast(Pair(expression, false))

        while (stack.isNotEmpty()) {
            val (expr, childrenAnnotated) = stack.removeLast()

            if (
                expr.objectId in visited ||
                (!overwriteTypes && expr.type != null && !expr.isType(DType.UNKNOWN))
            ) {
                continue // We've already inferred the expression's type
            }

            if (!childrenAnnotated) {
                stack.addLast(Pair(expr, true))
                for (childExpr in expr.iterExpressions()) {
                    stack.addLast(Pair(childExpr, false))
                }
                continue
            }

            if (scope != null && expr is Column && expr.text("table").isNotEmpty()) {
                annotateColumn(expr, scope)
                continue
            }

            val spec = expressionMetadata[expr::class]

            when {
                spec is TypingSpec.Annotate -> runAnnotator(spec.ref, expr)
                spec is TypingSpec.Returns -> setType(expr, spec.dtype)
                spec is TypingSpec.ReturnsDataType -> setType(expr, spec.dtype)
                else -> setType(expr, DType.UNKNOWN)
            }
        }
    }

    /** The scoped-Column branch of _annotate_expression's stack walk. */
    private fun annotateColumn(expr: Column, scope: Scope) {
        val table = expr.text("table")
        var source: Any? = null
        var sourceScope: Scope? = scope
        while (sourceScope != null && source == null) {
            source = sourceScope.sources[table]
            if (source == null) {
                sourceScope = sourceScope.parent
            }
        }

        if (source is Table) {
            var tableColType: Any? = schema.getColumnType(source, expr)
            if (
                tableColType is DataType &&
                tableColType.isType(DType.UNKNOWN) &&
                truthy(source.args["pivots"])
            ) {
                tableColType = getScopeSourceSelects(sourceScope ?: scope, table)[expr.name]
                    ?: DType.UNKNOWN
            }

            setType(expr, tableColType)
        } else if (source != null && sourceScope != null) {
            val colType = getScopeSourceSelects(sourceScope, table)[expr.name]
            val sourceExpr = (source as? Scope)?.expression
            if (colType != null) {
                setType(expr, colType)
            } else if (sourceExpr is Unnest) {
                setType(expr, sourceExpr.type)
            } else {
                setType(expr, DType.UNKNOWN)
            }
        } else {
            val colType =
                if (source == null && scope.pivots.isNotEmpty()) {
                    getScopeSourceSelects(scope, table)[expr.name]
                } else {
                    null
                }
            if (colType != null) {
                setType(expr, colType)
            } else {
                setType(expr, DType.UNKNOWN)
            }
        }

        val dotParts = expr.metaOrNull?.get("dot_parts") as? List<*>
        if (expr.isType(DType.JSON) && !dotParts.isNullOrEmpty()) {
            // JSON dot access is case sensitive across all dialects, so we need to
            // undo the normalization.
            val i = dotParts.iterator()
            var parent = expr.parent
            while (parent is Dot) {
                (parent.expressionArg as Expression).replace(
                    Identifier(args("this" to i.next(), "quoted" to true))
                )
                parent = parent.parent
            }

            expr.metaOrNull?.remove("dot_parts")
        }

        val exprType = expr.type
        if (exprType != null && exprType.args["nullable"] == false) {
            expr.meta["nonnull"] = true
        }
    }

    /** Dispatches a generated [AnnotatorRef] to its hand-ported implementation. */
    private fun runAnnotator(ref: AnnotatorRef, e: Expression) {
        when (ref) {
            is AnnotatorRef.BinaryAnn -> annotateBinary(e)
            is AnnotatorRef.UnaryAnn -> annotateUnary(e)
            is AnnotatorRef.ByArgs ->
                annotateByArgs(e, ref.keys, promote = ref.promote, array = ref.array)
            is AnnotatorRef.CaseArgs -> {
                val ifs = (e.args["ifs"] as? List<*>)?.filterIsInstance<Expression>()
                    ?: emptyList()
                val branchArgs: List<Any> = ifs.map { it.args["true"] as Expression } + "default"
                annotateByArgs(e, branchArgs)
            }
            is AnnotatorRef.ByArrayElement -> annotateByArrayElement(e)
            is AnnotatorRef.UdfType -> setType(e, schema.getUdfType(e))
            is AnnotatorRef.TimeUnitCoercion -> annotateTimeunit(e)
            is AnnotatorRef.SetTypeFromArg -> setType(e, e.args[ref.key])
            is AnnotatorRef.MapAnn -> annotateMap(e)
            is AnnotatorRef.BracketAnn -> annotateBracket(e as Bracket)
            is AnnotatorRef.FlagType ->
                setType(e, if (truthy(e.args[ref.flag])) ref.whenTrue else ref.whenFalse)
            is AnnotatorRef.Identity -> Unit // exp.DataType: lambda _, e: e
            is AnnotatorRef.ArrayOfType -> setType(
                e,
                DataType(
                    args(
                        "this" to DType.ARRAY,
                        "expressions" to listOf(
                            DataType(args("this" to ref.element, "nested" to false))
                        ),
                        "nested" to true,
                    )
                ),
            )
            is AnnotatorRef.DivAnn -> annotateDiv(e)
            is AnnotatorRef.DotAnn -> annotateDot(e as Dot)
            is AnnotatorRef.ExplodeAnn -> annotateExplode(e)
            is AnnotatorRef.ExtractAnn -> annotateExtract(e)
            is AnnotatorRef.LiteralAnn -> annotateLiteral(e as Literal)
            is AnnotatorRef.StructAnn -> annotateStruct(e)
            is AnnotatorRef.ToMapAnn -> annotateToMap(e)
            is AnnotatorRef.UnnestAnn -> annotateUnnest(e)
            is AnnotatorRef.WithinGroupAnn -> annotateWithinGroup(e)
            is AnnotatorRef.SubqueryAnn -> annotateSubquery(e as Subquery)
            is AnnotatorRef.RandThisOrDouble ->
                if (truthy(e.args["this"])) {
                    annotateByArgs(e, listOf("this"))
                } else {
                    setType(e, DType.DOUBLE)
                }
            // sqlglot: spark2 exp.ApproxQuantile — by_args over "this", array-ness
            // driven by whether the "quantile" arg resolves to an ARRAY type.
            is AnnotatorRef.ApproxQuantileByArgs -> {
                val quantile = e.args["quantile"] as? Expression
                annotateByArgs(e, listOf("this"), array = quantile?.isType(DType.ARRAY) == true)
            }
            // sqlglot: spark2 _annotate_by_similar_args (CONCAT/LPAD/RPAD family).
            is AnnotatorRef.BySimilarArgs -> annotateBySimilarArgs(e, ref.keys)
            // sqlglot: exp.DataType.build("FixedString(16)", dialect="clickhouse")
            is AnnotatorRef.SetSizedType -> setType(
                e,
                DataType(
                    args(
                        "this" to ref.dtype,
                        "expressions" to listOf(
                            DataTypeParam(args("this" to Literal.number(ref.size.toString())))
                        ),
                        "nested" to false,
                        "nullable" to ref.nullable,
                    )
                ),
            )
        }
    }

    // sqlglot: TypeAnnotator._fixup_order_by_aliases
    private fun fixupOrderByAliases(scope: Scope) {
        val query = scope.expression
        if (query !is Query) return

        val order = query.args["order"] as? Expression ?: return

        // Build alias -> type map from fully-annotated projections (last match wins,
        // consistent with how _expand_alias_refs handles duplicate aliases).
        val aliasTypes = LinkedHashMap<String, Expression>()
        for (sel in query.selects) {
            if (sel is Alias) {
                val selThis = sel.thisArg as? Expression ?: continue
                val selType = selThis.type
                if (selType != null && !selThis.isType(DType.UNKNOWN)) {
                    aliasTypes[sel.alias] = selType
                }
            }
        }

        if (aliasTypes.isEmpty()) return

        for (ordered in order.expressionsArg.filterIsInstance<Expression>()) {
            val aliasCols = ordered.findAll<Column>().filter {
                it.text("table").isEmpty() && it.name in aliasTypes
            }.toList()
            for (col in aliasCols) {
                setType(col, aliasTypes[col.name])
            }

            if (aliasCols.isNotEmpty()) {
                for (node in ordered.walk(prune = { it is Subquery })) {
                    if (node !is Column && node !is Literal) {
                        visited.remove(node.objectId)
                    }
                }
                annotateExpression(ordered, scope)
            }
        }
    }

    /**
     * sqlglot: TypeAnnotator._maybe_coerce — returns type2 if type1 can be coerced
     * into it, otherwise type1. Parameterized types never coerce.
     */
    private fun maybeCoerce(type1: Any?, type2: Any?): Any {
        val type1Value: Any?
        if (type1 is DataType) {
            if (type1.expressionsArg.isNotEmpty()) {
                return type1
            }
            type1Value = type1.thisArg
        } else {
            type1Value = type1
        }

        val type2Value: Any?
        if (type2 is DataType) {
            if (type2.expressionsArg.isNotEmpty()) {
                return type2
            }
            type2Value = type2.thisArg
        } else {
            type2Value = type2
        }

        // We propagate the UNKNOWN type upwards if found
        if (type1Value == DType.UNKNOWN || type2Value == DType.UNKNOWN) {
            return DType.UNKNOWN
        }

        if (type1Value == DType.NULL) {
            return type2Value ?: DType.UNKNOWN
        }
        if (type2Value == DType.NULL) {
            return type1Value ?: DType.UNKNOWN
        }

        return if (type2Value in (coercesTo[type1Value] ?: emptySet<DType>())) {
            type2Value ?: DType.UNKNOWN
        } else {
            type1Value ?: DType.UNKNOWN
        }
    }

    // sqlglot: TypeAnnotator._get_setop_column_types
    private fun getSetopColumnTypes(setop: SetOperation): Map<String, Any> {
        val setopId = setop.objectId
        setopColumnTypes[setopId]?.let { return it }

        val colTypes = LinkedHashMap<String, Any>()

        // Validate that left and right have same number of projections
        if (
            setop.left.selects.isEmpty() ||
            setop.right.selects.isEmpty() ||
            setop.left.selects.size != setop.right.selects.size
        ) {
            return colTypes
        }

        // Process a chain / sub-tree of set operations
        for (setOp in setop.walk(prune = { it !is SetOperation && it !is Subquery })) {
            if (setOp !is SetOperation) continue

            val setopCols = LinkedHashMap<String, Any>()
            if (truthy(setOp.args["by_name"])) {
                val rTypeBySelect = HashMap<String, Expression?>()
                for (s in setOp.right.selects) rTypeBySelect[s.aliasOrName] = s.type
                for (s in setOp.left.selects) {
                    setopCols[s.aliasOrName] = maybeCoerce(
                        s.type,
                        rTypeBySelect[s.aliasOrName] ?: DType.UNKNOWN,
                    )
                }
            } else {
                for ((ls, rs) in setOp.left.selects.zip(setOp.right.selects)) {
                    setopCols[ls.aliasOrName] = maybeCoerce(ls.type, rs.type)
                }
            }

            // Coerce intermediate results with the previously registered types
            for ((colName, colType) in setopCols) {
                colTypes[colName] = maybeCoerce(colType, colTypes[colName] ?: DType.NULL)
            }
        }

        setopColumnTypes[setopId] = colTypes
        return colTypes
    }

    // sqlglot: TypeAnnotator._get_unpivot_column_types
    private fun getUnpivotColumnTypes(pivot: Pivot, srcTypes: Map<String, Any>): Map<String, Any> {
        val newTypes = LinkedHashMap<String, Any?>()

        for (field in pivot.fields) {
            val fieldCol = field.thisArg as Expression
            val first = seqGet(field.expressionsArg.filterIsInstance<Expression>(), 0)

            val inSrc: Expression?
            if (first is PivotAlias && first.args["alias"] != null) {
                val aliasNode = first.args["alias"] as Expression
                newTypes[fieldCol.name] = aliasNode.type
                inSrc = first.thisArg as? Expression
            } else {
                newTypes[fieldCol.name] = DType.VARCHAR.intoExpr()
                inSrc = first
            }

            val inCols =
                if (inSrc is Tuple) inSrc.expressionsArg.filterIsInstance<Expression>()
                else listOfNotNull(inSrc)
            val valExpr = seqGet(pivot.expressionsArg.filterIsInstance<Expression>(), 0)
            val valCols =
                if (valExpr is Tuple) valExpr.expressionsArg.filterIsInstance<Expression>()
                else listOfNotNull(valExpr)
            for ((valCol, inCol) in valCols.zip(inCols)) {
                newTypes[valCol.outputName] = inCol.type
            }
        }

        val result = LinkedHashMap<String, Any>()
        for (name in pivot.outputColumns(srcTypes.keys).keys) {
            val type = newTypes[name] ?: srcTypes[name]
            if (type != null) result[name] = type
        }
        return result
    }

    // sqlglot: TypeAnnotator._get_pivot_column_types
    private fun getPivotColumnTypes(pivot: Pivot, srcTypes: Map<String, Any>): Map<String, Any> {
        val firstField = seqGet(pivot.fields, 0)
        if (firstField !is In) {
            throw OptimizeError(
                "Expected In expression for pivot field, got ${firstField?.let { it::class.simpleName }}"
            )
        }

        val pivotConstants = firstField.expressionsArg

        // The first agg_cols_offset entries are source columns that pass through the
        // PIVOT unchanged; the rest are the aggregated columns, one per combination of
        // IN value and aggregate function.
        val outputToSrc = pivot.outputColumns(srcTypes.keys)

        val aggTypes = pivot.expressionsArg.filterIsInstance<Expression>().map { agg ->
            if (agg is Alias) (agg.thisArg as Expression).type else agg.type
        }
        val aggColsOffset = outputToSrc.size - pivotConstants.size * aggTypes.size
        if (aggColsOffset < 0) {
            throw OptimizeError("Negative pivot column offset: $aggColsOffset")
        }

        val outputNames = outputToSrc.keys.toList()
        val newTypes = LinkedHashMap<String, Any>()

        for (name in outputNames.take(aggColsOffset)) {
            val type = srcTypes[outputToSrc[name]]
            if (type != null) newTypes[name] = type
        }

        val repeatedAggTypes = sequence {
            for (unused in pivotConstants) {
                for (a in aggTypes) yield(a)
            }
        }
        for ((name, aggType) in outputNames.drop(aggColsOffset).zip(repeatedAggTypes.toList())) {
            if (aggType != null) newTypes[name] = aggType
        }

        return newTypes
    }

    // sqlglot: TypeAnnotator._annotate_binary
    private fun annotateBinary(expression: Expression): Expression {
        val left = expression.args["this"] as? Expression
        val right = expression.args["expression"] as? Expression
        if (left == null || right == null) {
            // Python logs a warning with the rendered SQL here
            setType(expression, null)
            return expression
        }

        val leftType = (left.type as? DataType)?.thisArg
        val rightType = (right.type as? DataType)?.thisArg

        val coercionKey =
            if (leftType is DType && rightType is DType) Pair(leftType, rightType) else null

        if (expression is Connector || expression is Predicate) {
            setType(expression, DType.BOOLEAN)
        } else if (coercionKey != null && coercionKey in binaryCoercions) {
            setType(expression, binaryCoercions.getValue(coercionKey)(left, right))
        } else {
            annotateByArgs(expression, listOf(left, right))
        }

        if (
            expression is dev.brikk.house.sql.ast.Is ||
            (left.metaOrNull?.get("nonnull") == true && right.metaOrNull?.get("nonnull") == true)
        ) {
            expression.meta["nonnull"] = true
        }

        return expression
    }

    // sqlglot: TypeAnnotator._annotate_unary
    private fun annotateUnary(expression: Expression): Expression {
        if (expression is Not) {
            setType(expression, DType.BOOLEAN)
        } else {
            setType(expression, (expression.thisArg as Expression).type)
        }

        if ((expression.thisArg as Expression).metaOrNull?.get("nonnull") == true) {
            expression.meta["nonnull"] = true
        }

        return expression
    }

    // sqlglot: TypeAnnotator._annotate_literal
    private fun annotateLiteral(expression: Literal): Expression {
        if (expression.isString) {
            setType(expression, DType.VARCHAR)
        } else if (expression.args["this"].let { it is String && INT_RE.matches(it) }) {
            // sqlglot: Expression.is_int (is_number and int(name) parses)
            setType(expression, DType.INT)
        } else {
            setType(expression, DType.DOUBLE)
        }

        expression.meta["nonnull"] = true

        return expression
    }

    // sqlglot: TypeAnnotator._annotate_by_args
    private fun annotateByArgs(
        expression: Expression,
        argRefs: List<Any>,
        promote: Boolean = false,
        array: Boolean = false,
    ): Expression {
        var literalType: Any? = null
        var nonLiteralType: Any? = null
        var nestedType: DataType? = null

        for (arg in argRefs) {
            val expressions: Any? = if (arg is String) expression.args[arg] else arg

            val exprList: List<Any?> = when (expressions) {
                null -> emptyList()
                is List<*> -> expressions
                else -> listOf(expressions)
            }
            for (item in exprList) {
                val expr = item as? Expression ?: continue
                val exprType = expr.type as DataType

                if (exprType.isType(DType.UNKNOWN)) {
                    setType(expression, DType.UNKNOWN)
                    return expression
                }

                if (nestedType != null) {
                    continue
                }

                // Stop coercing at the first nested data type found
                if (truthy(exprType.args["nested"])) {
                    nestedType = exprType
                } else if (expr is Literal) {
                    literalType = maybeCoerce(literalType ?: exprType, exprType)
                } else {
                    nonLiteralType = maybeCoerce(nonLiteralType ?: exprType, exprType)
                }
            }
        }

        var resultType: Any? = null

        if (nestedType != null) {
            resultType = nestedType
        } else if (literalType != null && nonLiteralType != null) {
            if (dialect.prioritizeNonLiteralTypes) {
                val literalThisType =
                    if (literalType is DataType) literalType.thisArg else literalType
                val nonLiteralThisType =
                    if (nonLiteralType is DataType) nonLiteralType.thisArg else nonLiteralType
                if (
                    (
                        literalThisType in DataType.INTEGER_TYPES &&
                            nonLiteralThisType in DataType.INTEGER_TYPES
                        ) ||
                    (
                        literalThisType in DataType.REAL_TYPES &&
                            nonLiteralThisType in DataType.REAL_TYPES
                        )
                ) {
                    resultType = nonLiteralType
                }
            }
        } else {
            resultType = literalType ?: nonLiteralType ?: DType.UNKNOWN
        }

        setType(expression, resultType ?: maybeCoerce(nonLiteralType, literalType))

        if (promote) {
            val thisType = (expression.type as DataType).thisArg
            if (thisType in DataType.INTEGER_TYPES) {
                setType(expression, DType.BIGINT)
            } else if (thisType in DataType.FLOAT_TYPES) {
                setType(expression, DType.DOUBLE)
            }
        }

        if (array) {
            setType(
                expression,
                DataType(
                    args(
                        "this" to DType.ARRAY,
                        "expressions" to listOf(expression.type),
                        "nested" to true,
                    )
                ),
            )
        }

        return expression
    }

    // sqlglot: spark2 _annotate_by_similar_args (sqlglot/typing/spark2.py) — CONCAT-family
    // type inference. All-BINARY -> BINARY; else any known, non-ARRAY, non-BINARY arg
    // -> TEXT; else UNKNOWN. Ported bug-for-bug (binary+unknown stays UNKNOWN).
    private fun annotateBySimilarArgs(expression: Expression, keys: List<String>): Expression {
        val argExprs = mutableListOf<Expression>()
        for (key in keys) {
            when (val v = expression.args[key]) {
                null -> {}
                is List<*> -> v.forEach { if (it is Expression) argExprs.add(it) }
                is Expression -> argExprs.add(v)
            }
        }

        val result: Any = if (argExprs.isNotEmpty() && argExprs.all { it.isType(DType.BINARY) }) {
            DType.BINARY
        } else if (
            argExprs.any {
                it.type != null && !it.isType(DType.UNKNOWN, DType.ARRAY, DType.BINARY)
            }
        ) {
            DType.TEXT
        } else {
            DType.UNKNOWN
        }

        setType(expression, result)
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_timeunit
    private fun annotateTimeunit(expression: Expression): Expression {
        val exprThis = expression.thisArg as Expression
        val thisType = (exprThis.type as? DataType)?.thisArg
        val unit = expression.args["unit"] as? Expression

        val datatype: Any = when {
            thisType in DataType.TEXT_TYPES -> coerceDateLiteral(exprThis, unit)
            thisType in DataType.TEMPORAL_TYPES -> coerceDate(exprThis, unit)
            else -> DType.UNKNOWN
        }

        setType(expression, datatype)
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_bracket
    private fun annotateBracket(expression: Bracket): Expression {
        val bracketArg = expression.expressionsArg[0] as Expression
        val exprThis = expression.thisArg as Expression

        val thisType = exprThis.type as? DataType
        val thisKeys = mapKeys(exprThis)

        if (bracketArg is Slice) {
            setType(expression, exprThis.type)
        } else if (thisType != null && thisType.isType(DType.ARRAY)) {
            setType(expression, seqGet(thisType.expressionsArg.filterIsInstance<Expression>(), 0))
        } else if (thisKeys != null && thisKeys.any { it == bracketArg }) {
            val index = thisKeys.indexOfFirst { it == bracketArg }
            val value = seqGet(mapValues(exprThis) ?: emptyList(), index)
            setType(expression, value?.type)
        } else {
            setType(expression, DType.UNKNOWN)
        }

        return expression
    }

    // sqlglot: array.Map.keys / VarMap.keys (annotate_bracket's `this.keys` lookup)
    private fun mapKeys(node: Expression): List<Expression>? = when (node) {
        is dev.brikk.house.sql.ast.Map, is VarMap ->
            (node.args["keys"] as? Expression)?.expressionsArg?.filterIsInstance<Expression>()
                ?: emptyList()
        else -> null
    }

    // sqlglot: array.Map.values / VarMap.values
    private fun mapValues(node: Expression): List<Expression>? = when (node) {
        is dev.brikk.house.sql.ast.Map, is VarMap ->
            (node.args["values"] as? Expression)?.expressionsArg?.filterIsInstance<Expression>()
                ?: emptyList()
        else -> null
    }

    // sqlglot: TypeAnnotator._annotate_div
    private fun annotateDiv(expression: Expression): Expression {
        val left = expression.args["this"] as Expression
        val right = expression.args["expression"] as Expression
        val leftType = (left.type as DataType).thisArg
        val rightType = (right.type as DataType).thisArg

        if (
            truthy(expression.args["typed"]) &&
            leftType in DataType.INTEGER_TYPES &&
            rightType in DataType.INTEGER_TYPES
        ) {
            setType(expression, DType.BIGINT)
        } else {
            setType(expression, maybeCoerce(leftType, rightType))
            val newType = expression.type as? DataType
            if (newType != null && newType.thisArg !in DataType.REAL_TYPES) {
                setType(expression, maybeCoerce(newType, DType.DOUBLE))
            }
        }

        return expression
    }

    // sqlglot: TypeAnnotator._annotate_dot
    private fun annotateDot(expression: Dot): Expression {
        setType(expression, null)

        // Propagate type from qualified UDF calls (e.g., db.my_udf(...))
        if (expression.expressionArg is Anonymous) {
            setType(expression, (expression.expressionArg as Expression).type)
            return expression
        }

        val thisType = (expression.thisArg as Expression).type as? DataType

        if (thisType != null && thisType.isType(DType.STRUCT)) {
            for (e in thisType.expressionsArg.filterIsInstance<Expression>()) {
                if (e.name == (expression.expressionArg as Expression).name) {
                    setType(expression, e.args["kind"])
                    break
                }
            }
        }

        return expression
    }

    // sqlglot: TypeAnnotator._annotate_explode
    private fun annotateExplode(expression: Expression): Expression {
        val thisType = ((expression.thisArg as Expression).type as DataType)
        setType(expression, seqGet(thisType.expressionsArg.filterIsInstance<Expression>(), 0))
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_unnest
    private fun annotateUnnest(expression: Expression): Expression {
        val child = seqGet(expression.expressionsArg.filterIsInstance<Expression>(), 0)

        val exprType =
            if (child != null && child.isType(DType.ARRAY)) {
                seqGet(
                    (child.type as DataType).expressionsArg.filterIsInstance<Expression>(), 0
                )
            } else {
                null
            }

        setType(expression, exprType)
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_subquery
    private fun annotateSubquery(expression: Subquery): Expression {
        // For scalar subqueries (subqueries with a single projection), infer the type
        // from that single projection.
        val query = expression.unnest()

        if (query is Query) {
            val selects = query.selects
            if (selects.size == 1) {
                setType(expression, selects[0].type)
                return expression
            }
        }

        setType(expression, DType.UNKNOWN)
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_struct_value — returns a DataType, a ColumnDef,
    // or null (unknown-typed field)
    private fun annotateStructValue(expression: Expression): Expression? {
        // Case: STRUCT(key AS value)
        var fieldThis: Expression? = null
        var kind: Expression? = expression.type

        val alias = expression.args["alias"] as? Expression
        if (alias != null) {
            fieldThis = alias.copy()
        } else if (expression.args["expression"] is Expression) {
            // Case: STRUCT(key = value) or STRUCT(key := value)
            fieldThis = (expression.thisArg as Expression).copy()
            kind = (expression.expressionArg as Expression).type
        } else if (expression is Column) {
            // Case: STRUCT(c)
            fieldThis = (expression.thisArg as Expression).copy()
        }

        if (kind != null && (kind as DataType).isType(DType.UNKNOWN)) {
            return null
        }

        if (fieldThis != null) {
            return ColumnDef(args("this" to fieldThis, "kind" to kind))
        }

        return kind
    }

    // sqlglot: TypeAnnotator._annotate_struct
    private fun annotateStruct(expression: Expression): Expression {
        val expressions = mutableListOf<Expression>()
        for (expr in expression.expressionsArg.filterIsInstance<Expression>()) {
            val structFieldType = annotateStructValue(expr)
            if (structFieldType == null) {
                setType(expression, null)
                return expression
            }

            expressions.add(structFieldType)
        }

        setType(
            expression,
            DataType(
                args("this" to DType.STRUCT, "expressions" to expressions, "nested" to true)
            ),
        )
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_map
    private fun annotateMap(expression: Expression): Expression {
        val keys = expression.args["keys"]
        val values = expression.args["values"]

        val mapType = DataType(args("this" to DType.MAP))
        if (keys is dev.brikk.house.sql.ast.Array && values is dev.brikk.house.sql.ast.Array) {
            val keyType: Any = seqGet(
                (keys.type as DataType).expressionsArg.filterIsInstance<Expression>(), 0
            ) ?: DType.UNKNOWN
            val valueType: Any = seqGet(
                (values.type as DataType).expressionsArg.filterIsInstance<Expression>(), 0
            ) ?: DType.UNKNOWN

            if (keyType != DType.UNKNOWN && valueType != DType.UNKNOWN) {
                mapType.set("expressions", listOf(keyType, valueType))
                mapType.set("nested", true)
            }
        }

        setType(expression, mapType)
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_to_map
    private fun annotateToMap(expression: Expression): Expression {
        val mapType = DataType(args("this" to DType.MAP))
        val arg = expression.thisArg as Expression
        if (arg.isType(DType.STRUCT)) {
            for (coldef in (arg.type as DataType).expressionsArg.filterIsInstance<Expression>()) {
                val kind = coldef.args["kind"]
                if (kind != DType.UNKNOWN) {
                    mapType.set("expressions", listOf(DType.VARCHAR.intoExpr(), kind))
                    mapType.set("nested", true)
                    break
                }
            }
        }

        setType(expression, mapType)
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_extract
    private fun annotateExtract(expression: Expression): Expression {
        val part = expression.name
        when {
            part == "TIME" -> setType(expression, DType.TIME)
            part == "DATE" -> setType(expression, DType.DATE)
            part in GeneratedTypingMetadata.BIGINT_EXTRACT_DATE_PARTS ->
                setType(expression, DType.BIGINT)
            else -> setType(expression, DType.INT)
        }
        return expression
    }

    // sqlglot: TypeAnnotator._annotate_within_group
    private fun annotateWithinGroup(expression: Expression): Expression {
        if (expression.thisArg is PercentileDisc) {
            val order = expression.args["expression"] as? Expression
            val orderExpressions = order?.expressionsArg?.filterIsInstance<Expression>()
            val sortType: Any? =
                if (!orderExpressions.isNullOrEmpty()) {
                    (orderExpressions[0].thisArg as Expression).type
                } else {
                    DType.UNKNOWN
                }
            setType(expression, sortType)
            return expression
        }

        return annotateByArgs(expression, listOf("this"))
    }

    // sqlglot: TypeAnnotator._annotate_by_array_element
    private fun annotateByArrayElement(expression: Expression): Expression {
        val arrayArg = expression.thisArg as Expression
        val arrayType = arrayArg.type as? DataType
        if (arrayType != null && arrayType.isType(DType.ARRAY)) {
            val elementType: Any =
                seqGet(arrayType.expressionsArg.filterIsInstance<Expression>(), 0)
                    ?: DType.UNKNOWN
            setType(expression, elementType)
        } else {
            setType(expression, DType.UNKNOWN)
        }

        return expression
    }
}
