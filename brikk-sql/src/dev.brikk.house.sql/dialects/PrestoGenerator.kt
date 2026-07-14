package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.eliminateQualify
import dev.brikk.house.sql.generator.eliminateDistinctOn
import dev.brikk.house.sql.generator.eliminateSemiAndAntiJoins
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.parser.PrestoTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString
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

// sqlglot: dialects.hive.Hive.TIME_MAPPING (needed by PrestoGenerator.strtounix_sql)
private val HIVE_TIME_MAPPING: Map<String, String> = linkedMapOf(
    "y" to "%Y", "Y" to "%Y", "YYYY" to "%Y", "yyyy" to "%Y", "YY" to "%y", "yy" to "%y",
    "MMMM" to "%B", "MMM" to "%b", "MM" to "%m", "M" to "%-m", "dd" to "%d", "d" to "%-d",
    "HH" to "%H", "H" to "%-H", "hh" to "%I", "h" to "%-I", "mm" to "%M", "m" to "%-M",
    "ss" to "%S", "s" to "%-S", "SSSSSS" to "%f", "a" to "%p", "DD" to "%j", "D" to "%-j",
    "E" to "%a", "EE" to "%a", "EEE" to "%a", "EEEE" to "%A", "z" to "%Z", "Z" to "%z",
)

// sqlglot: dialects.hive.Hive.INVERSE_TIME_MAPPING
internal val HIVE_INVERSE_TIME_MAPPING: Map<String, String> =
    HIVE_TIME_MAPPING.entries.associate { (k, v) -> v to k }

/**
 * Port of sqlglot's PrestoGenerator (reference/sqlglot/sqlglot/generators/presto.py).
 * TRANSFORMS entries live in [TRANSFORMS] (a dispatch-map overlay handed to the base
 * constructor); flag overrides are open-val overrides; multi-line methods below.
 *
 * NOT PORTED (no Kotlin equivalents of sqlglot's transforms/annotate_types yet):
 * the exp.Select preprocess pipeline (eliminate_qualify, eliminate_distinct_on,
 * explode_projection_to_unnest, eliminate_semi_and_anti_joins,
 * amend_exploded_column_table, eliminate_window_clause) and the Array
 * inherit_struct_field_names preprocess. Mismatches are ledgered.
 */
// sqlglot: generators.presto.PrestoGenerator
open class PrestoGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = PrestoTokenizerTables.CONFIG,
    // extra dispatch overlay for subclasses (sqlglot: further TRANSFORMS merges, e.g. Trino)
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
    sourceDialect: String? = null,
) : Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
    sourceDialect = sourceDialect,
) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.PRESTO

    // ------------------------------------------------------------------
    // Flags (sqlglot: PrestoGenerator class attributes)
    // ------------------------------------------------------------------

    override val selectKinds: Set<String> get() = emptySet()
    override val intervalAllowsPluralForm: Boolean get() = false
    override val joinHints: Boolean get() = false
    override val tableHints: Boolean get() = false
    override val queryHints: Boolean get() = false
    override val isBoolAllowed: Boolean get() = false
    override val tzToWithTimeZone: Boolean get() = true
    override val nvl2Supported: Boolean get() = false
    override val structDelimiter: Pair<String, String> get() = "(" to ")"
    // sqlglot: PrestoGenerator.LIMIT_ONLY_LITERALS = True
    override val limitOnlyLiterals: Boolean get() = true
    override val supportsSingleArgConcat: Boolean get() = false
    override val likePropertyInsideSchema: Boolean get() = true
    override val multiArgDistinct: Boolean get() = false
    override val supportsToNumber: Boolean get() = false
    override val parseJsonName: String? get() = "JSON_PARSE"
    override val padFillPatternIsRequired: Boolean get() = true
    override val exceptIntersectSupportAllClause: Boolean get() = false
    override val supportsMedian: Boolean get() = false

    // sqlglot: Presto dialect-level flags read by the generator
    override val dialectNullOrdering: String get() = "nulls_are_last"
    override val dialectIndexOffset: Int get() = 1
    // sqlglot: Presto.TIME_FORMAT = MySQL.TIME_FORMAT
    override val dialectTimeFormat: String get() = "'%Y-%m-%d %T'"
    override val dialectStrictStringConcat: Boolean get() = true
    override val dialectTypedDivision: Boolean get() = true
    override val dialectTablesampleSizeIsPercent: Boolean get() = true
    // sqlglot: Presto.LOG_BASE_FIRST = None
    override val dialectLogBaseFirst: Boolean? get() = null
    override val inverseTimeMapping: Map<String, String> get() = MysqlDialect.INVERSE_TIME_MAPPING
    // sqlglot: Presto HEX_STRINGS = [("x'", "'"), ...]
    override val hexStart: String? get() = "x'"
    override val hexEnd: String? get() = "'"

    // sqlglot: PrestoGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: PrestoGenerator.RESERVED_KEYWORDS
    override val reservedKeywords: Set<String> get() = RESERVED_KEYWORDS

    // sqlglot: PrestoGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // sqlglot: Presto.DATEINT_FORMAT (Dialect default 'yyyymmdd' -> "'%Y%m%d'")
    open val dialectDateintFormat: String get() = "'%Y%m%d'"

    // ------------------------------------------------------------------
    // Transform helpers (sqlglot: module-level functions / dialect helpers)
    // ------------------------------------------------------------------

    internal fun isTruthy(value: kotlin.Any?): Boolean = when (value) {
        null, false -> false
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    // sqlglot: dialect.rename_func
    internal fun renameFuncSql(name: String, expression: Expression): String {
        val flattened = expression.args.values.flatMap { v ->
            if (v is List<*>) v else listOf(v)
        }
        return func(name, *flattened.toTypedArray())
    }

    // sqlglot: generators.presto._sha2_digest_sql (annotate_types-driven TEXT check is
    // approximated with Cast-carried type info; untyped args behave like UNKNOWN)
    open fun sha2DigestSql(expression: SHA2Digest): String {
        val length = expression.text("length").ifEmpty { "256" }
        if (length !in setOf("256", "512")) {
            unsupported("SHA$length is not supported in Presto")
        }

        var this_ = expression.thisArg as Expression
        if (isCastToType(this_, TEXT_TYPES)) {
            this_ = Encode(args("this" to this_, "charset" to Literal.string("utf-8")))
        }
        return func("SHA$length", this_)
    }

    // brikk extension (docs/brikk-extensions.md entry 11, NOT sqlglot parity):
    // GREATEST/LEAST NULL algebra. Presto/Trino GREATEST/LEAST return NULL if ANY
    // argument is NULL, while DuckDB/Postgres-parsed calls carry ignore_nulls=true
    // (they SKIP NULLs) — trino-duckdb-hazards.json "greatest / least",
    // verdict=divergent ("never translate by name when args may be NULL"). No
    // probe-verified Presto/Trino rewrite exists for the NULL-skipping semantics, so
    // the call is flagged instead of silently emitted; the Python oracle emits the
    // bare (semantically wrong) name, which we keep as the fallback output.
    open fun greatestLeastSql(expression: Expression): String {
        if (expression.args["ignore_nulls"] == true && expression.expressionsArg.isNotEmpty()) {
            val name = if (expression is Greatest) "GREATEST" else "LEAST"
            unsupported(
                "$name was parsed under a NULL-skipping dialect (e.g. DuckDB/Postgres); " +
                    "Presto/Trino $name returns NULL if any argument is NULL — " +
                    "no result-identical rewrite is verified"
            )
        }
        return functionFallbackSql(expression as Func)
    }

    // brikk extension (docs/brikk-extensions.md entry 12, NOT sqlglot parity):
    // REGEXP_REPLACE replace-first vs replace-all. Presto/Trino REGEXP_REPLACE always
    // replaces ALL matches and has no modifiers argument; DuckDB's default replaces only
    // the FIRST match unless the 'g' modifier is present (trino-duckdb-hazards.json
    // "regexp_replace", verdict=divergent). The Python oracle re-emits DuckDB's
    // modifiers as a 4th argument, which Trino's own parser/analyzer rejects (the only
    // 4-ary form takes a lambda). We render the grammar-legal 3-arg form and flag:
    //  - replace-first sources (single_replace=true without 'g'): no Presto/Trino
    //    equivalent exists — flagged;
    //  - the 'g' modifier is dropped silently (replace-all is Presto/Trino's default);
    //  - any other modifiers (c/i/m/s/...) have no Presto/Trino REGEXP_REPLACE
    //    equivalent — flagged and dropped;
    //  - position/occurrence arguments (Snowflake-style) — flagged and dropped.
    open fun regexpreplaceSql(expression: RegexpReplace): String {
        val modifiers = expression.args["modifiers"] as? Expression
        val modifierStr =
            if (modifiers is Literal && modifiers.isString) modifiers.thisArg as? String ?: ""
            else null // non-literal modifiers: cannot inspect

        if (modifiers != null && modifierStr == null) {
            unsupported("REGEXP_REPLACE with non-literal modifiers is not supported in Presto")
        }
        val remaining = modifierStr?.filter { it != 'g' } ?: ""
        if (remaining.isNotEmpty()) {
            unsupported(
                "Regexp modifiers '$remaining' have no Presto/Trino REGEXP_REPLACE equivalent"
            )
        }
        val replacesAll = modifierStr?.contains('g') == true
        if (expression.args["single_replace"] == true && !replacesAll && (modifiers == null || modifierStr != null)) {
            unsupported(
                "DuckDB REGEXP_REPLACE without the 'g' modifier replaces only the first " +
                    "match; Presto/Trino REGEXP_REPLACE always replaces all matches — " +
                    "there is no replace-first form"
            )
        }
        for (arg in listOf("position", "occurrence")) {
            if (expression.args[arg] != null) {
                unsupported(
                    "Argument '$arg' is not supported for expression 'RegexpReplace' when targeting Presto."
                )
            }
        }
        return func(
            "REGEXP_REPLACE",
            expression.thisArg,
            expression.args["expression"],
            expression.args["replacement"],
        )
    }

    // brikk extension (docs/brikk-extensions.md entry 13, NOT sqlglot parity): scalar-
    // position UNNEST/EXPLODE (e.g. duckdb `SELECT UNNEST([1, 2, 3])`) is eliminated in
    // sqlglot by the exp.Select preprocess `explode_projection_to_unnest`, which is not
    // ported (see class KDoc; the affected corpus cases are ledgered). Presto/Trino has
    // no EXPLODE *function*, so an Explode node that reaches the generator would render
    // as a call Trino cannot resolve — flag it accurately; Lateral-wrapped Explodes are
    // handled by explodeToUnnestSql and never dispatch here.
    open fun explodeSql(expression: Explode): String {
        unsupported(
            "UNNEST/EXPLODE in scalar position requires the explode_projection_to_unnest " +
                "transform (not ported); Presto/Trino has no EXPLODE function"
        )
        return functionFallbackSql(expression)
    }

    // sqlglot: generators.presto._initcap_sql (INITCAP_DEFAULT_DELIMITER_CHARS check
    // reduces to "delimiters present -> unsupported" for the base delimiter set)
    open fun initcapSql(expression: Initcap): String {
        val delimiters = expression.args["expression"] as? Expression
        if (delimiters != null &&
            !(delimiters is Literal && delimiters.isString &&
                delimiters.thisArg == INITCAP_DEFAULT_DELIMITER_CHARS)
        ) {
            unsupported("INITCAP does not support custom delimiters")
        }

        val regex = "(\\w)(\\w*)"
        return "REGEXP_REPLACE(${sql(expression, "this")}, '$regex', x -> UPPER(x[1]) || LOWER(x[2]))"
    }

    // sqlglot: generators.presto._no_sort_array
    open fun noSortArraySql(expression: SortArray): String {
        val asc = expression.args["asc"] as? Expression
        val comparator = if (asc is BooleanNode && asc.thisArg == false) {
            "(a, b) -> CASE WHEN a < b THEN 1 WHEN a > b THEN -1 ELSE 0 END"
        } else {
            null
        }
        return func("ARRAY_SORT", expression.thisArg, comparator)
    }

    // sqlglot: generators.presto._schema_sql
    open fun prestoSchemaSql(expression: Schema): String {
        if (expression.parent is PartitionedByProperty) {
            // Any columns in the ARRAY[] string literals should not be quoted
            // sqlglot: expression.transform(lambda n: n.name if isinstance(n, exp.Identifier)
            // else n, copy=False) — identifiers are replaced by their raw name strings
            // (arg values may be plain strings; Generator.sql passes them through)
            for (identifier in expression.findAll(Identifier::class).toList()) {
                identifier.replace(identifier.name)
            }

            val partitionExprs = expression.expressionsArg.map { c ->
                when {
                    // bare identifiers replaced by raw strings above pass through
                    c !is Expression -> sql(c)
                    c is Func || c is Property -> sql(c)
                    else -> sql(c, "this")
                }
            }
            return sql(
                ArrayNode(
                    args("expressions" to partitionExprs.map { Literal.string(it) })
                )
            )
        }

        val parent = expression.parent
        if (parent != null) {
            for (schema in parent.findAll(Schema::class)) {
                if (schema === expression) continue

                val columnDefs = schema.findAll(ColumnDef::class).toList()
                if (columnDefs.isNotEmpty() && schema.parent is Property) {
                    expression.set(
                        "expressions",
                        expression.expressionsArg + columnDefs,
                    )
                }
            }
        }

        return schemaSql(expression)
    }

    // sqlglot: generators.presto._quantile_sql
    open fun quantileSql(expression: Quantile): String {
        unsupported("Presto does not support exact quantiles")
        return func("APPROX_PERCENTILE", expression.thisArg, expression.args["quantile"])
    }

    // sqlglot: generators.presto._str_to_time_sql
    open fun strToTimeSql(expression: Expression): String =
        func("DATE_PARSE", expression.thisArg, formatTime(expression))

    // sqlglot: generators.presto._ts_or_ds_to_date_sql
    open fun tsOrDsToDateSql(expression: TsOrDsToDate): String {
        val timeFormat = formatTime(expression)
        if (timeFormat != null && timeFormat != dialectTimeFormat && timeFormat != dialectDateFormat) {
            return "CAST(${strToTimeSql(expression)} AS DATE)"
        }
        return sql(
            Cast(
                args(
                    "this" to Cast(
                        args(
                            "this" to expression.thisArg,
                            "to" to DataType(args("this" to DType.TIMESTAMP)),
                        )
                    ),
                    "to" to DataType(args("this" to DType.DATE)),
                )
            )
        )
    }

    // sqlglot: dialect.ts_or_ds_add_cast + generators.presto._ts_or_ds_add_sql
    open fun tsOrDsAddSql(expression: TsOrDsAdd): String {
        // sqlglot: TsOrDsAdd.return_type (args["return_type"] or DATE)
        val returnType = expression.args["return_type"] as? Expression
            ?: DataType(args("this" to DType.DATE))
        var this_ = (expression.thisArg as Expression).copy()

        if (returnType.thisArg == DType.DATE) {
            this_ = Cast(args("this" to this_, "to" to DataType(args("this" to DType.TIMESTAMP))))
        }
        (expression.thisArg as Expression).replace(
            Cast(args("this" to this_, "to" to returnType.copy()))
        )

        val unit = unitToStr(expression)
        return func("DATE_ADD", unit, expression.args["expression"], expression.thisArg)
    }

    // sqlglot: generators.presto._ts_or_ds_diff_sql
    open fun tsOrDsDiffSql(expression: TsOrDsDiff): String {
        val this_ = Cast(
            args(
                "this" to expression.thisArg,
                "to" to DataType(args("this" to DType.TIMESTAMP)),
            )
        )
        val expr = Cast(
            args(
                "this" to expression.args["expression"],
                "to" to DataType(args("this" to DType.TIMESTAMP)),
            )
        )
        val unit = unitToStr(expression)
        return func("DATE_DIFF", unit, expr, this_)
    }

    // sqlglot: generators.presto._first_last_sql
    open fun firstLastSql(expression: Expression): String {
        if (expression.findAncestor(MatchRecognize::class, Select::class) is MatchRecognize) {
            return functionFallbackSql(expression as Func)
        }
        return renameFuncSql("ARBITRARY", expression)
    }

    // sqlglot: generators.presto._unix_to_time_sql
    open fun unixToTimeSql(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        val timestamp = sql(expression, "this")

        // sqlglot: `scale in (None, exp.UnixToTime.SECONDS)` (SECONDS = Literal.number(0))
        val isSeconds = scale == null ||
            (scale is Literal && !scale.isString && scale.thisArg == "0")
        if (isSeconds) return renameFuncSql("FROM_UNIXTIME", expression)

        return "FROM_UNIXTIME(CAST($timestamp AS DOUBLE) / POW(10, ${sql(scale)}))"
    }

    /**
     * sqlglot: generators.presto._to_int — Python runs annotate_types; we approximate
     * with a literal/Cast-level integer-type inference (columns and unknown functions
     * behave like Python's UNKNOWN annotation and get cast to BIGINT).
     */
    protected fun isIntTyped(expression: Expression?): Boolean = when (expression) {
        null -> false
        is Literal -> !expression.isString && !(expression.thisArg as? String ?: "").contains(".")
        is Neg -> isIntTyped(expression.thisArg as? Expression)
        is Paren -> isIntTyped(expression.thisArg as? Expression)
        is Cast -> (expression.args["to"] as? Expression)?.thisArg in INTEGER_TYPES
        is Floor, is Ceil -> isIntTyped(expression.thisArg as? Expression)
        is Add, is Sub, is Mul, is Mod -> {
            val b = expression as Binary
            isIntTyped(b.left) && isIntTyped(b.right)
        }
        else -> false
    }

    protected fun toInt(expression: Expression): Expression =
        if (isIntTyped(expression)) expression
        else Cast(args("this" to expression, "to" to DataType(args("this" to DType.BIGINT))))

    // sqlglot: generators.presto._date_delta_sql
    open fun dateDeltaSql(name: String, expression: Expression, negateInterval: Boolean = false): String {
        val interval = toInt(expression.args["expression"] as Expression)
        val finalInterval = if (negateInterval) {
            Mul(args("this" to interval, "expression" to Literal.number("-1")))
        } else interval
        return func(name, unitToStr(expression), finalInterval, expression.thisArg)
    }

    // sqlglot: dialect.explode_to_unnest_sql + generators.presto._explode_to_unnest_sql
    // (the annotate_types-driven struct-array alias fix is not ported)
    open fun explodeToUnnestSql(expression: Lateral): String {
        var this_ = expression.thisArg as? Expression
        val alias = expression.args["alias"] as? Expression

        if (this_ is Inline) {
            val replaced = Explode(args("this" to (this_.thisArg as Expression).copy()))
            this_.replace(replaced)
            this_ = replaced
        }

        var crossJoinExpr: Expression? = null
        val aliasColumns = (alias?.args?.get("columns") as? List<*>)?.filterIsInstance<Expression>()
        if (this_ is Posexplode && alias is TableAlias && !aliasColumns.isNullOrEmpty()) {
            val columns = aliasColumns
            val pos = columns[0]
            val cols = columns.drop(1)

            val posMinusOne = Alias(
                args(
                    "this" to Sub(
                        args(
                            "this" to Column(args("this" to Identifier(args("this" to pos.name)))),
                            "expression" to Literal.number("1"),
                        )
                    ),
                    "alias" to Identifier(args("this" to pos.name)),
                )
            )
            val selectCols = listOf(posMinusOne) + cols.map {
                Column(args("this" to Identifier(args("this" to it.name))))
            }
            val unnest = Unnest(
                args(
                    "expressions" to listOf((this_.thisArg as Expression).copy()),
                    "offset" to true,
                    "alias" to TableAlias(
                        args(
                            "this" to (alias.thisArg as? Expression)?.copy(),
                            "columns" to (cols.map { it.copy() } + pos.copy()),
                        )
                    ),
                )
            )
            val subquery = Subquery(
                args(
                    "this" to Select(
                        args("expressions" to selectCols, "from" to From(args("this" to unnest)))
                    )
                )
            )
            crossJoinExpr = Lateral(args("this" to subquery))
        } else if (this_ is Explode) {
            crossJoinExpr = Unnest(
                args(
                    "expressions" to listOf(this_.thisArg),
                    "alias" to alias,
                )
            )
        }

        if (crossJoinExpr != null) {
            return sql(Join(args("this" to crossJoinExpr, "kind" to "cross")))
        }

        return lateralSql(expression)
    }

    // sqlglot: dialect.bool_xor_sql
    open fun boolXorSql(expression: Xor): String {
        val a = sql(expression, "this")
        val b = sql(expression, "expression")
        return "($a AND (NOT $b)) OR ((NOT $a) AND $b)"
    }

    // sqlglot: dialect.no_ilike_sql
    open fun noIlikeSql(expression: ILike): String =
        likeSql(
            Like(
                args(
                    "this" to Lower(args("this" to expression.thisArg)),
                    "expression" to Lower(args("this" to expression.args["expression"])),
                    "negate" to expression.args["negate"],
                )
            )
        )

    // sqlglot: dialect.no_pivot_sql
    open fun noPivotSql(expression: Pivot): String {
        unsupported("PIVOT unsupported")
        return ""
    }

    // sqlglot: dialect.no_timestamp_sql (the zero-arg branch runs annotate_types in
    // Python to pick TIMESTAMP vs TIMESTAMPTZ; we default to TIMESTAMP)
    open fun noTimestampSql(expression: Timestamp): String {
        val zone = expression.args["zone"] as? Expression
        if (zone == null) {
            return sql(
                Cast(
                    args(
                        "this" to expression.thisArg,
                        "to" to DataType(args("this" to DType.TIMESTAMP)),
                    )
                )
            )
        }
        if (zone.name.lowercase() in dev.brikk.house.sql.parser.TIMEZONES) {
            return sql(
                AtTimeZone(
                    args(
                        "this" to Cast(
                            args(
                                "this" to expression.thisArg,
                                "to" to DataType(args("this" to DType.TIMESTAMP)),
                            )
                        ),
                        "zone" to zone,
                    )
                )
            )
        }
        return func("TIMESTAMP", expression.thisArg, zone)
    }

    // sqlglot: dialect.encode_decode_sql
    open fun encodeDecodeSql(expression: Expression, name: String, replace: Boolean = true): String {
        val charset = expression.args["charset"] as? Expression
        if (charset != null && charset.name.lowercase() !in setOf("utf-8", "utf8")) {
            unsupported("Expected utf-8 character set, got ${sql(charset)}.")
        }
        return func(
            name,
            expression.thisArg,
            if (replace) expression.args["replace"] else null,
        )
    }

    // sqlglot: dialect.left_to_substring_sql
    open fun leftToSubstringSql(expression: Left): String =
        sql(
            Substring(
                args(
                    "this" to expression.thisArg,
                    "start" to Literal.number("1"),
                    "length" to expression.args["expression"],
                )
            )
        )

    // sqlglot: dialect.right_to_substring_sql
    open fun rightToSubstringSql(expression: Right): String =
        sql(
            Substring(
                args(
                    "this" to expression.thisArg,
                    "start" to Sub(
                        args(
                            "this" to Add(
                                args(
                                    "this" to Length(args("this" to expression.thisArg)),
                                    "expression" to Paren(
                                        args(
                                            "this" to Sub(
                                                args(
                                                    "this" to expression.args["expression"],
                                                    "expression" to Literal.number("1"),
                                                )
                                            )
                                        )
                                    ),
                                )
                            ),
                            "expression" to null,
                        )
                    ),
                )
            )
        )

    // sqlglot: dialect.regexp_extract_sql (REGEXP_EXTRACT_DEFAULT_GROUP = 0)
    open fun regexpExtractSql(expression: Expression): String {
        var group = expression.args["group"] as? Expression
        // Do not render group if it's the default value for this dialect
        if (group != null && group.name == "0") group = null
        return func(
            (expression as Func).sqlName(),
            expression.thisArg,
            expression.args["expression"],
            group,
        )
    }

    // sqlglot: dialect.strposition_sql (func_name="STRPOS", supports_occurrence=True)
    open fun strpositionSql(expression: StrPosition): String {
        var string = expression.thisArg as? Expression
        val substr = expression.args["substr"]
        var position = expression.args["position"] as? Expression
        val occurrence = expression.args["occurrence"]

        if (occurrence != null && position == null) {
            // supports_occurrence && occurrence && supports_position=false && !position
            // -> Python's branch requires supports_position; STRPOS has none, skip
        }

        val transpilePosition = position != null
        if (transpilePosition) {
            string = Substring(args("this" to string, "start" to position))
        }

        val fnArgs = mutableListOf<kotlin.Any?>(string, substr)
        if (occurrence != null) fnArgs.add(occurrence)

        val funcExpr = Anonymous(
            args("this" to "STRPOS", "expressions" to fnArgs.filterNotNull())
        )

        if (transpilePosition) {
            val zero = Literal.number("0")
            val one = Literal.number("1")
            val funcWithOffset = Sub(
                args(
                    "this" to Add(args("this" to funcExpr.copy(), "expression" to position)),
                    "expression" to one,
                )
            )
            val funcWrapped = If(
                args(
                    "this" to EQ(args("this" to funcExpr, "expression" to zero)),
                    "true" to zero.copy(),
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
        val identifier = if (SAFE_IDENTIFIER_RE.matches(name)) {
            Identifier(args("this" to name))
        } else {
            Identifier(args("this" to name, "quoted" to true))
        }
        return "${sql(expression, "this")}.${sql(identifier)}"
    }

    // sqlglot: dialect.timestamptrunc_sql()
    open fun timestamptruncSql(expression: TimestampTrunc): String =
        func("DATE_TRUNC", unitToStr(expression), expression.thisArg)

    // sqlglot: dialect.timestrtotime_sql
    open fun timestrtotimeSql(expression: Expression, includePrecision: Boolean = false): String {
        val zone = expression.args["zone"]
        var datatype = DataType(
            args("this" to (if (isTruthy(zone)) DType.TIMESTAMPTZ else DType.TIMESTAMP))
        )

        val this_ = expression.thisArg
        if (this_ is Literal && includePrecision) {
            val precision = subsecondPrecisionOf(this_.name)
            if (precision > 0) {
                datatype = DataType(
                    args(
                        "this" to datatype.thisArg,
                        "expressions" to listOf(
                            DataTypeParam(args("this" to Literal.number(precision.toString())))
                        ),
                    )
                )
            }
        }

        return sql(Cast(args("this" to expression.thisArg, "to" to datatype)))
    }

    // sqlglot: dialect.datestrtodate_sql
    open fun datestrtodateSql(expression: DateStrToDate): String =
        sql(
            Cast(
                args("this" to expression.thisArg, "to" to DataType(args("this" to DType.DATE)))
            )
        )

    // sqlglot: dialect.sequence_sql (the is_end_exclusive rewrite needs the simplify
    // optimizer; unsupported-message fallback keeps the plain SEQUENCE call)
    open fun sequenceSql(expression: Expression): String {
        var start = expression.args["start"] as? Expression
        var end = expression.args["end"] as? Expression
        val step = expression.args["step"] as? Expression

        val targetType: Expression? = when {
            start is Cast -> start.args["to"] as? Expression
            end is Cast -> end.args["to"] as? Expression
            else -> null
        }

        if (start != null && end != null) {
            if (targetType != null &&
                targetType.thisArg in setOf(DType.DATE, DType.TIMESTAMP)
            ) {
                if (start is Cast && targetType === start.args["to"]) {
                    end.replace(Cast(args("this" to end.copy(), "to" to targetType.copy())))
                        .let { end = it as Expression }
                } else {
                    start.replace(Cast(args("this" to start.copy(), "to" to targetType.copy())))
                        .let { start = it as Expression }
                }
            }

            if (isTruthy(expression.args["is_end_exclusive"])) {
                unsupported("Cannot transpile end-exclusive sequences to Presto")
            }
        }

        return func("SEQUENCE", expression.args["start"], expression.args["end"], step)
    }

    // sqlglot: transforms.epoch_cast_to_ts
    protected fun epochCastToTs(expression: Expression): Expression {
        if ((expression is Cast || expression is TryCast) &&
            expression.name.lowercase() == "epoch" &&
            (expression.args["to"] as? Expression)?.thisArg in TEMPORAL_TYPES
        ) {
            (expression.thisArg as Expression).replace(Literal.string("1970-01-01 00:00:00"))
        }
        return expression
    }

    // sqlglot: transforms.unnest_generate_series
    protected fun unnestGenerateSeries(expression: Table): Expression {
        val this_ = expression.thisArg
        if (this_ is GenerateSeries) {
            val unnest = Unnest(args("expressions" to listOf(this_)))
            val alias = expression.args["alias"] as? TableAlias
            if (alias != null && alias.name.isNotEmpty()) {
                return Alias(
                    args(
                        "this" to unnest,
                        "alias" to TableAlias(
                            args(
                                "this" to Identifier(args("this" to "_u")),
                                "columns" to listOf(Identifier(args("this" to alias.name))),
                            )
                        ),
                    )
                )
            }
            return unnest
        }
        return expression
    }

    // sqlglot: transforms.remove_within_group_for_percentiles
    protected fun removeWithinGroupForPercentiles(expression: WithinGroup): Expression {
        val this_ = expression.thisArg
        val order = expression.args["expression"]
        if ((this_ is PercentileCont || this_ is PercentileDisc) && order is Order) {
            val quantile = (this_ as Expression).thisArg
            val ordered = expression.find(Ordered::class)
            val inputValue = (ordered as? Ordered)?.thisArg
            val replacement = ApproxQuantile(
                args("this" to inputValue, "quantile" to quantile)
            )
            return expression.replace(replacement) as Expression
        }
        return expression
    }

    // sqlglot: transforms.add_recursive_cte_column_names
    protected fun addRecursiveCteColumnNames(expression: With): Expression {
        if (isTruthy(expression.args["recursive"])) {
            var counter = 0
            for (cte in expression.expressionsArg) {
                val alias = (cte as Expression).args["alias"] as? TableAlias ?: continue
                if ((alias.args["columns"] as? List<*>).isNullOrEmpty()) {
                    var query = cte.thisArg as? Expression
                    if (query is SetOperation) query = query.thisArg as? Expression

                    val selects = (query as? Select)?.expressionsArg.orEmpty()
                    alias.set(
                        "columns",
                        selects.map { s ->
                            val e = s as Expression
                            val name = when {
                                e is Alias -> e.text("alias").ifEmpty { e.name }
                                else -> e.name
                            }
                            val finalName = name.ifEmpty { "_c_${counter++}" }
                            Identifier(args("this" to finalName))
                        },
                    )
                }
            }
        }
        return expression
    }

    // ------------------------------------------------------------------
    // Overridden generator methods (sqlglot: generators/presto.py methods)
    // ------------------------------------------------------------------

    // sqlglot: PrestoGenerator.extract_sql
    override fun extractSql(expression: Extract): String {
        val datePart = expression.name

        if (!datePart.startsWith("EPOCH")) return super.extractSql(expression)

        val scale: Long? = when (datePart) {
            "EPOCH_MILLISECOND" -> 1_000L
            "EPOCH_MICROSECOND" -> 1_000_000L
            "EPOCH_NANOSECOND" -> 1_000_000_000L
            else -> null
        }

        val value = expression.args["expression"]

        val ts = Cast(args("this" to value, "to" to DataType(args("this" to DType.TIMESTAMP))))
        var toUnix: Expression = TimeToUnix(args("this" to ts))

        if (scale != null) {
            toUnix = Mul(args("this" to toUnix, "expression" to Literal.number(scale.toString())))
        }

        return sql(toUnix)
    }

    // sqlglot: PrestoGenerator.jsonformat_sql (annotate_types approximated: only an
    // explicit is_json flag or a Cast-to-JSON argument skips the JSON cast)
    open fun jsonformatSql(expression: JSONFormat): String {
        val this_ = expression.thisArg as? Expression
        val isJson = isTruthy(expression.args["is_json"])

        if (this_ != null && !isJson && !isCastToType(this_, setOf(DType.JSON))) {
            this_.replace(
                Cast(args("this" to this_.copy(), "to" to DataType(args("this" to DType.JSON))))
            )
        }

        return functionFallbackSql(expression)
    }

    // sqlglot: PrestoGenerator.md5_sql
    open fun md5Sql(expression: MD5): String {
        var this_ = expression.thisArg as Expression
        if (isCastToType(this_, TEXT_TYPES)) {
            this_ = Encode(args("this" to this_, "charset" to Literal.string("utf-8")))
        }
        return func("LOWER", func("TO_HEX", func("MD5", sql(this_))))
    }

    // sqlglot: PrestoGenerator.sha2_sql
    open fun sha2Sql(expression: SHA2): String {
        val length = expression.text("length").ifEmpty { "256" }
        if (length !in setOf("256", "512")) {
            unsupported("SHA$length is not supported in Presto")
        }

        var this_ = expression.thisArg as Expression
        if (isCastToType(this_, TEXT_TYPES)) {
            this_ = Encode(args("this" to this_, "charset" to Literal.string("utf-8")))
        }
        return func("LOWER", func("TO_HEX", func("SHA$length", sql(this_))))
    }

    // sqlglot: PrestoGenerator.strtounix_sql
    open fun strtounixSql(expression: StrToUnix): String {
        val this_ = expression.thisArg as Expression
        val valueAsText = Cast(
            args("this" to this_, "to" to DataType(args("this" to DType.TEXT)))
        )
        val valueAsTimestamp = if (this_ is Literal && this_.isString) {
            Cast(args("this" to this_, "to" to DataType(args("this" to DType.TIMESTAMP))))
        } else this_

        val parseWithoutTz = func("DATE_PARSE", valueAsText, formatTime(expression))

        val formattedValue = func("DATE_FORMAT", valueAsTimestamp, formatTime(expression))
        val parseWithTz = func(
            "PARSE_DATETIME",
            formattedValue,
            formatTimeString(sql(expression, "format"), HIVE_INVERSE_TIME_MAPPING),
        )
        val coalesced = func("COALESCE", func("TRY", parseWithoutTz), parseWithTz)
        return func("TO_UNIXTIME", coalesced)
    }

    // sqlglot: PrestoGenerator.bracket_sql (safe -> ELEMENT_AT)
    override fun bracketSql(expression: Bracket): String {
        if (isTruthy(expression.args["safe"])) {
            // sqlglot: dialect.bracket_to_element_at_sql
            val exprs = bracketOffsetExpressions(expression)
            return func("ELEMENT_AT", expression.thisArg, exprs.firstOrNull())
        }
        return super.bracketSql(expression)
    }

    // sqlglot: PrestoGenerator.struct_sql
    override fun structSql(expression: Struct): String {
        if (expression.type == null) {
            dev.brikk.house.sql.optimizer.annotateTypes(expression, dialect = dialect)
        }

        val values = mutableListOf<String>()
        val schema = mutableListOf<String>()
        var unknownType = false

        for (e in expression.expressionsArg) {
            val expr = e as Expression
            if (expr is PropertyEQ) {
                val exprType = expr.type as? DataType
                if (exprType != null && exprType.isType(DType.UNKNOWN)) {
                    unknownType = true
                } else {
                    schema.add("${sql(expr, "this")} ${sql(expr.type)}")
                }
                values.add(sql(expr, "expression"))
            } else {
                values.add(sql(expr))
            }
        }

        val size = expression.expressionsArg.size

        if (size == 0 || schema.size != size) {
            if (unknownType) {
                unsupported("Cannot convert untyped key-value definitions (try annotate_types).")
            }
            return func("ROW", *values.toTypedArray())
        }
        return "CAST(ROW(${values.joinToString(", ")}) AS ROW(${schema.joinToString(", ")}))"
    }

    // sqlglot: PrestoGenerator.interval_sql
    override fun intervalSql(expression: Interval): String {
        val this_ = expression.thisArg as? Expression
        if (this_ != null && expression.text("unit").uppercase().startsWith("WEEK")) {
            return "(${this_.name} * INTERVAL '7' DAY)"
        }
        return super.intervalSql(expression)
    }

    // sqlglot: PrestoGenerator.transaction_sql
    override fun transactionSql(expression: Transaction): String {
        val modes = (expression.args["modes"] as? List<*>).orEmpty()
        val modesSql = if (modes.isNotEmpty()) " ${modes.joinToString(", ")}" else ""
        return "START TRANSACTION$modesSql"
    }

    // sqlglot: PrestoGenerator.offset_limit_modifiers
    override fun offsetLimitModifiers(
        expression: Expression,
        fetch: Boolean,
        limit: Expression?,
    ): List<String> = listOf(
        sql(expression, "offset"),
        sql(limit),
    )

    // sqlglot: PrestoGenerator.create_sql
    override fun createSql(expression: Create): String {
        val kind = expression.args["kind"]
        val schema = expression.thisArg
        if (kind == "VIEW" && schema is Schema && schema.expressionsArg.isNotEmpty()) {
            schema.set("expressions", null)
        }
        return super.createSql(expression)
    }

    // sqlglot: PrestoGenerator.delete_sql
    override fun deleteSql(expression: Delete): String {
        val tablesArg = (expression.args["tables"] as? List<*>)?.filterIsInstance<Expression>()
        val tables = if (!tablesArg.isNullOrEmpty()) tablesArg else listOf(expression.thisArg as? Expression)
        if (tables.size > 1) return super.deleteSql(expression)

        val table = tables[0]
        expression.set("this", table)
        expression.set("tables", null)

        if (table is Table) {
            val tableAlias = table.args["alias"] as? Expression
            if (tableAlias != null) {
                table.set("alias", null)
                // sqlglot: transforms.unqualify_columns
                for (column in expression.findAll(Column::class)) {
                    column.set("table", null)
                    column.set("db", null)
                    column.set("catalog", null)
                }
            }
        }

        return super.deleteSql(expression)
    }

    // sqlglot: PrestoGenerator.jsonextract_sql ("variant_extract_is_json_extract"
    // dialect setting defaults to True -> always the JSON_EXTRACT branch)
    open fun jsonextractSql(expression: JSONExtract): String =
        func(
            "JSON_EXTRACT",
            expression.thisArg,
            expression.args["expression"],
            *expression.expressionsArg.toTypedArray(),
        )

    // sqlglot: PrestoGenerator.groupconcat_sql
    open fun groupconcatSql(expression: GroupConcat): String =
        func(
            "ARRAY_JOIN",
            ArrayAgg(args("this" to expression.thisArg)),
            expression.args["separator"],
        )

    // sqlglot: Generator.log_sql with Presto.LOG_BASE_FIRST = None
    override fun logSql(expression: Log): String {
        val this_ = expression.thisArg as Expression
        val expr = expression.args["expression"] as? Expression

        if (expr != null) {
            if (this_.name in setOf("2", "10")) {
                return func("LOG${this_.name}", expr)
            }
            unsupported("Unsupported logarithm with base ${sql(this_)}")
        }

        return func("LOG", this_, expr)
    }

    companion object {

        // sqlglot: expressions.datatypes.DataType.TEMPORAL_TYPES
        val TEMPORAL_TYPES: Set<DType> = setOf(
            DType.DATE, DType.DATE32, DType.DATETIME, DType.DATETIME2, DType.DATETIME64,
            DType.SMALLDATETIME, DType.TIME, DType.TIMESTAMP, DType.TIMESTAMPNTZ,
            DType.TIMESTAMPLTZ, DType.TIMESTAMPTZ, DType.TIMESTAMP_MS, DType.TIMESTAMP_NS,
            DType.TIMESTAMP_S, DType.TIMETZ,
        )

        // sqlglot: Dialect.INITCAP_DEFAULT_DELIMITER_CHARS
        const val INITCAP_DEFAULT_DELIMITER_CHARS: String = " \t\n\r\u000b\u000c"

        // sqlglot: time.subsecond_precision (regex approximation, mirrors MysqlGenerator)
        private val ISO_TIMESTAMP_RE = Regex(
            "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.(\\d+))?)?([+-]\\d{2}:\\d{2}(:\\d{2})?)?$"
        )

        internal fun subsecondPrecisionOf(timestampLiteral: String): Int {
            val m = ISO_TIMESTAMP_RE.matchEntire(timestampLiteral) ?: return 0
            val fraction = m.groupValues[3]
            if (fraction.isEmpty() || fraction.length > 6) return 0
            val micros = fraction.padEnd(6, '0').toIntOrNull() ?: return 0
            if (micros == 0) return 0
            val digitCount = micros.toString().trimEnd('0').length
            return if (digitCount > 3) 6 else 3
        }

        // sqlglot: PrestoGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = mapOf(
            DType.BINARY to "VARBINARY",
            DType.BIT to "BOOLEAN",
            DType.DATETIME to "TIMESTAMP",
            DType.DATETIME64 to "TIMESTAMP",
            DType.FLOAT to "REAL",
            DType.HLLSKETCH to "HYPERLOGLOG",
            DType.INT to "INTEGER",
            DType.STRUCT to "ROW",
            DType.TEXT to "VARCHAR",
            DType.TIMESTAMPTZ to "TIMESTAMP",
            DType.TIMESTAMPNTZ to "TIMESTAMP",
            DType.TIMETZ to "TIME",
        )

        // sqlglot: PrestoGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            GeneratorTables.PROPERTIES_LOCATION + mapOf(
                LocationProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
                VolatileProperty::class to GeneratorTables.PropLocation.UNSUPPORTED,
            )

        // sqlglot: PrestoGenerator.TRANSFORMS (dispatch-map overlay over the base;
        // multi-line entries are methods on PrestoGenerator, one-liners inlined)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.pg(): PrestoGenerator = this as PrestoGenerator

            reg(AnyValue::class) { e -> pg().renameFuncSql("ARBITRARY", e) }
            reg(ApproxQuantile::class) { e ->
                func(
                    "APPROX_PERCENTILE",
                    e.thisArg,
                    e.args["weight"],
                    e.args["quantile"],
                    e.args["accuracy"],
                )
            }
            reg(ArgMax::class) { e -> pg().renameFuncSql("MAX_BY", e) }
            reg(ArgMin::class) { e -> pg().renameFuncSql("MIN_BY", e) }
            // sqlglot: TRANSFORMS[exp.Array] (inherit_struct_field_names preprocess skipped)
            reg(ArrayNode::class) { e -> "ARRAY[${expressions(e, flat = true)}]" }
            reg(ArrayAny::class) { e -> pg().renameFuncSql("ANY_MATCH", e) }
            reg(ArrayConcat::class) { e -> pg().renameFuncSql("CONCAT", e) }
            reg(ArrayContains::class) { e -> pg().renameFuncSql("CONTAINS", e) }
            reg(ArrayToString::class) { e -> pg().renameFuncSql("ARRAY_JOIN", e) }
            reg(ArrayUniqueAgg::class) { e -> pg().renameFuncSql("SET_AGG", e) }
            reg(ArraySlice::class) { e -> pg().renameFuncSql("SLICE", e) }
            reg(AtTimeZone::class) { e -> pg().renameFuncSql("AT_TIMEZONE", e) }
            reg(BitwiseAnd::class) { e -> func("BITWISE_AND", e.thisArg, e.args["expression"]) }
            reg(BitwiseLeftShift::class) { e -> pg().renameFuncSql("BITWISE_LEFT_SHIFT", e) }
            reg(BitwiseNot::class) { e -> func("BITWISE_NOT", e.thisArg) }
            reg(BitwiseOr::class) { e -> func("BITWISE_OR", e.thisArg, e.args["expression"]) }
            reg(BitwiseRightShift::class) { e -> pg().renameFuncSql("BITWISE_RIGHT_SHIFT", e) }
            reg(BitwiseXor::class) { e -> func("BITWISE_XOR", e.thisArg, e.args["expression"]) }
            // sqlglot: exp.Cast/TryCast preprocess([transforms.epoch_cast_to_ts])
            reg(Cast::class) { e -> castSql(pg().epochCastToTs(e) as Cast) }
            reg(TryCast::class) { e -> trycastSql(pg().epochCastToTs(e) as TryCast) }
            reg(CurrentTime::class) { _ -> "CURRENT_TIME" }
            reg(CurrentTimestamp::class) { _ -> "CURRENT_TIMESTAMP" }
            reg(CurrentUser::class) { _ -> "CURRENT_USER" }
            reg(DateAdd::class) { e -> pg().dateDeltaSql("DATE_ADD", e) }
            reg(DateDiff::class) { e ->
                func("DATE_DIFF", unitToStr(e), e.args["expression"], e.thisArg)
            }
            reg(DateStrToDate::class) { e -> pg().datestrtodateSql(e as DateStrToDate) }
            reg(DateToDi::class) { e ->
                "CAST(DATE_FORMAT(${sql(e, "this")}, ${pg().dialectDateintFormat}) AS INT)"
            }
            reg(DateSub::class) { e -> pg().dateDeltaSql("DATE_ADD", e, negateInterval = true) }
            reg(DayOfWeek::class) { e -> "((${func("DAY_OF_WEEK", e.thisArg)} % 7) + 1)" }
            reg(DayOfWeekIso::class) { e -> pg().renameFuncSql("DAY_OF_WEEK", e) }
            reg(Decode::class) { e -> pg().encodeDecodeSql(e, "FROM_UTF8") }
            reg(DiToDate::class) { e ->
                "CAST(DATE_PARSE(CAST(${sql(e, "this")} AS VARCHAR), ${pg().dialectDateintFormat}) AS DATE)"
            }
            reg(Encode::class) { e -> pg().encodeDecodeSql(e, "TO_UTF8") }
            reg(FileFormatProperty::class) { e ->
                "format=${sql(Literal.string(e.name))}"
            }
            reg(First::class) { e -> pg().firstLastSql(e) }
            reg(FromISO8601Date::class) { e -> pg().renameFuncSql("FROM_ISO8601_DATE", e) }
            reg(FromISO8601Timestamp::class) { e ->
                pg().renameFuncSql("FROM_ISO8601_TIMESTAMP", e)
            }
            reg(FromTimeZone::class) { e ->
                "WITH_TIMEZONE(${sql(e, "this")}, ${sql(e, "zone")}) AT TIME ZONE 'UTC'"
            }
            reg(GenerateSeries::class) { e -> pg().sequenceSql(e) }
            reg(GenerateDateArray::class) { e -> pg().sequenceSql(e) }
            reg(If::class) { e ->
                func("IF", e.thisArg, e.args["true"], e.args["false"])
            }
            reg(ILike::class) { e -> pg().noIlikeSql(e as ILike) }
            reg(Initcap::class) { e -> pg().initcapSql(e as Initcap) }
            // brikk extension (docs/brikk-extensions.md entry 11)
            reg(Greatest::class) { e -> pg().greatestLeastSql(e) }
            reg(Least::class) { e -> pg().greatestLeastSql(e) }
            // brikk extension (docs/brikk-extensions.md entry 12)
            reg(RegexpReplace::class) { e -> pg().regexpreplaceSql(e as RegexpReplace) }
            // brikk extension (docs/brikk-extensions.md entry 13)
            reg(Explode::class) { e -> pg().explodeSql(e as Explode) }
            // brikk extension (docs/brikk-extensions.md entry 14): catalog-backed rename
            // fixes for renders the Python oracle emits under names Trino/Presto does not
            // have (gap-report.json bucket B "absent-name" entries):
            // duckdb isinf()/doris isinf() -> IS_INFINITE (trino-functions-481.tsv:
            // is_infinite(double|number) -> boolean; Python emits IS_INF).
            reg(IsInf::class) { e -> pg().renameFuncSql("IS_INFINITE", e) }
            // doris database()/mysql schema() -> CURRENT_SCHEMA, a parenthesis-less
            // special form in Trino's grammar (SqlBase.g4 `name=CURRENT_SCHEMA`); the
            // Python oracle emits CURRENT_SCHEMA(), which that grammar rejects.
            reg(CurrentSchema::class) { _ -> "CURRENT_SCHEMA" }
            // doris months_add(d, n) -> DATE_ADD('MONTH', n, d) (trino-functions-481.tsv:
            // date_add(varchar(x), bigint, date|timestamp...)); Python emits ADD_MONTHS,
            // which Trino does not have.
            reg(AddMonths::class) { e ->
                if (e.args["preserve_end_of_month"] != null) {
                    unsupported(
                        "Argument 'preserve_end_of_month' is not supported for expression 'AddMonths' when targeting Presto."
                    )
                }
                func("DATE_ADD", Literal.string("MONTH"), e.args["expression"], e.thisArg)
            }
            // doris split_by_string(s, sep) -> SPLIT(s, sep) (trino-functions-481.tsv:
            // split(varchar(x), varchar(y)) -> array(varchar(x)); Python emits
            // STRING_TO_ARRAY, which Trino does not have). The Postgres 3-arg
            // null-replacement form has no Trino equivalent and is flagged.
            reg(StringToArray::class) { e ->
                if (e.args["null"] != null) {
                    unsupported(
                        "Argument 'null' is not supported for expression 'StringToArray' when targeting Presto."
                    )
                }
                func("SPLIT", e.thisArg, e.args["expression"])
            }
            reg(Last::class) { e -> pg().firstLastSql(e) }
            reg(LastDay::class) { e -> func("LAST_DAY_OF_MONTH", e.thisArg) }
            reg(Lateral::class) { e -> pg().explodeToUnnestSql(e as Lateral) }
            reg(Left::class) { e -> pg().leftToSubstringSql(e as Left) }
            // sqlglot: unsupported_args("ins_cost", "del_cost", "sub_cost", "max_dist")
            reg(Levenshtein::class) { e ->
                for (arg in listOf("ins_cost", "del_cost", "sub_cost", "max_dist")) {
                    if (e.args[arg] != null) {
                        unsupported(
                            "Argument '$arg' is not supported for expression 'Levenshtein' when targeting Presto."
                        )
                    }
                }
                func("LEVENSHTEIN_DISTANCE", e.thisArg, e.args["expression"])
            }
            reg(LogicalAnd::class) { e -> pg().renameFuncSql("BOOL_AND", e) }
            reg(LogicalOr::class) { e -> pg().renameFuncSql("BOOL_OR", e) }
            reg(Pivot::class) { e -> pg().noPivotSql(e as Pivot) }
            reg(Quantile::class) { e -> pg().quantileSql(e as Quantile) }
            reg(RegexpExtract::class) { e -> pg().regexpExtractSql(e) }
            reg(RegexpExtractAll::class) { e -> pg().regexpExtractSql(e) }
            reg(Right::class) { e -> pg().rightToSubstringSql(e as Right) }
            reg(Schema::class) { e -> pg().prestoSchemaSql(e as Schema) }
            reg(SchemaCommentProperty::class) { e -> nakedProperty(e as Property) }
            // sqlglot: exp.Select preprocess pipeline. Only eliminate_qualify is ported;
            // sqlglot presto order: [eliminate_window_clause, eliminate_qualify,
            // eliminate_distinct_on, explode_projection_to_unnest(1),
            // eliminate_semi_and_anti_joins, amend_exploded_column_table].
            // eliminate_window_clause, explode_projection_to_unnest and
            // amend_exploded_column_table remain NOT PORTED (ledgered).
            reg(Select::class) { e ->
                var s = eliminateQualify(e)
                s = eliminateDistinctOn(s)
                s = eliminateSemiAndAntiJoins(s)
                selectSql(s as Select)
            }
            reg(SortArray::class) { e -> pg().noSortArraySql(e as SortArray) }
            reg(SqlSecurityProperty::class) { e -> "SECURITY ${sql(e, "this")}" }
            reg(StrPosition::class) { e -> pg().strpositionSql(e as StrPosition) }
            reg(StrToDate::class) { e -> "CAST(${pg().strToTimeSql(e)} AS DATE)" }
            reg(StrToMap::class) { e -> pg().renameFuncSql("SPLIT_TO_MAP", e) }
            reg(StrToTime::class) { e -> pg().strToTimeSql(e) }
            reg(StructExtract::class) { e -> pg().structExtractSql(e as StructExtract) }
            // sqlglot: exp.Table preprocess([transforms.unnest_generate_series])
            reg(Table::class) { e ->
                val transformed = pg().unnestGenerateSeries(e as Table)
                if (transformed is Table) tableSql(transformed)
                else sql(transformed, comment = false)
            }
            reg(Timestamp::class) { e -> pg().noTimestampSql(e as Timestamp) }
            reg(TimestampAdd::class) { e -> pg().dateDeltaSql("DATE_ADD", e) }
            reg(TimestampTrunc::class) { e -> pg().timestamptruncSql(e as TimestampTrunc) }
            reg(TimeStrToDate::class) { e -> pg().timestrtotimeSql(e) }
            reg(TimeStrToTime::class) { e -> pg().timestrtotimeSql(e) }
            reg(TimeStrToUnix::class) { e ->
                func("TO_UNIXTIME", func("DATE_PARSE", e.thisArg, pg().dialectTimeFormat))
            }
            reg(TimeToStr::class) { e -> func("DATE_FORMAT", e.thisArg, formatTime(e)) }
            reg(TimeToUnix::class) { e -> pg().renameFuncSql("TO_UNIXTIME", e) }
            reg(ToChar::class) { e -> func("DATE_FORMAT", e.thisArg, formatTime(e)) }
            reg(TsOrDiToDi::class) { e ->
                "CAST(SUBSTR(REPLACE(CAST(${sql(e, "this")} AS VARCHAR), '-', ''), 1, 8) AS INT)"
            }
            reg(TsOrDsAdd::class) { e -> pg().tsOrDsAddSql(e as TsOrDsAdd) }
            reg(TsOrDsDiff::class) { e -> pg().tsOrDsDiffSql(e as TsOrDsDiff) }
            reg(TsOrDsToDate::class) { e -> pg().tsOrDsToDateSql(e as TsOrDsToDate) }
            reg(Unhex::class) { e -> pg().renameFuncSql("FROM_HEX", e) }
            reg(UnixToStr::class) { e ->
                "DATE_FORMAT(FROM_UNIXTIME(${sql(e, "this")}), ${formatTime(e)})"
            }
            reg(UnixToTime::class) { e -> pg().unixToTimeSql(e as UnixToTime) }
            reg(UnixToTimeStr::class) { e ->
                "CAST(FROM_UNIXTIME(${sql(e, "this")}) AS VARCHAR)"
            }
            reg(VariancePop::class) { e -> pg().renameFuncSql("VAR_POP", e) }
            // sqlglot: exp.With preprocess([transforms.add_recursive_cte_column_names])
            reg(With::class) { e -> withSql(pg().addRecursiveCteColumnNames(e as With) as With) }
            // sqlglot: exp.WithinGroup preprocess([transforms.remove_within_group_for_percentiles])
            reg(WithinGroup::class) { e ->
                val transformed = pg().removeWithinGroupForPercentiles(e as WithinGroup)
                if (transformed is WithinGroup) withingroupSql(transformed) else sql(transformed, comment = false)
            }
            reg(Trunc::class) { e -> pg().renameFuncSql("TRUNCATE", e) }
            reg(Xor::class) { e -> pg().boolXorSql(e as Xor) }
            reg(MD5Digest::class) { e -> pg().renameFuncSql("MD5", e) }
            reg(SHA::class) { e -> pg().renameFuncSql("SHA1", e) }
            reg(SHA1Digest::class) { e -> pg().renameFuncSql("SHA1", e) }
            reg(SHA2Digest::class) { e -> pg().sha2DigestSql(e as SHA2Digest) }
            reg(Substring::class) { e -> pg().renameFuncSql("SUBSTR", e) }

            // sqlglot: auto-discovered <name>_sql methods with no base dispatch entry
            reg(JSONFormat::class) { e -> pg().jsonformatSql(e as JSONFormat) }
            reg(MD5::class) { e -> pg().md5Sql(e as MD5) }
            reg(SHA2::class) { e -> pg().sha2Sql(e as SHA2) }
            reg(StrToUnix::class) { e -> pg().strtounixSql(e as StrToUnix) }
            reg(JSONExtract::class) { e -> pg().jsonextractSql(e as JSONExtract) }
            reg(GroupConcat::class) { e -> pg().groupconcatSql(e as GroupConcat) }

            // sqlglot: Generator.hex_sql / lowerhex_sql with HEX_FUNC = "TO_HEX"
            reg(LowerHex::class) { e -> func("LOWER", func("TO_HEX", sql(e, "this"))) }
            reg(Hex::class) { e -> func("TO_HEX", sql(e, "this")) }

            // sqlglot: Generator.arraysize_sql with ARRAY_SIZE_NAME = "CARDINALITY"
            reg(ArraySize::class) { e ->
                if (e.args["expression"] != null) {
                    unsupported("Cannot transpile dimension argument for ARRAY_LENGTH")
                }
                func("CARDINALITY", e.thisArg)
            }

            // sqlglot: Generator.decodecase_sql with SUPPORTS_DECODE_CASE = False
            reg(DecodeCase::class) { e ->
                val exprs = e.expressionsArg.map { it as Expression }
                val decodeExpr = exprs.first()
                val rest = exprs.drop(1)

                val ifs = mutableListOf<Expression>()
                var i = 0
                while (i + 1 < rest.size) {
                    var search = rest[i]
                    val result = rest[i + 1]
                    val cond: Expression = when {
                        search is Literal -> EQ(
                            args("this" to decodeExpr.copy(), "expression" to search)
                        )
                        search is Null -> Is(
                            args("this" to decodeExpr.copy(), "expression" to Null())
                        )
                        else -> {
                            if (search is Binary) search = Paren(args("this" to search))
                            Or(
                                args(
                                    "this" to EQ(
                                        args("this" to decodeExpr.copy(), "expression" to search)
                                    ),
                                    "expression" to And(
                                        args(
                                            "this" to Is(
                                                args(
                                                    "this" to decodeExpr.copy(),
                                                    "expression" to Null(),
                                                )
                                            ),
                                            "expression" to Is(
                                                args(
                                                    "this" to search.copy(),
                                                    "expression" to Null(),
                                                )
                                            ),
                                        )
                                    ),
                                )
                            )
                        }
                    }
                    ifs.add(If(args("this" to cond, "true" to result)))
                    i += 2
                }

                val default = if (rest.size % 2 == 1) rest.last() else null
                sql(Case(args("ifs" to ifs, "default" to default)))
            }

            // sqlglot: base Generator methods reached only from presto/trino ASTs
            reg(JSONExtractQuote::class) { e ->
                val scalar = if (pg().isTruthy((e as JSONExtractQuote).args["scalar"])) " ON SCALAR STRING" else ""
                "${sql(e, "option")} QUOTES$scalar"
            }
            reg(OverflowTruncateBehavior::class) { e ->
                var filler = sql(e, "this")
                if (filler.isNotEmpty()) filler = " $filler"
                val withCount =
                    if (pg().isTruthy(e.args["with_count"])) "WITH COUNT" else "WITHOUT COUNT"
                "TRUNCATE$filler $withCount"
            }
        }

        // sqlglot: PrestoGenerator.RESERVED_KEYWORDS
        val RESERVED_KEYWORDS: Set<String> = setOf(
            "alter", "and", "as", "between", "by", "case", "cast", "constraint", "create",
            "cross", "current_time", "current_timestamp", "deallocate", "delete", "describe",
            "distinct", "drop", "else", "end", "escape", "except", "execute", "exists",
            "extract", "false", "for", "from", "full", "group", "having", "in", "inner",
            "insert", "intersect", "into", "is", "join", "left", "like", "natural", "not",
            "null", "on", "or", "order", "outer", "prepare", "right", "select", "table",
            "then", "true", "union", "using", "values", "when", "where", "with",
        )
    }
}
