package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Any as AnyNode
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.parser.PostgresTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.Int
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.Set
import kotlin.reflect.KClass

// sqlglot: dialect.unit_to_str
private fun unitToStr(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression
        ?: return if (default.isNotEmpty()) Literal.string(default) else null

    if (unit is Placeholder || (unit !is Var && unit !is Literal)) return unit

    return Literal.string(unit.name)
}

/**
 * Port of sqlglot's PostgresGenerator (reference/sqlglot/sqlglot/generators/postgres.py).
 * TRANSFORMS entries live in [TRANSFORMS] (a dispatch-map overlay handed to the base
 * constructor and accepting a further overlay for subclasses — Redshift/Materialize/
 * RisingWave extend Postgres in sqlglot); flag overrides are open-val overrides.
 *
 * NOT PORTED (no Kotlin equivalents of sqlglot's transforms/annotate_types yet):
 * the exp.Select preprocess pipeline (eliminate_semi_and_anti_joins, eliminate_qualify);
 * the annotate_types-dependent branches of unnest_sql (array<json> ->
 * JSON_ARRAY_ELEMENTS) and _round_sql (DOUBLE input detection is approximated for
 * literals); _date_add_sql's _simplify_unless_literal is approximated by the identity.
 * Mismatches are ledgered.
 */
// sqlglot: generators.postgres.PostgresGenerator
open class PostgresGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    normalizeFunctions: kotlin.Any = "upper",
    tokenizerConfig: TokenizerConfig = PostgresTokenizerTables.CONFIG,
    // extra dispatch overlay for subclasses (sqlglot: further TRANSFORMS merges)
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
) : Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    normalizeFunctions = normalizeFunctions,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
) {

    // ------------------------------------------------------------------
    // Flags (sqlglot: PostgresGenerator class attributes)
    // ------------------------------------------------------------------

    override val selectKinds: Set<String> get() = emptySet()
    override val trySupported: Boolean get() = false
    override val supportsUescape: Boolean get() = false
    override val singleStringInterval: Boolean get() = true
    override val renameTableWithDb: Boolean get() = false
    override val lockingReadsSupported: Boolean get() = true
    override val joinHints: Boolean get() = false
    override val tableHints: Boolean get() = false
    override val queryHints: Boolean get() = false
    override val nvl2Supported: Boolean get() = false
    override val parameterToken: String get() = "$"
    override val namedPlaceholderToken: String get() = "%"
    override val tablesampleSizeIsRows: Boolean get() = false
    override val tablesampleSeedKeyword: String get() = "REPEATABLE"
    override val supportsSelectInto: Boolean get() = true
    override val supportsUnloggedTables: Boolean get() = true
    override val likePropertyInsideSchema: Boolean get() = true
    override val multiArgDistinct: Boolean get() = false
    override val supportsWindowExclude: Boolean get() = true
    override val copyHasIntoKeyword: Boolean get() = false
    override val supportsMedian: Boolean get() = false
    override val arraySizeDimRequired: Boolean? get() = true
    override val supportsBetweenFlags: Boolean get() = true
    // PostgreSQL uses "INOUT" (no space)
    override val inoutSeparator: String get() = ""

    // sqlglot: Postgres BYTE_STRINGS = [("e'", "'"), ("E'", "'")] (tokenizer-derived);
    // BYTE_STRING_ESCAPES contains "\\" so escaped sequences are supported
    override val byteStart: String? get() = "e'"
    override val byteEnd: String? get() = "'"
    override val dialectByteStringsSupportEscapedSequences: Boolean get() = true
    override val escapedSequences: Map<String, String> get() = MysqlGenerator.ESCAPED_SEQUENCES

    // sqlglot: Postgres dialect-level flags read by the generator
    override val dialectTablesampleSizeIsPercent: Boolean get() = true
    override val dialectNullOrdering: String get() = "nulls_are_large"
    override val dialectIndexOffset: Int get() = 1
    override val dialectTypedDivision: Boolean get() = true
    override val dialectConcatCoalesce: Boolean get() = true
    override val dialectConcatWsCoalesce: Boolean get() = true
    override val dialectTimeFormat: String get() = "'YYYY-MM-DD HH24:MI:SS'"
    override val inverseTimeMapping: Map<String, String>
        get() = PostgresDialect.INVERSE_TIME_MAPPING

    // sqlglot: PostgresGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: PostgresGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // sqlglot: dialect.rename_func
    internal fun renameFuncSql(name: String, expression: Expression): String {
        val flattened = expression.args.values.flatMap { v ->
            if (v is List<*>) v else listOf(v)
        }
        return func(name, *flattened.toTypedArray())
    }

    // sqlglot: dialect.Dialect.normalize_identifier for postgres (lowercases
    // case-insensitive, i.e. unquoted, identifiers)
    private fun normalizeIdentifierName(identifier: Expression?): String? {
        if (identifier == null) return null
        val quoted = (identifier as? Identifier)?.args?.get("quoted") == true
        return if (quoted) identifier.name else identifier.name.lowercase()
    }

    // ------------------------------------------------------------------
    // Methods (sqlglot: PostgresGenerator methods + module-level helpers)
    // ------------------------------------------------------------------

    // sqlglot: PostgresGenerator.lateral_sql
    override fun lateralSql(expression: Lateral): String {
        var sql = super.lateralSql(expression)

        if (expression.args.containsKey("cross_apply") && expression.args["cross_apply"] != null) {
            sql = "$sql ON TRUE"
        }

        return sql
    }

    // sqlglot: PostgresGenerator.schemacommentproperty_sql
    open fun schemacommentpropertySql(expression: SchemaCommentProperty): String {
        unsupported("Table comments are not supported in the CREATE statement")
        return ""
    }

    // sqlglot: PostgresGenerator.commentcolumnconstraint_sql
    open fun commentcolumnconstraintSql(expression: CommentColumnConstraint): String {
        unsupported("Column comments are not supported in the CREATE statement")
        return ""
    }

    // sqlglot: generators.postgres._auto_increment_to_serial
    protected fun autoIncrementToSerial(expression: Expression): Expression {
        val auto = expression.find(AutoIncrementColumnConstraint::class)

        if (auto != null) {
            val constraints =
                (expression.args["constraints"] as? List<*>)?.toMutableList() ?: mutableListOf()
            constraints.remove(auto.parent)
            expression.set("constraints", constraints)
            val kind = expression.args["kind"] as? DataType

            when (kind?.thisArg) {
                DType.INT -> kind.replace(DataType(args("this" to DType.SERIAL)))
                DType.SMALLINT -> kind.replace(DataType(args("this" to DType.SMALLSERIAL)))
                DType.BIGINT -> kind.replace(DataType(args("this" to DType.BIGSERIAL)))
                else -> {}
            }
        }

        return expression
    }

    // sqlglot: generators.postgres._serial_to_generated
    protected fun serialToGenerated(expression: Expression): Expression {
        if (expression !is ColumnDef) return expression
        val kind = expression.args["kind"] as? DataType ?: return expression

        val dataType = when (kind.thisArg) {
            DType.SERIAL -> DataType(args("this" to DType.INT))
            DType.SMALLSERIAL -> DataType(args("this" to DType.SMALLINT))
            DType.BIGSERIAL -> DataType(args("this" to DType.BIGINT))
            else -> null
        }

        if (dataType != null) {
            kind.replace(dataType)
            val constraints =
                (expression.args["constraints"] as? List<*>)?.toMutableList() ?: mutableListOf()
            val generated = ColumnConstraint(
                args(
                    "kind" to GeneratedAsIdentityColumnConstraint(args("this" to false))
                )
            )
            val notnull = ColumnConstraint(args("kind" to NotNullColumnConstraint()))

            if (constraints.none { it == notnull }) {
                constraints.add(0, notnull)
            }
            if (constraints.none { it == generated }) {
                constraints.add(0, generated)
            }
            expression.set("constraints", constraints)
        }

        return expression
    }

    // sqlglot: PostgresGenerator.columndef_sql (+ the ColumnDef preprocess pipeline)
    override fun columndefSql(expression: ColumnDef, sep: String): String {
        var expr: Expression = expression
        expr = autoIncrementToSerial(expr)
        expr = serialToGenerated(expr)

        // PostgreSQL places parameter modes BEFORE parameter name
        val paramConstraint = expr.find(InOutColumnConstraint::class)

        if (paramConstraint != null) {
            val modeSql = sql(paramConstraint)
            paramConstraint.pop() // Remove to prevent double-rendering
            val baseSql = super.columndefSql(expr as ColumnDef, sep)
            return "$modeSql $baseSql"
        }

        return super.columndefSql(expr as ColumnDef, sep)
    }

    // sqlglot: PostgresGenerator.unnest_sql — the annotate_types-driven `array<json>`
    // -> JSON_ARRAY_ELEMENTS branch is NOT PORTED (requires type inference); only the
    // GenerateDateArray rewrite is ported.
    override fun unnestSql(expression: Unnest): String {
        if (expression.expressionsArg.size == 1) {
            val arg = expression.expressionsArg[0]
            if (arg is GenerateDateArray) {
                var generateSeries: Expression = GenerateSeries(args(*arg.args.entries.map { it.key to it.value }.toTypedArray()))
                if (expression.parent is From || expression.parent is Join) {
                    // exp.select("value::date").from_(exp.Table(this=...).as_("_t", table=["value"]))
                    //     .subquery(alias or "_unnested_generate_series")
                    val aliasArg = expression.args["alias"]
                    val subqueryAlias: Expression = when {
                        aliasArg is TableAlias -> aliasArg
                        aliasArg is Expression -> TableAlias(args("this" to aliasArg))
                        else -> TableAlias(
                            args(
                                "this" to Identifier(
                                    args("this" to "_unnested_generate_series")
                                )
                            )
                        )
                    }
                    generateSeries = Subquery(
                        args(
                            "this" to Select(
                                args(
                                    "expressions" to listOf(
                                        Cast(
                                            args(
                                                "this" to Column(
                                                    args("this" to Identifier(args("this" to "value")))
                                                ),
                                                "to" to DataType(args("this" to DType.DATE)),
                                            )
                                        )
                                    ),
                                    "from_" to From(
                                        args(
                                            "this" to Table(
                                                args(
                                                    "this" to generateSeries,
                                                    "alias" to TableAlias(
                                                        args(
                                                            "this" to Identifier(args("this" to "_t")),
                                                            "columns" to listOf(
                                                                Identifier(args("this" to "value"))
                                                            ),
                                                        )
                                                    ),
                                                )
                                            )
                                        )
                                    ),
                                )
                            ),
                            "alias" to subqueryAlias,
                        )
                    )
                }
                return sql(generateSeries)
            }
        }

        return super.unnestSql(expression)
    }

    // sqlglot: PostgresGenerator.bracket_sql — forms like ARRAY[1, 2, 3][3] aren't
    // allowed; we need to wrap the ARRAY
    override fun bracketSql(expression: Bracket): String {
        if (expression.thisArg is ArrayNode) {
            expression.set("this", Paren(args("this" to expression.thisArg)))
        }

        return super.bracketSql(expression)
    }

    // sqlglot: PostgresGenerator.matchagainst_sql
    override fun matchagainstSql(expression: MatchAgainst): String {
        val this_ = sql(expression, "this")
        val expressionsSql = expression.expressionsArg.map { "${sql(it)} @@ $this_" }
        val joined = expressionsSql.joinToString(" OR ")
        return if (expressionsSql.size > 1) "($joined)" else joined
    }

    // sqlglot: PostgresGenerator.alterset_sql
    override fun altersetSql(expression: AlterSet): String {
        var exprs = expressions(expression, flat = true)
        exprs = if (exprs.isNotEmpty()) "($exprs)" else ""

        var accessMethod = sql(expression, "access_method")
        accessMethod = if (accessMethod.isNotEmpty()) "ACCESS METHOD $accessMethod" else ""
        var tablespace = sql(expression, "tablespace")
        tablespace = if (tablespace.isNotEmpty()) "TABLESPACE $tablespace" else ""
        val option = sql(expression, "option")

        return "SET $exprs$accessMethod$tablespace$option"
    }

    // sqlglot: PostgresGenerator.datatype_sql
    open fun postgresDatatypeSql(expression: DataType): String {
        if (expression.thisArg == DType.ARRAY) {
            if (expression.expressionsArg.isNotEmpty()) {
                val values = expressions(expression, key = "values", flat = true)
                return "${expressions(expression, flat = true)}[$values]"
            }
            return "ARRAY"
        }

        if (expression.thisArg == DType.ENUM) {
            return "ENUM (${expressions(expression, flat = true)})"
        }

        if ((expression.thisArg == DType.DOUBLE || expression.thisArg == DType.FLOAT) &&
            expression.expressionsArg.isNotEmpty()
        ) {
            // Postgres doesn't support precision for REAL and DOUBLE PRECISION types
            return "FLOAT(${expressions(expression, flat = true)})"
        }

        return datatypeSql(expression)
    }

    // sqlglot: PostgresGenerator.cast_sql
    override fun castSql(expression: Cast, safePrefix: String?): String {
        val this_ = expression.thisArg

        // Postgres casts DIV() to decimal for transpilation but when roundtripping it's superfluous
        val to = expression.args["to"] as? DataType
        if (this_ is IntDiv &&
            to?.thisArg == DType.DECIMAL &&
            to.expressionsArg.isEmpty() &&
            to.args["nested"] != true
        ) {
            return sql(this_)
        }

        return super.castSql(expression, safePrefix = safePrefix)
    }

    // sqlglot: PostgresGenerator.array_sql
    open fun arraySql(expression: ArrayNode): String {
        val exprs = expression.expressionsArg
        val funcName = normalizeFunc("ARRAY")

        // sqlglot: isinstance(seq_get(exprs, 0), exp.Query) — the Kotlin Select/set-op
        // nodes don't implement the Query marker interface, so enumerate them
        val first = exprs.firstOrNull()
        if (first is Select || first is SetOperation || first is Subquery) {
            return "$funcName(${sql(exprs[0])})"
        }

        // sqlglot: dialect.inline_array_sql
        val inline = "[" + expressions(
            expression, dynamic = true, newLine = true, skipFirst = true, skipLast = true
        ) + "]"
        return "$funcName$inline"
    }

    // sqlglot: PostgresGenerator.computedcolumnconstraint_sql
    override fun computedcolumnconstraintSql(expression: ComputedColumnConstraint): String =
        "GENERATED ALWAYS AS (${sql(expression, "this")}) STORED"

    // sqlglot: PostgresGenerator.isascii_sql
    open fun isasciiSql(expression: IsAscii): String =
        "(${sql(expression.thisArg)} ~ '^[[:ascii:]]*\$')"

    // sqlglot: PostgresGenerator.ignorenulls_sql
    override fun ignorenullsSql(expression: IgnoreNulls): String {
        // https://www.postgresql.org/docs/current/functions-window.html
        unsupported("PostgreSQL does not support IGNORE NULLS.")
        return sql(expression, "this")
    }

    // sqlglot: PostgresGenerator.respectnulls_sql
    override fun respectnullsSql(expression: RespectNulls): String {
        // https://www.postgresql.org/docs/current/functions-window.html
        unsupported("PostgreSQL does not support RESPECT NULLS.")
        return sql(expression, "this")
    }

    // sqlglot: PostgresGenerator.currentschema_sql (@unsupported_args("this"))
    open fun currentschemaSql(expression: CurrentSchema): String {
        if (expression.args["this"] != null) {
            unsupported("Argument 'this' is not supported for expression 'CurrentSchema'")
        }
        return "CURRENT_SCHEMA"
    }

    // sqlglot: PostgresGenerator.interval_sql
    override fun intervalSql(expression: Interval): String {
        val unit = (expression.args["unit"] as? Expression)?.name?.lowercase() ?: ""

        val this_ = expression.thisArg
        if (unit.startsWith("quarter") && this_ is Literal) {
            val months = (this_.name.toIntOrNull() ?: 0) * 3
            this_.replace(Literal.string(months.toString()))
            (expression.args["unit"] as Expression).replace(Var(args("this" to "MONTH")))
        }

        return super.intervalSql(expression)
    }

    // sqlglot: PostgresGenerator.placeholder_sql
    override fun placeholderSql(expression: Placeholder): String {
        if (expression.args["jdbc"] == true) {
            return "?"
        }

        val this_ = if (expression.args["this"] != null) "(${expression.name})" else ""
        return "$namedPlaceholderToken${this_}s"
    }

    // sqlglot: PostgresGenerator.arraycontains_sql — CASE WHEN value IS NULL THEN NULL
    // ELSE COALESCE(value = ANY(array), FALSE) END
    open fun arraycontainsSql(expression: ArrayContains): String {
        val value = expression.args["expression"] as? Expression
        val array = expression.thisArg as? Expression

        val coalesceExpr = Coalesce(
            args(
                "this" to EQ(
                    args(
                        "this" to value?.copy(),
                        "expression" to AnyNode(
                            args("this" to Paren(args("this" to array?.copy())))
                        ),
                    )
                ),
                "expressions" to listOf(BooleanNode(args("this" to false))),
            )
        )

        val caseExpr = Case(
            args(
                "ifs" to listOf(
                    If(
                        args(
                            "this" to Is(args("this" to value?.copy(), "expression" to Null())),
                            "true" to Null(),
                        )
                    )
                ),
                "default" to coalesceExpr,
            )
        )

        return sql(caseExpr)
    }

    // sqlglot: generators.postgres._date_add_sql — _simplify_unless_literal is
    // approximated by the identity (the optimizer's simplify is not ported)
    open fun dateAddSql(expression: Expression, kind: String): String {
        var expr = expression
        if (expr is TsOrDsAdd) {
            expr = tsOrDsAddCast(expr)
        }

        val this_ = sql(expr, "this")
        val unit = expr.args["unit"]

        var e = expr.args["expression"] as? Expression
        if (e is Interval) {
            return "$this_ $kind ${sql(e)}"
        } else if (e is Literal && e.isString) {
            // handled below (is_string stays true)
        } else if (e is Literal) {
            e.set("is_string", true)
        } else if (e != null && e.isNumber) {
            e = Literal.string(e.name)
        } else {
            val one = Literal.number("1")
            val intervalTimesValue = Mul(
                args(
                    "this" to Interval(args("this" to one, "unit" to unit)),
                    "expression" to e,
                )
            )
            return "$this_ $kind ${sql(intervalTimesValue)}"
        }

        return "$this_ $kind ${sql(Interval(args("this" to e, "unit" to unit)))}"
    }

    // sqlglot: dialect.ts_or_ds_add_cast
    protected fun tsOrDsAddCast(expression: TsOrDsAdd): TsOrDsAdd {
        var this_: Expression? = (expression.thisArg as? Expression)?.copy()

        // sqlglot: expression.return_type
        val returnType = (expression.args["return_type"] as? DataType)
            ?: DataType(args("this" to DType.DATE))
        if (returnType.thisArg == DType.DATE) {
            // If we need to cast to a DATE, we cast to TIMESTAMP first to make sure we
            // can truncate timestamp strings, because some dialects can't cast them to DATE
            this_ = Cast(args("this" to this_, "to" to DataType(args("this" to DType.TIMESTAMP))))
        }

        (expression.thisArg as Expression).replace(
            Cast(args("this" to this_, "to" to returnType.copy()))
        )
        return expression
    }

    // sqlglot: generators.postgres._date_diff_sql
    open fun dateDiffSql(expression: Expression): String {
        val unit = ((expression.args["unit"] as? Expression)?.name ?: "").uppercase()
        val factor = DATE_DIFF_FACTOR[unit]

        val end = "CAST(${sql(expression, "this")} AS TIMESTAMP)"
        val start = "CAST(${sql(expression, "expression")} AS TIMESTAMP)"

        if (factor != null) {
            return "CAST(EXTRACT(epoch FROM $end - $start)$factor AS BIGINT)"
        }

        val age = "AGE($end, $start)"

        val unitSql = when (unit) {
            "WEEK" -> "EXTRACT(days FROM ($end - $start)) / 7"
            "MONTH" -> "EXTRACT(year FROM $age) * 12 + EXTRACT(month FROM $age)"
            "QUARTER" -> "EXTRACT(year FROM $age) * 4 + EXTRACT(month FROM $age) / 3"
            "YEAR" -> "EXTRACT(year FROM $age)"
            else -> age
        }

        return "CAST($unitSql AS BIGINT)"
    }

    // sqlglot: generators.postgres._substring_sql
    open fun substringSql(expression: Substring): String {
        val this_ = sql(expression, "this")
        val start = sql(expression, "start")
        val length = sql(expression, "length")

        val fromPart = if (start.isNotEmpty()) " FROM $start" else ""
        val forPart = if (length.isNotEmpty()) " FOR $length" else ""

        return "SUBSTRING($this_$fromPart$forPart)"
    }

    // sqlglot: dialect.json_extract_segments
    open fun jsonExtractSegments(
        expression: Expression,
        name: String,
        quotedIndex: Boolean = true,
        op: String? = null,
    ): String {
        val path = expression.args["expression"]
        if (path !is JSONPath) return renameFuncSql(name, expression)

        val segments = mutableListOf<String>()
        for (segment in path.expressionsArg) {
            val seg = segment as? Expression ?: continue
            val escape = seg.args["quoted"] == true
            var segSql = sql(seg)
            if (segSql.isNotEmpty()) {
                if (seg is JSONPathPart && (quotedIndex || seg !is JSONPathSubscript)) {
                    if (escape) segSql = escapeStr(segSql)
                    segSql = "$quoteStart$segSql$quoteEnd"
                }
                segments.add(segSql)
            }
        }

        if (op != null) {
            return (listOf(sql(expression, "this")) + segments).joinToString(" $op ")
        }
        return func(name, expression.thisArg, *segments.toTypedArray())
    }

    // sqlglot: generators.postgres._json_extract_sql
    open fun postgresJsonExtractSql(expression: Expression, name: String, op: String): String {
        if (expression.args["only_json_types"] == true) {
            return jsonExtractSegments(expression, name, quotedIndex = false, op = op)
        }
        return jsonExtractSegments(expression, name)
    }

    // sqlglot: generators.postgres._unix_to_time_sql
    open fun unixToTimeSql(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        val timestamp = expression.thisArg

        // sqlglot: exp.UnixToTime.SECONDS = Literal.number(0)
        val isSeconds = scale == null ||
            (scale is Literal && !scale.isString && scale.name == "0")
        if (isSeconds) {
            return func("TO_TIMESTAMP", timestamp, formatTime(expression))
        }

        return func(
            "TO_TIMESTAMP",
            Div(
                args(
                    "this" to timestamp,
                    // sqlglot: exp.func("POW", 10, scale) resolves to exp.Pow
                    "expression" to Pow(args("this" to Literal.number("10"), "expression" to scale)),
                )
            ),
            formatTime(expression),
        )
    }

    // sqlglot: generators.postgres._levenshtein_sql
    open fun levenshteinSql(expression: Levenshtein): String {
        val name = if (expression.args["max_dist"] != null) "LEVENSHTEIN_LESS_EQUAL" else "LEVENSHTEIN"
        return renameFuncSql(name, expression)
    }

    // sqlglot: generators.postgres._round_sql — annotate_types is approximated: only
    // double-typed nodes (or float literals) trigger the DECIMAL cast
    open fun roundSql(expression: Round): String {
        val this_ = sql(expression, "this")
        val decimals = sql(expression, "decimals")

        if (decimals.isEmpty()) {
            return func("ROUND", this_)
        }

        // ROUND(double precision, integer) is not permitted in Postgres
        // so it's necessary to cast to decimal before rounding.
        val arg = expression.thisArg as? Expression
        val argType = (arg?.type as? DataType)?.thisArg
        val isDoubleLiteral = arg is Literal && !arg.isString &&
            (arg.name.contains('.') || arg.name.contains('e') || arg.name.contains('E'))
        val thisSql = if (argType == DType.DOUBLE || (argType == null && isDoubleLiteral)) {
            sql(
                Cast(
                    args(
                        "this" to this_,
                        "to" to DataType(
                            args(
                                "this" to DType.DECIMAL,
                                "expressions" to expression.expressionsArg,
                            )
                        ),
                    )
                )
            )
        } else {
            this_
        }

        return func("ROUND", thisSql, decimals)
    }

    // sqlglot: dialect.getbit_sql (annotate-free: type info is only present when set)
    open fun getbitSql(expression: Getbit): String {
        val value = expression.thisArg
        val position = expression.args["expression"]

        val type = (expression.type as? DataType)?.thisArg
        if (expression.args["zero_is_msb"] != true &&
            type != null && type in DuckdbGenerator.INTEGER_TYPES
        ) {
            // Use bitwise operations: (value >> position) & 1
            val shifted = BitwiseRightShift(args("this" to value, "expression" to position))
            val masked = BitwiseAnd(args("this" to shifted, "expression" to Literal.number("1")))
            return sql(masked)
        }

        return func("GET_BIT", value, position)
    }

    // sqlglot: dialect.groupconcat_sql(func_name="STRING_AGG", within_group=False)
    open fun groupconcatSql(expression: GroupConcat): String {
        val funcName = "STRING_AGG"
        var this_ = expression.thisArg as Expression
        val separatorExpr = (expression.args["separator"] as? Expression) ?: Literal.string(",")
        val separator = sql(separatorExpr)

        var limit: Limit? = null
        if (this_ is Limit && this_.thisArg != null) {
            limit = this_
            this_ = this_.thisArg as Expression
            limit.set("this", null)
        }

        val order = this_.find(Order::class) as? Order

        if (order != null && order.thisArg != null) {
            this_ = order.thisArg as Expression
            order.set("this", null)
        }

        val argsSql = formatArgs(this_, if (separator.isNotEmpty()) separator else null)

        val listagg: Expression = Anonymous(args("this" to funcName, "expressions" to listOf(argsSql)))

        var modifiers = if (limit != null) sql(limit) else ""

        if (order != null) {
            // within_group=False
            modifiers = "${sql(order)}$modifiers"
        }

        if (modifiers.isNotEmpty()) {
            listagg.set("expressions", listOf("$argsSql$modifiers"))
        }

        return sql(listagg)
    }

    // sqlglot: dialect.strposition_sql(func_name="POSITION") — ANSI POSITION(a IN b)
    open fun strpositionSql(expression: StrPosition): String {
        var string = expression.thisArg as? Expression
        val substr = expression.args["substr"]
        val position = expression.args["position"] as? Expression
        val occurrence = expression.args["occurrence"]
        val zero = Literal.number("0")
        val one = Literal.number("1")

        val transpilePosition = position != null
        if (transpilePosition) {
            string = Substring(args("this" to string, "start" to position))
        }

        val funcExpr: Expression = Anonymous(
            args(
                "this" to "POSITION",
                "expressions" to listOf(In(args("this" to substr, "field" to string))),
            )
        )
        if (occurrence != null) {
            unsupported("POSITION does not support the occurrence parameter.")
        }

        if (transpilePosition) {
            val funcWithOffset = Sub(
                args(
                    "this" to Add(args("this" to funcExpr, "expression" to position)),
                    "expression" to one,
                )
            )
            val funcWrapped = If(
                args(
                    "this" to EQ(args("this" to funcExpr.copy(), "expression" to zero)),
                    "true" to zero,
                    "false" to funcWithOffset,
                )
            )
            return sql(funcWrapped)
        }

        return sql(funcExpr)
    }

    // sqlglot: dialect.struct_extract_sql
    open fun structExtractSql(expression: StructExtract): String {
        val name = (expression.args["expression"] as? Expression)?.name ?: ""
        return "${sql(expression, "this")}.${sql(Identifier(args("this" to name)))}"
    }

    // sqlglot: dialect.count_if_to_sum
    open fun countIfToSumSql(expression: CountIf): String {
        var cond = expression.thisArg as? Expression

        if (cond is Distinct) {
            cond = cond.expressionsArg.firstOrNull() as? Expression
            unsupported("DISTINCT is not supported when converting COUNT_IF to SUM")
        }

        return func(
            "sum",
            Anonymous(
                args(
                    "this" to "if",
                    "expressions" to listOf(cond, Literal.number("1"), Literal.number("0")),
                )
            ),
        )
    }

    // sqlglot: dialect.bool_xor_sql
    open fun boolXorSql(expression: Xor): String {
        val a = sql(expression.thisArg)
        val b = sql(expression.args["expression"])
        return "($a AND (NOT $b)) OR ((NOT $a) AND $b)"
    }

    // sqlglot: dialect.no_last_day_sql
    open fun noLastDaySql(expression: LastDay): String {
        val truncCurrDate = Anonymous(
            args(
                "this" to "date_trunc",
                "expressions" to listOf(Literal.string("month"), expression.thisArg),
            )
        )
        val plusOneMonth = Anonymous(
            args(
                "this" to "date_add",
                "expressions" to listOf(truncCurrDate, Literal.number("1"), Literal.string("month")),
            )
        )
        val minusOneDay = Anonymous(
            args(
                "this" to "date_sub",
                "expressions" to listOf(plusOneMonth, Literal.number("1"), Literal.string("day")),
            )
        )

        return sql(
            Cast(args("this" to minusOneDay, "to" to DataType(args("this" to DType.DATE))))
        )
    }

    // sqlglot: dialect.no_map_from_entries_sql
    open fun noMapFromEntriesSql(expression: MapFromEntries): String {
        unsupported("MAP_FROM_ENTRIES unsupported")
        return ""
    }

    // sqlglot: dialect.no_paren_current_date_sql
    open fun noParenCurrentDateSql(expression: CurrentDate): String {
        val zone = sql(expression, "this")
        return if (zone.isNotEmpty()) "CURRENT_DATE AT TIME ZONE $zone" else "CURRENT_DATE"
    }

    // sqlglot: dialect.no_pivot_sql
    open fun noPivotSql(expression: Pivot): String {
        unsupported("PIVOT unsupported")
        return ""
    }

    // sqlglot: dialect.max_or_greatest / min_or_least
    open fun maxOrGreatestSql(expression: Max): String =
        renameFuncSql(if (expression.expressionsArg.isNotEmpty()) "GREATEST" else "MAX", expression)

    open fun minOrLeastSql(expression: Min): String =
        renameFuncSql(if (expression.expressionsArg.isNotEmpty()) "LEAST" else "MIN", expression)

    // sqlglot: dialect.merge_without_target_sql — remove table refs from columns in
    // when statements
    open fun mergeWithoutTargetSql(expression: Merge): String {
        val target = expression.thisArg as? Expression
        val alias = target?.args?.get("alias") as? TableAlias

        val targets = mutableSetOf<String?>()
        targets.add(normalizeIdentifierName(target?.thisArg as? Expression))
        if (alias != null) {
            targets.add(normalizeIdentifierName(alias.thisArg as? Expression))
        }

        val whens = expression.args["whens"] as? Expression
        for (whenExpr in whens?.expressionsArg.orEmpty()) {
            val then = (whenExpr as? Expression)?.args?.get("then") as? Expression ?: continue
            if (then is Update) {
                for (equals in then.findAll(EQ::class)) {
                    val equalLhs = equals.thisArg
                    if (equalLhs is Column &&
                        normalizeIdentifierName(equalLhs.args["table"] as? Expression) in targets
                    ) {
                        equalLhs.replace(Column(args("this" to equalLhs.thisArg)))
                    }
                }
            }
            if (then is Insert) {
                val columnList = then.thisArg
                if (columnList is Tuple) {
                    for (column in columnList.expressionsArg) {
                        val col = column as? Column ?: continue
                        if (normalizeIdentifierName(col.args["table"] as? Expression) in targets) {
                            col.replace(Column(args("this" to col.thisArg)))
                        }
                    }
                }
            }
        }

        return mergeSql(expression)
    }

    // sqlglot: transforms.add_within_group_for_percentiles (exp.PercentileCont/Disc
    // preprocess) — rewrites PERCENTILE(x, q) into WITHIN GROUP form
    open fun addWithinGroupForPercentilesSql(expression: Expression): String {
        if (expression.parent !is WithinGroup && expression.args["expression"] != null) {
            val column = expression.thisArg as? Expression
            val quantile = expression.args["expression"] as? Expression
            expression.set("this", quantile?.copy())
            expression.set("expression", null)
            val order = Order(
                args("expressions" to listOf(Ordered(args("this" to column?.copy()))))
            )
            return sql(WithinGroup(args("this" to expression.copy(), "expression" to order)))
        }
        return functionFallbackSql(expression as Func)
    }

    // sqlglot: dialect.filter_array_using_unnest
    open fun filterArrayUsingUnnestSql(expression: Expression): String {
        val cond = expression.args["expression"] as? Expression
        val lambda = cond as? Lambda
        val innerCond: Expression?
        val alias: Expression?
        if (lambda != null) {
            innerCond = lambda.thisArg as? Expression
            alias = lambda.expressionsArg.firstOrNull() as? Expression
        } else {
            innerCond = cond
            alias = null
        }

        val unnest = Unnest(args("expressions" to listOf(expression.thisArg)))
        val select = Select(
            args(
                "expressions" to listOf(Column(args("this" to Star()))),
                "from_" to From(
                    args(
                        "this" to Subquery(
                            args(
                                "this" to unnest,
                                "alias" to TableAlias(
                                    args(
                                        "this" to (alias ?: Identifier(args("this" to "_u"))),
                                    )
                                ),
                            )
                        )
                    )
                ),
                "where" to Where(args("this" to innerCond)),
            )
        )
        return func("ARRAY", select)
    }

    // sqlglot: dialect.sha256_sql
    open fun sha256Sql(expression: SHA2): String {
        val length = (expression.args["length"] as? Expression)?.name ?: "256"
        return func("SHA$length", expression.thisArg)
    }

    // sqlglot: dialect.sha2_digest_sql
    open fun sha2DigestSql(expression: SHA2Digest): String {
        val length = (expression.args["length"] as? Expression)?.name ?: "256"
        return func("SHA$length", expression.thisArg)
    }

    // sqlglot: dialect.timestrtotime_sql (include_precision=false)
    open fun timestrtotimeSql(expression: TimeStrToTime): String {
        val dtype = if (expression.args["zone"] != null) DType.TIMESTAMPTZ else DType.TIMESTAMP
        return sql(
            Cast(args("this" to expression.thisArg, "to" to DataType(args("this" to dtype))))
        )
    }

    // sqlglot: dialect.datestrtodate_sql
    open fun datestrtodateSql(expression: DateStrToDate): String = sql(
        Cast(args("this" to expression.thisArg, "to" to DataType(args("this" to DType.DATE))))
    )

    // sqlglot: dialect.trim_sql
    open fun dialectTrimSql(expression: Trim): String {
        val removeChars = sql(expression, "expression")

        // Use TRIM/LTRIM/RTRIM syntax if the expression isn't database-specific
        if (removeChars.isEmpty()) {
            return trimSql(expression)
        }

        val target = sql(expression, "this")
        var trimType = sql(expression, "position")
        var collation = sql(expression, "collation")

        trimType = if (trimType.isNotEmpty()) "$trimType " else ""
        val removeCharsPart = "$removeChars "
        val fromPart = "FROM "
        collation = if (collation.isNotEmpty()) " COLLATE $collation" else ""
        return "TRIM($trimType$removeCharsPart$fromPart$target$collation)"
    }

    // sqlglot: dialect.no_trycast_sql
    open fun noTrycastSql(expression: TryCast): String = castSql(expression)

    // sqlglot: dialect.array_append_sql (ARRAY_FUNCS_PROPAGATES_NULLS=False target)
    open fun arrayAppendSql(expression: Expression, name: String, swapParams: Boolean): String {
        val this_ = expression.thisArg as? Expression
        val element = expression.args["expression"] as? Expression
        val callArgs = if (swapParams) listOf(element, this_) else listOf(this_, element)
        val funcSql = func(name, *callArgs.toTypedArray())

        val sourceNullPropagation = expression.args["null_propagation"] == true
        val targetNullPropagation = false

        if (sourceNullPropagation == targetNullPropagation) {
            return funcSql
        }

        // Source propagates NULLs, target doesn't: wrap in conditional
        return sql(
            If(
                args(
                    "this" to Is(args("this" to this_?.copy(), "expression" to Null())),
                    "true" to Null(),
                    "false" to funcSql,
                )
            )
        )
    }

    // sqlglot: dialect.array_concat_sql("ARRAY_CAT") with ARRAY_CONCAT_IS_VAR_LEN=False
    open fun arrayconcatSql(expression: ArrayConcat): String {
        val name = "ARRAY_CAT"
        val this_ = expression.thisArg as? Expression
        val exprs = expression.expressionsArg.filterIsInstance<Expression>()
        val allArgs = listOf(this_) + exprs

        // sqlglot: _build_func_call with ARRAY_CONCAT_IS_VAR_LEN=False — binary nesting
        fun buildFuncCall(argsList: List<Expression?>): String {
            if (argsList.size == 1) {
                // Single arg gets empty array to preserve semantics
                return func(name, argsList[0], ArrayNode(args("expressions" to emptyList<Expression>())))
            }
            var result = func(name, argsList[argsList.size - 2], argsList[argsList.size - 1])
            for (arg in argsList.dropLast(2).reversed()) {
                result = "$name(${sql(arg)}, $result)"
            }
            return result
        }

        val sourceNullPropagation = expression.args["null_propagation"] == true
        // Postgres: ARRAY_FUNCS_PROPAGATES_NULLS=False
        val targetNullPropagation = false

        if (sourceNullPropagation == targetNullPropagation ||
            this_ is ArrayNode || exprs.isEmpty()
        ) {
            return buildFuncCall(allArgs)
        }

        if (sourceNullPropagation) {
            // Source propagates NULLs, target doesn't: NULL-check every argument
            val nullChecks: List<Expression> = allArgs.map { arg ->
                Is(args("this" to arg?.copy(), "expression" to Null()))
            }
            val combinedCheck = nullChecks.reduce { a, b ->
                Or(args("this" to a, "expression" to b))
            }

            return sql(
                If(
                    args(
                        "this" to combinedCheck,
                        "true" to Null(),
                        "false" to buildFuncCall(allArgs),
                    )
                )
            )
        }

        // Source doesn't propagate NULLs, target does: COALESCE args to empty arrays
        val wrappedArgs = allArgs.map { arg ->
            Coalesce(
                args(
                    "expressions" to listOf(
                        arg?.copy(),
                        ArrayNode(args("expressions" to emptyList<Expression>())),
                    )
                )
            )
        }
        return buildFuncCall(wrappedArgs)
    }

    // sqlglot: dialect.generate_series_sql("GENERATE_SERIES")
    open fun generateseriesSql(expression: GenerateSeries): String {
        val start = expression.args["start"]
        val end = expression.args["end"]
        val step = expression.args["step"]

        if (expression.args["is_end_exclusive"] == true) {
            val adjustedEnd = Sub(args("this" to end, "expression" to Literal.number("1")))
            return func("GENERATE_SERIES", start, adjustedEnd, step)
        }

        return func("GENERATE_SERIES", start, end, step)
    }

    // sqlglot: dialect.regexp_replace_global_modifier
    protected fun regexpReplaceGlobalModifier(expression: RegexpReplace): Expression? {
        var modifiers = expression.args["modifiers"] as? Expression
        val singleReplace = expression.args["single_replace"] == true
        val occurrence = expression.args["occurrence"] as? Expression

        val occurrenceIsZero = occurrence == null ||
            (occurrence is Literal && !occurrence.isString && occurrence.name == "0")
        if (!singleReplace && occurrenceIsZero) {
            if (modifiers == null || modifiers.isString) {
                // Append 'g' to the modifiers if they are not provided since the
                // semantics of REGEXP_REPLACE from the input dialect is to replace
                // all occurrences of the pattern.
                val value = modifiers?.name ?: ""
                modifiers = Literal.string(value + "g")
            }
        }

        return modifiers
    }

    companion object {

        // sqlglot: generators.postgres.DATE_DIFF_FACTOR
        val DATE_DIFF_FACTOR: Map<String, String> = mapOf(
            "MICROSECOND" to " * 1000000",
            "MILLISECOND" to " * 1000",
            "SECOND" to "",
            "MINUTE" to " / 60",
            "HOUR" to " / 3600",
            "DAY" to " / 86400",
        )

        // sqlglot: generator.Generator.TYPE_MAPPING (the base entries Postgres inherits)
        private val BASE_TYPE_MAPPING: Map<DType, String> = mapOf(
            DType.DATETIME2 to "TIMESTAMP",
            DType.NCHAR to "CHAR",
            DType.NVARCHAR to "VARCHAR",
            DType.MEDIUMTEXT to "TEXT",
            DType.LONGTEXT to "TEXT",
            DType.TINYTEXT to "TEXT",
            DType.BLOB to "VARBINARY",
            DType.MEDIUMBLOB to "BLOB",
            DType.LONGBLOB to "BLOB",
            DType.TINYBLOB to "BLOB",
            DType.INET to "INET",
            DType.ROWVERSION to "VARBINARY",
            DType.SMALLDATETIME to "TIMESTAMP",
        )

        // sqlglot: PostgresGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = BASE_TYPE_MAPPING + mapOf(
            DType.TINYINT to "SMALLINT",
            DType.FLOAT to "REAL",
            DType.DOUBLE to "DOUBLE PRECISION",
            DType.BINARY to "BYTEA",
            DType.VARBINARY to "BYTEA",
            DType.ROWVERSION to "BYTEA",
            DType.DATETIME to "TIMESTAMP",
            DType.TIMESTAMPNTZ to "TIMESTAMP",
            DType.BLOB to "BYTEA",
        )

        // sqlglot: PostgresGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            GeneratorTables.PROPERTIES_LOCATION + mapOf(
                PartitionedByProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                TransientProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                VolatileProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
            )

        // sqlglot: PostgresGenerator.TRANSFORMS (dispatch-map overlay over the base)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.pg(): PostgresGenerator = this as PostgresGenerator

            // sqlglot: _versioned_anyvalue_sql — dialect.version defaults to the
            // latest, so the >= 16 rename path is taken
            reg(AnyValue::class) { e -> pg().renameFuncSql("ANY_VALUE", e) }
            reg(ArrayConcat::class) { e -> pg().arrayconcatSql(e as ArrayConcat) }
            reg(ArrayFilter::class) { e -> pg().filterArrayUsingUnnestSql(e) }
            reg(ArrayNode::class) { e -> pg().arraySql(e as ArrayNode) }
            reg(ArrayAppend::class) { e -> pg().arrayAppendSql(e, "ARRAY_APPEND", swapParams = false) }
            reg(ArrayPrepend::class) { e -> pg().arrayAppendSql(e, "ARRAY_PREPEND", swapParams = true) }
            reg(ArrayContains::class) { e -> pg().arraycontainsSql(e as ArrayContains) }
            reg(BitwiseAndAgg::class) { e -> pg().renameFuncSql("BIT_AND", e) }
            reg(BitwiseOrAgg::class) { e -> pg().renameFuncSql("BIT_OR", e) }
            reg(BitwiseXor::class) { e -> binary(e as Binary, "#") }
            reg(BitwiseXorAgg::class) { e -> pg().renameFuncSql("BIT_XOR", e) }
            reg(CurrentDate::class) { e -> pg().noParenCurrentDateSql(e as CurrentDate) }
            reg(CurrentTimestamp::class) { _ -> "CURRENT_TIMESTAMP" }
            reg(CurrentUser::class) { _ -> "CURRENT_USER" }
            reg(CurrentSchema::class) { e -> pg().currentschemaSql(e as CurrentSchema) }
            reg(CurrentVersion::class) { e -> pg().renameFuncSql("VERSION", e) }
            reg(DataType::class) { e -> pg().postgresDatatypeSql(e as DataType) }
            reg(DateAdd::class) { e -> pg().dateAddSql(e, "+") }
            reg(DateDiff::class) { e -> pg().dateDiffSql(e) }
            reg(DateStrToDate::class) { e -> pg().datestrtodateSql(e as DateStrToDate) }
            reg(DateSub::class) { e -> pg().dateAddSql(e, "-") }
            reg(Explode::class) { e -> pg().renameFuncSql("UNNEST", e) }
            reg(ExplodingGenerateSeries::class) { e -> pg().renameFuncSql("GENERATE_SERIES", e) }
            reg(GenerateSeries::class) { e -> pg().generateseriesSql(e as GenerateSeries) }
            reg(Getbit::class) { e -> pg().getbitSql(e as Getbit) }
            reg(GroupConcat::class) { e -> pg().groupconcatSql(e as GroupConcat) }
            reg(IntDiv::class) { e -> pg().renameFuncSql("DIV", e) }
            reg(IsAscii::class) { e -> pg().isasciiSql(e as IsAscii) }
            reg(JSONArrayAgg::class) { e ->
                func(
                    "JSON_AGG",
                    sql(e, "this"),
                    suffix = "${sql(e, "order")})",
                )
            }
            reg(JSONExtract::class) { e ->
                pg().postgresJsonExtractSql(e, "JSON_EXTRACT_PATH", "->")
            }
            reg(JSONExtractScalar::class) { e ->
                pg().postgresJsonExtractSql(e, "JSON_EXTRACT_PATH_TEXT", "->>")
            }
            reg(JSONBExtract::class) { e -> binary(e as Binary, "#>") }
            reg(JSONBExtractScalar::class) { e -> binary(e as Binary, "#>>") }
            reg(JSONBContains::class) { e -> binary(e as Binary, "?") }
            reg(ParseJSON::class) { e ->
                sql(Cast(args("this" to e.thisArg, "to" to DataType(args("this" to DType.JSON)))))
            }
            // sqlglot: json_path_key_only_name
            reg(JSONPathKey::class) { e ->
                if (e.thisArg is JSONPathWildcard) {
                    unsupported("Unsupported wildcard in JSONPathKey expression")
                }
                e.name
            }
            reg(JSONPathRoot::class) { _ -> "" }
            reg(JSONPathSubscript::class) { e -> jsonPathPart(e.thisArg) }
            reg(LastDay::class) { e -> pg().noLastDaySql(e as LastDay) }
            reg(LogicalOr::class) { e -> pg().renameFuncSql("BOOL_OR", e) }
            reg(LogicalAnd::class) { e -> pg().renameFuncSql("BOOL_AND", e) }
            reg(Max::class) { e -> pg().maxOrGreatestSql(e as Max) }
            reg(MapFromEntries::class) { e -> pg().noMapFromEntriesSql(e as MapFromEntries) }
            reg(Min::class) { e -> pg().minOrLeastSql(e as Min) }
            reg(Merge::class) { e -> pg().mergeWithoutTargetSql(e as Merge) }
            reg(PartitionedByProperty::class) { e -> "PARTITION BY ${sql(e, "this")}" }
            reg(PercentileCont::class) { e -> pg().addWithinGroupForPercentilesSql(e) }
            reg(PercentileDisc::class) { e -> pg().addWithinGroupForPercentilesSql(e) }
            reg(Pivot::class) { e -> pg().noPivotSql(e as Pivot) }
            reg(Rand::class) { e -> pg().renameFuncSql("RANDOM", e) }
            reg(RegexpLike::class) { e -> binary(e as Binary, "~") }
            reg(RegexpILike::class) { e -> binary(e as Binary, "~*") }
            reg(RegexpReplace::class) { e ->
                func(
                    "REGEXP_REPLACE",
                    e.thisArg,
                    e.args["expression"],
                    e.args["replacement"],
                    e.args["position"],
                    e.args["occurrence"],
                    pg().regexpReplaceGlobalModifier(e as RegexpReplace),
                )
            }
            reg(Round::class) { e -> pg().roundSql(e as Round) }
            reg(SHA2::class) { e -> pg().sha256Sql(e as SHA2) }
            reg(SHA2Digest::class) { e -> pg().sha2DigestSql(e as SHA2Digest) }
            reg(StrPosition::class) { e -> pg().strpositionSql(e as StrPosition) }
            reg(StrToDate::class) { e -> func("TO_DATE", e.thisArg, formatTime(e)) }
            reg(StrToTime::class) { e -> func("TO_TIMESTAMP", e.thisArg, formatTime(e)) }
            reg(StructExtract::class) { e -> pg().structExtractSql(e as StructExtract) }
            reg(Substring::class) { e -> pg().substringSql(e as Substring) }
            reg(TimeFromParts::class) { e -> pg().renameFuncSql("MAKE_TIME", e) }
            reg(TimestampFromParts::class) { e -> pg().renameFuncSql("MAKE_TIMESTAMP", e) }
            // sqlglot: timestamptrunc_sql(zone=True)
            reg(TimestampTrunc::class) { e ->
                func("DATE_TRUNC", unitToStr(e), e.thisArg, e.args["zone"])
            }
            reg(TimeStrToTime::class) { e -> pg().timestrtotimeSql(e as TimeStrToTime) }
            reg(TimeToStr::class) { e -> func("TO_CHAR", e.thisArg, formatTime(e)) }
            reg(ToChar::class) { e ->
                if (e.args["format"] != null) functionFallbackSql(e as Func)
                else tocharSql(e as ToChar)
            }
            reg(Trim::class) { e -> pg().dialectTrimSql(e as Trim) }
            reg(TryCast::class) { e -> pg().noTrycastSql(e as TryCast) }
            reg(TsOrDsAdd::class) { e -> pg().dateAddSql(e, "+") }
            reg(TsOrDsDiff::class) { e -> pg().dateDiffSql(e) }
            reg(Uuid::class) { _ -> "GEN_RANDOM_UUID()" }
            reg(TimeToUnix::class) { e ->
                func("DATE_PART", Literal.string("epoch"), e.thisArg)
            }
            reg(VariancePop::class) { e -> pg().renameFuncSql("VAR_POP", e) }
            reg(Variance::class) { e -> pg().renameFuncSql("VAR_SAMP", e) }
            reg(Xor::class) { e -> pg().boolXorSql(e as Xor) }
            reg(Unicode::class) { e -> pg().renameFuncSql("ASCII", e) }
            reg(UnixToTime::class) { e -> pg().unixToTimeSql(e as UnixToTime) }
            reg(Levenshtein::class) { e -> pg().levenshteinSql(e as Levenshtein) }
            reg(JSONObjectAgg::class) { e -> pg().renameFuncSql("JSON_OBJECT_AGG", e) }
            reg(JSONBObjectAgg::class) { e -> pg().renameFuncSql("JSONB_OBJECT_AGG", e) }
            reg(CountIf::class) { e -> pg().countIfToSumSql(e as CountIf) }
            // base TRANSFORMS pops exp.CommentColumnConstraint; the method override
            // (commentcolumnconstraint_sql) reports it as unsupported
            reg(CommentColumnConstraint::class) { e ->
                pg().commentcolumnconstraintSql(e as CommentColumnConstraint)
            }
            reg(SchemaCommentProperty::class) { e ->
                pg().schemacommentpropertySql(e as SchemaCommentProperty)
            }
        }
    }
}
