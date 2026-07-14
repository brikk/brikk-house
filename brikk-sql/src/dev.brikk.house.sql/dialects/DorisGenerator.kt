package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.DorisTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
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
 * Port of sqlglot's DorisGenerator (reference/sqlglot/sqlglot/generators/doris.py).
 * TRANSFORMS entries live in [TRANSFORMS], passed through MysqlGenerator's dispatch
 * overlay; flag overrides are open-val overrides; multi-line methods below.
 */
// sqlglot: generators.doris.DorisGenerator
open class DorisGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = DorisTokenizerTables.CONFIG,
    sourceDialect: String? = null,
) : MysqlGenerator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = TRANSFORMS,
    sourceDialect = sourceDialect,
) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.DORIS

    // ------------------------------------------------------------------
    // Flags (sqlglot: DorisGenerator class attributes)
    // ------------------------------------------------------------------

    // sqlglot: DorisGenerator.VARCHAR_REQUIRES_SIZE = False
    override val varcharRequiresSize: Boolean get() = false

    // sqlglot: DorisGenerator.WITH_PROPERTIES_PREFIX = "PROPERTIES"
    override val withPropertiesPrefix: String get() = "PROPERTIES"

    // sqlglot: DorisGenerator.RENAME_TABLE_WITH_DB = False
    override val renameTableWithDb: Boolean get() = false

    // sqlglot: DorisGenerator.UPDATE_STATEMENT_SUPPORTS_FROM = True
    override val updateStatementSupportsFrom: Boolean get() = true

    // sqlglot: DorisGenerator.CAST_MAPPING = {}
    override val castMapping: Map<DType, String> get() = emptyMap()

    // sqlglot: DorisGenerator.TIMESTAMP_FUNC_TYPES = set()
    override val timestampFuncTypes: Set<DType> get() = emptySet()

    // sqlglot: DorisGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: DorisGenerator.RESERVED_KEYWORDS
    override val reservedKeywords: Set<String> get() = RESERVED_KEYWORDS

    // sqlglot: DorisGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // sqlglot: Doris.TIME_FORMAT
    override val dialectTimeFormat: String get() = "'yyyy-MM-dd HH:mm:ss'"

    // BUGS-doris-generator-mappings row 7 (P2): ArraySize (duckdb array_length / len) must
    // render as ARRAY_SIZE, not the base default ARRAY_LENGTH. The live Doris FE has NO
    // ARRAY_LENGTH ("Can not found function 'ARRAY_LENGTH'"); the correct name is ARRAY_SIZE
    // (aliases CARDINALITY/SIZE; GeneratedDorisFunctionCatalog: ARRAY_SIZE, live = 3). NOTE:
    // this is fixed at the GENERATOR mapping only — the pinned function catalog is NOT
    // mutated (it faithfully reflects the pinned vendored FE registry; the ARRAY_LENGTH
    // presence/absence is a version skew between our pin and the probe program's live FE).
    override val arraySizeName: String get() = "ARRAY_SIZE"

    // ------------------------------------------------------------------
    // brikk extension: first-class Doris arrays (registry entry 7; upstream-PR candidate)
    //
    // sqlglot's Doris dialect inherits MySQL's array rejection ("Arrays are not supported
    // by MySQL"), but Doris supports arrays natively — pinned against the real FE parser
    // in brikk-sql-verify (SqlVerifierTest.dorisParserSupportsArrays and the array
    // renderings test). Divergences from the Python oracle are listed in
    // docs/brikk-extensions.md entry 7.
    // ------------------------------------------------------------------

    // brikk extension: Doris array subscripts are 1-based (ELEMENT_AT / arr[i] start at 1);
    // sqlglot inherits MySQL's INDEX_OFFSET = 0 and mis-renders e.g. duckdb arr[1] as arr[0].
    // Mirrored by DorisParser.indexOffset.
    override val dialectIndexOffset: Int get() = 1

    // brikk extension: canonical Doris array constructor `ARRAY(1, 2, 3)`, accepted by the
    // FE parser (`[1, 2, 3]` is too; docs use array()). Same rendering sqlglot falls back
    // to, but without the "Arrays are not supported by MySQL" flag.
    override fun arraySql(expression: ArrayNode): String = functionFallbackSql(expression)

    // brikk extension: accurate message — MySQL's blames MySQL, but the real reason is that
    // Doris's ARRAY_CONTAINS_ALL is order-sensitive (subsequence match), so @>/<@ set
    // containment has no result-identical Doris mapping.
    override fun arrayOpUnsupportedSql(expression: Expression): String {
        unsupported(
            "Array set-containment operations have no direct Doris equivalent (ARRAY_CONTAINS_ALL is order-sensitive)"
        )
        return functionFallbackSql(expression as Func)
    }

    // brikk extension: EXPLODE is a table function in Doris, only valid in LATERAL VIEW.
    // Scalar-position UNNEST/EXPLODE (e.g. duckdb `SELECT UNNEST([...])`) has no Doris
    // equivalent, so flag it accurately instead of blaming MySQL's array support; the
    // EXPLODE(...) fallback is still emitted (table-position UNNEST is fine and is
    // rendered by the base unnestSql, which the Doris FE parser accepts).
    open fun explodeSql(expression: Explode): String {
        if (expression.findAncestor(Lateral::class) == null) {
            unsupported(
                "UNNEST/EXPLODE in scalar position has no Doris equivalent (EXPLODE is only valid in LATERAL VIEW)"
            )
        }
        return functionFallbackSql(expression)
    }

    // brikk extension (docs/brikk-extensions.md entry 14): SortArray -> ARRAY_SORT /
    // ARRAY_REVERSE_SORT. Only the plain ascending/descending forms have Doris
    // equivalents; explicit NULL placement or a non-literal direction is flagged.
    open fun dorisSortArraySql(expression: SortArray): String {
        val asc = expression.args["asc"]
        val nullsFirst = expression.args["nulls_first"]
        if (nullsFirst != null) {
            unsupported(
                "Argument 'nulls_first' is not supported for expression 'SortArray' when targeting Doris."
            )
        }
        return when {
            asc == null || (asc is BooleanNode && asc.thisArg == true) ->
                func("ARRAY_SORT", expression.thisArg)
            asc is BooleanNode && asc.thisArg == false ->
                func("ARRAY_REVERSE_SORT", expression.thisArg)
            else -> {
                unsupported("Non-literal sort direction is not supported for SortArray in Doris")
                func("ARRAY_SORT", expression.thisArg)
            }
        }
    }

    // BUGS-doris-generator-mappings enhancement: ArraySlice -> Doris ARRAY_SLICE, which
    // takes (array, offset, LENGTH) rather than DuckDB list_slice's (array, begin, END).
    // Convert an integer-literal end index to a length (END - BEGIN + 1). A non-literal
    // begin/end can't be statically converted, so flag it (rather than emit a diverging
    // naive rename) and fall back to the two-arg ARRAY_SLICE (offset-to-tail) form.
    open fun dorisArraySliceSql(expression: ArraySlice): String {
        val arr = expression.thisArg
        val start = expression.args["start"] as? Expression
        val end = expression.args["end"] as? Expression

        if (end == null) {
            // list_slice(a, begin) -> array_slice(a, offset) (to the tail); identical.
            return func("ARRAY_SLICE", arr, start)
        }

        fun intLit(e: Expression?): Long? =
            if (e is Literal && e.isInt) e.name.toLongOrNull() else null

        val startVal = intLit(start)
        val endVal = intLit(end)
        if (startVal != null && endVal != null) {
            val length = endVal - startVal + 1
            return func("ARRAY_SLICE", arr, start, Literal.number(length.toString()))
        }

        unsupported(
            "list_slice with a non-integer-literal begin/end cannot be converted to Doris " +
                "ARRAY_SLICE's (offset, length) form without diverging"
        )
        return func("ARRAY_SLICE", arr, start)
    }

    // brikk extension (docs/brikk-extensions.md entry 14): SHA2Digest -> UNHEX(SHA2(x, n)).
    // Doris SHA2 supports 224/256/384/512 (MySQL-compatible); see the TRANSFORMS entry
    // for the return-shape (hex-VARCHAR vs VARBINARY) evidence.
    open fun dorisSha2DigestSql(expression: SHA2Digest): String {
        val length = expression.args["length"] as? Expression ?: Literal.number("256")
        return func("UNHEX", func("SHA2", expression.thisArg, length))
    }

    // brikk extension (docs/brikk-extensions.md entry 14): GenerateSeries -> ARRAY_RANGE.
    // Doris ARRAY_RANGE is end-EXCLUSIVE (doris-signatures.json; docs "[start, end)"),
    // matching duckdb `range` exactly. Inclusive sources (duckdb generate_series, trino
    // sequence) are mapped by shifting the stop bound one step in the step's direction,
    // which is only decidable for an absent (defaults to 1) or integer-literal step —
    // anything else keeps the Python fallback and is flagged.
    open fun dorisGenerateSeriesSql(expression: GenerateSeries): String {
        val start = expression.args["start"] as? Expression
        val end = expression.args["end"] as? Expression
        val step = expression.args["step"] as? Expression

        if (expression.args["is_end_exclusive"] == true) {
            return func("ARRAY_RANGE", start, end, step)
        }

        // Inclusive semantics: determine the step direction.
        val stepSign: Int? = when {
            step == null -> 1
            step is Literal && step.isInt -> if ((step.name.toLongOrNull() ?: 0L) >= 0) 1 else -1
            step is Neg && step.isInt -> -1
            else -> null
        }
        if (stepSign == null) {
            unsupported(
                "Inclusive GENERATE_SERIES/SEQUENCE with a non-integer-literal step has no " +
                    "Doris equivalent (ARRAY_RANGE is end-exclusive)"
            )
            return functionFallbackSql(expression)
        }
        val shifted: Expression = if (stepSign >= 0) {
            Add(args("this" to end?.copy(), "expression" to Literal.number("1")))
        } else {
            Sub(args("this" to end?.copy(), "expression" to Literal.number("1")))
        }
        return func("ARRAY_RANGE", start, shifted, step)
    }

    // ------------------------------------------------------------------
    // Transform helpers (sqlglot: module-level functions in generators/doris.py)
    // ------------------------------------------------------------------

    // sqlglot: generators.doris._lag_lead_sql
    open fun lagLeadSql(expression: Expression): String =
        func(
            if (expression is Lag) "LAG" else "LEAD",
            expression.thisArg,
            expression.args["offset"] ?: Literal.number("1"),
            expression.args["default"] ?: Null(),
        )

    // sqlglot: dialect.approx_count_distinct_sql (@unsupported_args("accuracy"))
    open fun approxCountDistinctSql(expression: ApproxDistinct): String {
        if (isTruthy(expression.args["accuracy"])) {
            unsupported(
                "Argument 'accuracy' is not supported for expression 'ApproxDistinct' when targeting Doris."
            )
        }
        return func("APPROX_COUNT_DISTINCT", expression.thisArg)
    }

    // sqlglot: dialect.time_format("doris") — format unless it's the dialect default
    open fun dorisTimeFormat(expression: Expression): String? {
        val timeFormat = formatTime(expression)
        return if (timeFormat != dialectTimeFormat) timeFormat else null
    }

    // BUGS-doris-generator-mappings row 2 (P1): UnixToTime -> the Doris epoch family.
    // UnixToTime carries a `scale` (10^scale sub-second units per second: 0 = seconds,
    // 3 = ms [duckdb epoch_ms], 6 = us) and optionally a `format`. Doris exposes a
    // scale-keyed conversion family (FROM_SECOND / FROM_MILLISECOND / FROM_MICROSECOND,
    // each BIGINT -> DATETIME; GeneratedDorisFunctionCatalog), which is the correct idiom
    // for a bare unix->datetime conversion. When a FORMAT is present the source wants a
    // formatted STRING, which is exactly FROM_UNIXTIME(seconds, format) — but that path
    // only accepts SECONDS, so a non-seconds scale with a format is flagged (no direct
    // Doris equivalent) and rendered seconds-style rather than silently overflowing.
    open fun dorisUnixToTimeSql(expression: UnixToTime): String {
        val scale = expression.args["scale"]
        val format = dorisTimeFormat(expression)
        val scaleStr: String? = when {
            scale == null -> "0"
            scale is Literal && !scale.isString -> scale.name
            else -> null
        }

        if (format != null) {
            // Formatted output: FROM_UNIXTIME(seconds, format). Only valid for seconds.
            if (scaleStr != "0") {
                unsupported(
                    "FROM_UNIXTIME with a format string only accepts SECONDS; a sub-second " +
                        "scale ($scaleStr) has no direct Doris equivalent"
                )
            }
            return func("FROM_UNIXTIME", expression.thisArg, format)
        }

        val fn = when (scaleStr) {
            "0" -> "FROM_SECOND"
            "3" -> "FROM_MILLISECOND"
            "6" -> "FROM_MICROSECOND"
            else -> {
                unsupported(
                    "UnixToTime scale '$scaleStr' has no Doris epoch-conversion function " +
                        "(FROM_SECOND/FROM_MILLISECOND/FROM_MICROSECOND cover 0/3/6)"
                )
                "FROM_SECOND"
            }
        }
        return func(fn, expression.thisArg)
    }

    // sqlglot: Generator.lastday_sql with LAST_DAY_SUPPORTS_DATE_PART = False
    open fun lastdaySql(expression: LastDay): String {
        val unit = expression.text("unit")
        if (unit.isNotEmpty() && unit != "MONTH") {
            unsupported("Date parts are not supported in LAST_DAY.")
        }
        return func("LAST_DAY", expression.thisArg)
    }

    // ------------------------------------------------------------------
    // Overridden generator methods (sqlglot: generators/doris.py methods)
    // ------------------------------------------------------------------

    // BUGS-doris-generator-mappings row 4 (P1): duckdb struct_pack(a:=1,...) parses to a
    // Struct of PropertyEQ (name := value). The base rendering emits STRUCT(1 AS a, ...),
    // but Doris STRUCT rejects `expr AS name` alias syntax (error) and bare STRUCT(1,'x')
    // loses the field names. Doris NAMED_STRUCT('a',1,'b','x') (GeneratedDorisFunctionCatalog:
    // NAMED_STRUCT, since 2.0; verified live {"a":1,"b":"x"}) preserves them. Named-less
    // struct() literals (no PropertyEQ) keep the base STRUCT rendering.
    override fun structSql(expression: Struct): String {
        val exprs = expression.expressionsArg
        val allNamed = exprs.isNotEmpty() && exprs.all { it is PropertyEQ }
        if (!allNamed) return super.structSql(expression)

        val args = mutableListOf<kotlin.Any?>()
        for (e in exprs) {
            val prop = e as PropertyEQ
            // PropertyEQ `this` is the field name (Identifier/Column/Literal), rendered as
            // a string-literal key for NAMED_STRUCT.
            args.add(Literal.string(prop.name))
            args.add(prop.args["expression"])
        }
        return func("NAMED_STRUCT", *args.toTypedArray())
    }

    // BUGS-doris-generator-mappings enhancements: a small allowlist of source functions
    // that reach us as unresolved Anonymous calls (no canonical AST node exists) but have
    // a trivially-identical Doris equivalent under a different NAME. Renaming the emitted
    // name (arguments unchanged) is result-identical and lifts the fail-loud refusal.
    // Evidence: REPORT-doris-differential-probe-2026-07-13.md "Verified live" list.
    //   greatest_common_divisor -> GCD, least_common_multiple -> LCM (duckdb),
    //   st_aswkb -> ST_ASBINARY (duckdb geometry).
    private val anonymousRenames: Map<String, String> = mapOf(
        "GREATEST_COMMON_DIVISOR" to "GCD",
        "LEAST_COMMON_MULTIPLE" to "LCM",
        "ST_ASWKB" to "ST_ASBINARY",
    ) + REVERSE_CLICKHOUSE_RENAMES

    override fun anonymousSql(expression: Anonymous): String {
        // Reverse direction (ClickHouse -> Doris): ClickHouse's splitByRegexp(pattern, str)
        // maps to Doris split_by_regexp(str, pattern) — SAME function, ARGS SWAPPED (live
        // value-equal, doris-ducklake agent 2026-07-14). Handle before the plain-name map.
        if (expression.name.equals("splitByRegexp", ignoreCase = true)) {
            val a = expression.expressionsArg
            if (a.size == 2) return func("SPLIT_BY_REGEXP", a[1], a[0])
        }
        val renamed = anonymousRenames[expression.name.uppercase()]
        if (renamed != null) {
            return func(renamed, *expression.expressionsArg.toTypedArray())
        }
        return super.anonymousSql(expression)
    }

    // sqlglot: DorisGenerator.uniquekeyproperty_sql
    override fun uniquekeypropertySql(expression: UniqueKeyProperty, prefix: String): String {
        val createStmt = expression.findAncestor(Create::class)
        val properties = createStmt?.args?.get("properties") as? Expression
        if (properties?.find(MaterializedProperty::class) != null) {
            return super.uniquekeypropertySql(expression, prefix = "KEY")
        }

        return super.uniquekeypropertySql(expression, prefix)
    }

    // brikk extension (docs/brikk-extensions.md #10, NOT sqlglot parity): Doris
    // materialized-view column lists take bare column names, optionally with a COMMENT
    // (reference/doris .../DorisParser.g4 `simpleColumnDef : colName=identifier (COMMENT
    // comment=STRING_LITERAL)?`) — column types are derived from the query and cannot be
    // declared. sqlglot re-emits full typed column defs (`c1 INT`), which the FE parser
    // rejects, so we strip the type from MV schema columns (dropping only what Doris
    // cannot express; the engine derives the same column from the query either way).
    override fun columndefSql(expression: ColumnDef, sep: String): String {
        if (expression.args["kind"] != null) {
            val create = expression.findAncestor(Create::class)
            val isMaterializedView = (create?.args?.get("properties") as? Expression)
                ?.find(MaterializedProperty::class) != null
            if (isMaterializedView && expression.parent === create?.thisArg) {
                val bare = expression.copy() as ColumnDef
                bare.set("kind", null)
                return super.columndefSql(bare, sep)
            }
        }
        return super.columndefSql(expression, sep)
    }

    // sqlglot: DorisGenerator.partitionrange_sql
    override fun partitionrangeSql(expression: PartitionRange): String {
        val name = sql(expression, "this")
        val values = expression.expressionsArg

        if (values.size != 1) {
            // Multiple values: use VALUES [ ... )
            val valuesSql = if (values.isNotEmpty() && values[0] is List<*>) {
                values.joinToString(", ") { inner ->
                    "(${(inner as List<*>).joinToString(", ") { sql(it) }})"
                }
            } else {
                values.joinToString(", ") { "(${sql(it)})" }
            }

            return "PARTITION $name VALUES [$valuesSql)"
        }

        return "PARTITION $name VALUES LESS THAN (${sql(values[0])})"
    }

    // sqlglot: DorisGenerator.partitionbyrangepropertydynamic_sql
    open fun partitionbyrangepropertydynamicSql(expression: PartitionByRangePropertyDynamic): String {
        // Generates: FROM ("start") TO ("end") INTERVAL N UNIT
        val start = sql(expression, "start")
        val end = sql(expression, "end")
        val every = expression.args["every"] as? Expression

        val interval = if (every != null) {
            "INTERVAL ${sql(every, "this")} ${sql(every, "unit")}"
        } else {
            ""
        }

        return "FROM ($start) TO ($end) $interval"
    }

    // sqlglot: DorisGenerator.partitionedbyproperty_sql
    // brikk extension (docs/brikk-extensions.md #9, NOT sqlglot parity): sqlglot emits a
    // bare `PARTITION BY (cols)` for CREATE TABLE, but Doris's grammar
    // (reference/doris .../DorisParser.g4 `partitionTable`) requires a parenthesized
    // partition-definition list after the column list:
    //   PARTITION BY (RANGE | LIST)? identityOrFunctionList '(' partitionsDef? ')'
    // We complete the clause with Doris's own defaults, both FE-analyzer-valid:
    //   - column partition keys -> `PARTITION BY (cols) ()` — the kind-less form is LIST
    //     per the FE (LogicalPlanBuilder.visitPartitionTable), with partitions added later;
    //   - a function partition key (e.g. DATE_TRUNC) -> `PARTITION BY RANGE (expr) ()` —
    //     the FE auto-infers AUTO partitioning from the function expression, and the
    //     internal catalog rejects functions in LIST partitions, so RANGE is the only
    //     analyzer-valid completion (PartitionTableInfo.validatePartitionInfo).
    // CREATE MATERIALIZED VIEW keeps the bare form: its `PARTITION BY '(' mvPartition ')'`
    // rule takes no partition-definition list.
    open fun partitionedbypropertySql(expression: PartitionedByProperty): String {
        val this_ = expression.thisArg
        val cols = if (this_ is Schema) expressions(this_, flat = true) else sql(this_)

        val create = expression.findAncestor(Create::class)
        val isMaterializedView = (create?.args?.get("properties") as? Expression)
            ?.find(MaterializedProperty::class) != null
        if (create == null || isMaterializedView || create.args["kind"] != "TABLE") {
            return "PARTITION BY ($cols)"
        }

        val keys: List<kotlin.Any?> = if (this_ is Schema) this_.expressionsArg else listOf(this_)
        val hasFunctionKey = keys.any { it is Expression && it !is Column && it !is Identifier }
        val kind = if (hasFunctionKey) "RANGE " else ""
        return "PARTITION BY $kind($cols) ()"
    }

    // brikk extension (NOT sqlglot parity): Doris has no FILTER clause; sqlglot passes it
    // through (invalid Doris SQL). We rewrite a conservative allowlist of aggregates into
    // an equivalent CASE form and raise UnsupportedError for everything else, so we never
    // emit silently-wrong SQL:
    //   AGG(expr) FILTER (WHERE cond)          -> AGG(CASE WHEN cond THEN expr END)
    //   COUNT(*) FILTER (WHERE cond)           -> COUNT(CASE WHEN cond THEN 1 END)
    //   COUNT(DISTINCT x) FILTER (WHERE cond)  -> COUNT(DISTINCT CASE WHEN cond THEN x END)
    // Allowlist: COUNT / SUM / MIN / MAX / AVG / ANY_VALUE / ARRAY_AGG (simple argument
    // only). Rejected: multi-argument aggregates, multi-column DISTINCT, ordered
    // ARRAY_AGG, and anything else (e.g. GROUP_CONCAT, whose separator handling is not
    // result-identical under the rewrite).
    override fun filterSql(expression: Filter): String {
        fun fail(reason: String): Nothing = throw UnsupportedError(
            "Doris has no FILTER clause and the aggregate cannot be safely rewritten to CASE: $reason"
        )

        val cond = ((expression.args["expression"] as? Expression)?.thisArg as? Expression)
            ?: fail("missing filter condition")

        // duckdb wraps e.g. ANY_VALUE in IgnoreNulls; rewrite the aggregate inside it.
        val holder = (expression.thisArg as? Expression) ?: fail("missing aggregate")
        val holderCopy = holder.copy()
        val agg = if (holderCopy is IgnoreNulls || holderCopy is RespectNulls) {
            holderCopy.thisArg as? Expression ?: fail("missing aggregate")
        } else {
            holderCopy
        }

        when (agg) {
            is Count, is Sum, is Min, is Max, is Avg, is AnyValue, is ArrayAgg -> Unit
            else -> fail("unsupported aggregate ${agg::class.simpleName}")
        }
        if ((agg.args["expressions"] as? List<*>).orEmpty().isNotEmpty()) {
            fail("aggregate has multiple arguments")
        }

        fun caseWhen(result: Expression): Case =
            Case(args("ifs" to listOf(If(args("this" to cond.copy(), "true" to result)))))

        when (val arg = agg.thisArg) {
            is Star -> agg.set("this", caseWhen(Literal.number("1"))) // COUNT(*)
            is Distinct -> {
                val distinctExprs = (arg.args["expressions"] as? List<*>).orEmpty()
                val single = distinctExprs.singleOrNull() as? Expression
                    ?: fail("DISTINCT over multiple expressions")
                arg.set("expressions", listOf(caseWhen(single.copy())))
            }
            is Order -> fail("aggregate has an ORDER BY clause")
            is Expression -> agg.set("this", caseWhen(arg.copy()))
            else -> fail("aggregate has no argument")
        }

        return sql(holderCopy)
    }

    // sqlglot: DorisGenerator.table_sql — no AS keyword in UPDATE and DELETE statements
    override fun tableSql(expression: Table, sep: String): String {
        val ancestor = expression.findAncestor(Update::class, Delete::class, Select::class)
        val actualSep = if (ancestor !is Select) " " else sep
        return super.tableSql(expression, sep = actualSep)
    }

    companion object {

        // Reverse direction (ClickHouse -> Doris) function-name renames for unmapped
        // Anonymous calls. Key = ClickHouse spelling UPPERCASED (matched via
        // expression.name.uppercase()); value = the Doris name. Every entry LIVE-verified
        // value-equal on Doris + ClickHouse 26.5.1.1 by the doris-ducklake agent
        // (docs/research/probe-runs/reverse-doris-trino.results.tsv). Round-trip safe: the
        // keys are ClickHouse camelCase names (no underscores) that Doris neither parses to
        // a node nor accepts, so native Doris generation is untouched.
        // Deliberately EXCLUDED: arrayUniq (Doris has no array_unique), jaroSimilarity (no
        // Doris jaro fn), xxHash32/64 (Doris xxhash_* is a DIFFERENT hash value), and
        // splitByRegexp (handled with an arg-swap in anonymousSql). arrayElement->element_at
        // is value-equal here but stays a divergent hazard (negative-index semantics).
        private val REVERSE_CLICKHOUSE_RENAMES: Map<String, String> = mapOf(
            "ARRAYSORT" to "ARRAY_SORT",
            "ARRAYREVERSESORT" to "ARRAY_REVERSE_SORT",
            "ARRAYINTERSECT" to "ARRAY_INTERSECT",
            "ARRAYAVG" to "ARRAY_AVG",
            "ARRAYCOMPACT" to "ARRAY_COMPACT",
            "ARRAYCOUNT" to "ARRAY_COUNT",
            "ARRAYCUMSUM" to "ARRAY_CUM_SUM",
            "ARRAYDIFFERENCE" to "ARRAY_DIFFERENCE",
            "ARRAYENUMERATE" to "ARRAY_ENUMERATE",
            "ARRAYENUMERATEUNIQ" to "ARRAY_ENUMERATE_UNIQ",
            "ARRAYEXCEPT" to "ARRAY_EXCEPT",
            "ARRAYPOPBACK" to "ARRAY_POPBACK",
            "ARRAYPOPFRONT" to "ARRAY_POPFRONT",
            "ARRAYPRODUCT" to "ARRAY_PRODUCT",
            "ARRAYELEMENT" to "ELEMENT_AT", // divergent (negative-index) — hazard kept
            "BITCOUNT" to "BIT_COUNT",
            "BITSHIFTLEFT" to "BIT_SHIFT_LEFT",
            "BITSHIFTRIGHT" to "BIT_SHIFT_RIGHT",
            "BITTEST" to "BIT_TEST",
            "COUNTSUBSTRINGS" to "COUNT_SUBSTRINGS",
            "L1DISTANCE" to "L1_DISTANCE",
            "ROUNDBANKERS" to "ROUND_BANKERS",
            "IPV4NUMTOSTRING" to "IPV4_NUM_TO_STRING",
            "ISIPV4STRING" to "IS_IPV4_STRING",
            "TOIPV4ORDEFAULT" to "TO_IPV4_OR_DEFAULT",
        )

        // sqlglot: DorisGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = MysqlGenerator.TYPE_MAPPING + mapOf(
            DType.TEXT to "STRING",
            DType.TIMESTAMP to "DATETIME",
            DType.TIMESTAMPTZ to "DATETIME",
        )

        // sqlglot: DorisGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            MysqlGenerator.PROPERTIES_LOCATION + mapOf(
                UniqueKeyProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                PartitionedByProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                BuildProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
            )

        // sqlglot: DorisGenerator.TRANSFORMS (dispatch-map overlay over MysqlGenerator's;
        // multi-line entries are methods on DorisGenerator, one-liners inlined)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.dg(): DorisGenerator = this as DorisGenerator

            reg(AddMonths::class) { e -> dg().renameFuncSql("MONTHS_ADD", e) }
            // BUGS-doris-generator-mappings row 1 (P1): duckdb list_has_any(a,b) parses to
            // ArrayOverlaps, whose base rendering is `a && b`. Doris `&&` is logical-AND
            // and cannot cast arrays to boolean (runtime error), so emit ARRAYS_OVERLAP(a,b)
            // (GeneratedDorisFunctionCatalog: ARRAYS_OVERLAP; verified live = 1).
            reg(ArrayOverlaps::class) { e ->
                func("ARRAYS_OVERLAP", (e as Binary).left, e.right)
            }
            reg(ApproxDistinct::class) { e -> dg().approxCountDistinctSql(e as ApproxDistinct) }
            reg(ArgMax::class) { e -> dg().renameFuncSql("MAX_BY", e) }
            reg(ArgMin::class) { e -> dg().renameFuncSql("MIN_BY", e) }
            reg(ArrayAgg::class) { e -> dg().renameFuncSql("COLLECT_LIST", e) }
            reg(ArrayToString::class) { e -> dg().renameFuncSql("ARRAY_JOIN", e) }
            // BUGS-doris-generator-mappings enhancement: duckdb list_slice(a,begin,END) ->
            // ArraySlice. Doris ARRAY_SLICE(arr, offset, LENGTH) uses a length, not an end
            // index, so dorisArraySliceSql converts end->length. Verified: list_slice(a,2,4)
            // = [a2,a3,a4] equals array_slice(a,2,3).
            reg(ArraySlice::class) { e -> dg().dorisArraySliceSql(e as ArraySlice) }
            reg(ArrayUniqueAgg::class) { e -> dg().renameFuncSql("COLLECT_SET", e) }
            reg(CurrentDate::class) { _ -> func("CURRENT_DATE") }
            reg(CurrentTimestamp::class) { _ -> func("NOW") }
            reg(DateTrunc::class) { e -> func("DATE_TRUNC", e.thisArg, unitToStr(e)) }
            reg(EuclideanDistance::class) { e -> dg().renameFuncSql("L2_DISTANCE", e) }
            // brikk extension: scalar-position EXPLODE flagging (see explodeSql)
            reg(Explode::class) { e -> dg().explodeSql(e as Explode) }
            reg(GroupConcat::class) { e ->
                func("GROUP_CONCAT", e.thisArg, e.args["separator"] ?: Literal.string(","))
            }
            // BUGS-doris-generator-mappings row 6 (P1): trino json_extract_scalar unwraps
            // to a RAW scalar, but Doris JSON_EXTRACT keeps JSON quotes on string scalars
            // (`'"hi"'` vs `'hi'`). Wrap in JSON_UNQUOTE so string scalars come back
            // unquoted, matching Trino (numeric scalars are unaffected). Verified live =
            // 'hi' (REPORT batch11-bucketb trino). Was previously bare JSON_EXTRACT.
            reg(JSONExtractScalar::class) { e ->
                func("JSON_UNQUOTE", func("JSON_EXTRACT", e.thisArg, e.args["expression"]))
            }
            // BUGS-doris-generator-mappings row 5 (P1): trino json_array_contains(j,v)
            // parses to JSONArrayContains, whose inherited MySQL rendering is
            // `j MEMBER OF(v)` — this errors at runtime on Doris (both operand orders).
            // Doris JSON_CONTAINS(j, v) (GeneratedDorisFunctionCatalog; verified live = 1)
            // is the equivalent.
            reg(JSONArrayContains::class) { e ->
                func("JSON_CONTAINS", (e as Binary).left, e.right)
            }
            // BUGS-doris-generator-mappings enhancement: trino is_nan(x) parses to IsNan;
            // Doris function is ISNAN, not IS_NAN (GeneratedDorisFunctionCatalog: ISNAN).
            // (DuckdbGenerator already renders IsNan -> ISNAN; Doris was falling back to the
            // verbatim IS_NAN sql-name and refusing.)
            reg(IsNan::class) { e -> dg().renameFuncSql("ISNAN", e) }
            reg(Lag::class) { e -> dg().lagLeadSql(e) }
            reg(Lead::class) { e -> dg().lagLeadSql(e) }
            reg(MapNode::class) { e -> dg().renameFuncSql("ARRAY_MAP", e) }
            reg(Property::class) { e ->
                "${propertyName(e as Property, stringKey = true)}=${sql(e, "value")}"
            }
            reg(RegexpLike::class) { e -> dg().renameFuncSql("REGEXP", e) }
            // BUGS-doris-generator-mappings row 3 (P1): string_split_regex / regexp_split
            // are REGEX splits, but SPLIT_BY_STRING splits on the pattern as a LITERAL.
            // Doris SPLIT_BY_REGEXP (alias REGEXP_SPLIT_TO_ARRAY, doris-signatures.json /
            // GeneratedDorisFunctionCatalog) is the regex-splitting function. The literal
            // Split / StringToArray nodes correctly keep SPLIT_BY_STRING below.
            reg(RegexpSplit::class) { e -> dg().renameFuncSql("SPLIT_BY_REGEXP", e) }
            reg(SchemaCommentProperty::class) { e -> nakedProperty(e as Property) }
            reg(Split::class) { e -> dg().renameFuncSql("SPLIT_BY_STRING", e) }
            reg(StringToArray::class) { e -> dg().renameFuncSql("SPLIT_BY_STRING", e) }
            reg(StrToUnix::class) { e -> func("UNIX_TIMESTAMP", e.thisArg, formatTime(e)) }
            reg(TimeStrToDate::class) { e -> dg().renameFuncSql("TO_DATE", e) }
            reg(TsOrDsAdd::class) { e -> func("DATE_ADD", e.thisArg, e.args["expression"]) }
            // BUGS-doris-generator-mappings row 8 (P3): duckdb strftime(ts,fmt) parses to
            // TimeToStr and renders DATE_FORMAT(ts, fmt) directly. But re-parsing that under
            // Doris (MysqlParser.DATE_FORMAT) wraps the arg in the INTERNAL
            // TsOrDsToTimestamp node, which had no Doris renderer -> the capability check
            // saw TS_OR_DS_TO_TIMESTAMP verbatim and falsely REFUSED (UNMAPPABLE). Doris has
            // no TS_OR_DS_TO_TIMESTAMP function; the node just means "coerce to datetime",
            // so render it as a plain CAST(... AS DATETIME) — matches the live behavior of
            // date_format(cast(ts as datetime), fmt).
            reg(TsOrDsToTimestamp::class) { e ->
                sql(Cast(args("this" to e.thisArg, "to" to DataType(args("this" to DType.DATETIME)))))
            }
            reg(TsOrDsToDate::class) { e -> func("TO_DATE", e.thisArg) }
            reg(TimeToUnix::class) { e -> dg().renameFuncSql("UNIX_TIMESTAMP", e) }
            reg(TimestampTrunc::class) { e -> func("DATE_TRUNC", e.thisArg, unitToStr(e)) }
            reg(UnixToStr::class) { e ->
                func("FROM_UNIXTIME", e.thisArg, dg().dorisTimeFormat(e))
            }
            // BUGS-doris-generator-mappings row 2 (P1): epoch_ms(ms) parses to
            // UnixToTime(scale=3). The old `renameFuncSql("FROM_UNIXTIME", e)` flattened
            // ALL args -> FROM_UNIXTIME(ms, 3), which is doubly wrong: Doris FROM_UNIXTIME
            // takes SECONDS (ms overflows) and its 2nd arg is a FORMAT STRING (literal 3 ->
            // '3'), not a fractional-second scale. Doris has a scale-keyed epoch family
            // FROM_SECOND / FROM_MILLISECOND / FROM_MICROSECOND (all BIGINT -> DATETIME),
            // used by dorisUnixToTimeSql below.
            reg(UnixToTime::class) { e -> dg().dorisUnixToTimeSql(e as UnixToTime) }

            // brikk extension (docs/brikk-extensions.md entry 14, NOT sqlglot parity):
            // catalog-backed fixes for renders the Python oracle emits under names Doris
            // does not have (gap-report.json bucket B "absent-name" entries). Evidence per
            // entry cites vendor/data/doris-signatures.json (FE registry extract) and,
            // where semantics matter, the FE sources under reference/doris.
            //
            // bit_and/bit_or/bit_xor aggregates -> GROUP_BIT_AND/OR/XOR
            // (doris-signatures.json: GroupBitAnd/GroupBitOr/GroupBitXor over integer
            // types; MySQL's BIT_AND etc. do not exist in Doris).
            reg(BitwiseAndAgg::class) { e -> dg().renameFuncSql("GROUP_BIT_AND", e) }
            reg(BitwiseOrAgg::class) { e -> dg().renameFuncSql("GROUP_BIT_OR", e) }
            reg(BitwiseXorAgg::class) { e -> dg().renameFuncSql("GROUP_BIT_XOR", e) }
            // duckdb isodow / trino day_of_week (ISO Monday=1..Sunday=7) -> WEEKDAY(x)+1
            // (doris-signatures.json: WEEKDAY(DATE|DATETIME) -> TINYINT, MySQL-compatible
            // Monday=0..Sunday=6; Python emits DAYOFWEEK_ISO, which Doris does not have.
            // Doris DAYOFWEEK is Sunday=1, so a bare rename would be off by a rotation.)
            reg(DayOfWeekIso::class) { e -> "(${func("WEEKDAY", e.thisArg)} + 1)" }
            // duckdb list_filter(arr, lambda) -> ARRAY_FILTER(lambda, arr): the FE lambda
            // form takes the lambda FIRST (reference/doris ArrayFilter.java:
            // "array_filter(lambda, a1, ...)"); Python emits FILTER(arr, lambda), which
            // Doris does not have.
            reg(ArrayFilter::class) { e ->
                func("ARRAY_FILTER", e.args["expression"], e.thisArg)
            }
            // duckdb list_transform(arr, lambda) -> ARRAY_MAP(lambda, arr) (reference/doris
            // ArrayMap.java: SIGNATURES args(LambdaType) — lambda-first); Python emits
            // TRANSFORM, which Doris does not have.
            reg(Transform::class) { e ->
                func("ARRAY_MAP", e.args["expression"], e.thisArg)
            }
            // duckdb list_prepend -> ARRAY_PUSHFRONT(arr, elem) (reference/doris
            // ArrayPushFront.java: args(ArrayType, element)); Python emits ARRAY_PREPEND,
            // which Doris does not have. Node args are (this=arr, expression=elem).
            reg(ArrayPrepend::class) { e -> dg().renameFuncSql("ARRAY_PUSHFRONT", e) }
            // duckdb list_sort / list_reverse_sort -> ARRAY_SORT / ARRAY_REVERSE_SORT
            // (doris-signatures.json: both take ARRAY<ANY>; default NULL placement matches
            // DuckDB's — ASC keeps NULLs first, DESC keeps NULLs last); Python emits
            // SORT_ARRAY(arr[, FALSE]) (the Hive name), which Doris does not have.
            reg(SortArray::class) { e -> dg().dorisSortArraySql(e as SortArray) }
            // trino sha256/sha512 -> UNHEX(SHA2(x, n)): Doris SHA2 returns the hex-string
            // digest (doris-signatures.json: SHA2(VARCHAR|STRING|VARBINARY, INT) ->
            // VARCHAR) while Trino's sha256/sha512 return the raw VARBINARY digest — the
            // same hex-VARCHAR vs VARBINARY return-shape hazard the trino<->duckdb
            // research pinned for hashes (trino-duckdb-hazards.json: "unhex wrap is
            // mandatory"), so the UNHEX(...) wrap mirrors the DuckDB treatment
            // (UNHEX(SHA256(x))). Python emits S_H_A2_DIGEST(x, n) — a broken default
            // sql_name Doris does not have.
            reg(SHA2Digest::class) { e -> dg().dorisSha2DigestSql(e as SHA2Digest) }
            // trino md5 (VARBINARY digest) -> UNHEX(MD5(x)) — same return-shape reasoning
            // as SHA2Digest; Python emits MD5_DIGEST, which Doris does not have.
            reg(MD5Digest::class) { e -> func("UNHEX", func("MD5", e.thisArg)) }
            // trino approx_percentile(x, p) -> PERCENTILE_APPROX(x, p)
            // (doris-signatures.json: PERCENTILE_APPROX(DOUBLE, DOUBLE[, DOUBLE]) —
            // approximate percentile on both sides). Trino's accuracy (0..1 fraction) is
            // NOT Doris's compression (2048..10000) so it is flagged, as is the weighted
            // form. Python emits APPROX_QUANTILE, which Doris does not have.
            reg(ApproxQuantile::class) { e ->
                for (arg in listOf("accuracy", "weight", "error_tolerance")) {
                    if (e.args[arg] != null) {
                        unsupported(
                            "Argument '$arg' is not supported for expression 'ApproxQuantile' when targeting Doris."
                        )
                    }
                }
                func("PERCENTILE_APPROX", e.thisArg, e.args["quantile"])
            }
            // duckdb range/generate_series and trino sequence -> ARRAY_RANGE
            // (doris-signatures.json: ARRAY_RANGE(INT[, INT[, INT]]) and
            // (DATETIME, DATETIME, INTERVAL), end-EXCLUSIVE like duckdb range); Python
            // emits GENERATE_SERIES, which Doris does not have.
            reg(GenerateSeries::class) { e -> dg().dorisGenerateSeriesSql(e as GenerateSeries) }

            // BUGS-doris-generator-mappings row 9 (P3): trino from_iso8601_timestamp_nanos(s)
            // parses to FromISO8601TimestampNanos, whose base rendering is CAST(s AS
            // TIMESTAMPTZ) -> Doris CAST(s AS DATETIME), which drops ALL fractional seconds.
            // Doris DATETIME(6) keeps microseconds (its max sub-second precision), so cast
            // to DATETIME(6) to retain as much as Doris can represent. LOSSY: the Trino
            // source keeps NANOseconds (9 digits); Doris DATETIME tops out at microseconds
            // (6 digits), so the final 3 digits of nanosecond precision are unrepresentable
            // and silently dropped.
            reg(FromISO8601TimestampNanos::class) { e ->
                sql(
                    Cast(
                        args(
                            "this" to e.thisArg,
                            "to" to DataType(
                                args(
                                    "this" to DType.DATETIME,
                                    "expressions" to listOf(
                                        DataTypeParam(args("this" to Literal.number("6")))
                                    ),
                                )
                            ),
                        )
                    )
                )
            }

            // sqlglot: LAST_DAY_SUPPORTS_DATE_PART = False (base lastday_sql flag)
            reg(LastDay::class) { e -> dg().lastdaySql(e as LastDay) }

            // sqlglot: auto-discovered <name>_sql methods with no base dispatch entry
            reg(PartitionByRangePropertyDynamic::class) { e ->
                dg().partitionbyrangepropertydynamicSql(e as PartitionByRangePropertyDynamic)
            }
            reg(PartitionedByProperty::class) { e ->
                dg().partitionedbypropertySql(e as PartitionedByProperty)
            }
        }

        // sqlglot: DorisGenerator.RESERVED_KEYWORDS
        // https://github.com/apache/doris/blob/e4f41dbf1ec03f5937fdeba2ee1454a20254015b/fe/fe-core/src/main/antlr4/org/apache/doris/nereids/DorisLexer.g4#L93
        val RESERVED_KEYWORDS: Set<String> = setOf(
            "account_lock", "account_unlock", "add", "adddate", "admin", "after", "agg_state",
            "aggregate", "alias", "all", "alter", "analyze", "analyzed", "and", "anti", "append",
            "array", "array_range", "as", "asc", "at", "authors", "auto", "auto_increment",
            "backend", "backends", "backup", "begin", "belong", "between", "bigint", "bin",
            "binary", "binlog", "bitand", "bitmap", "bitmap_union", "bitor", "bitxor", "blob",
            "boolean", "brief", "broker", "buckets", "build", "builtin", "bulk", "by", "cached",
            "call", "cancel", "case", "cast", "catalog", "catalogs", "chain", "char", "character",
            "charset", "check", "clean", "cluster", "clusters", "collate", "collation", "collect",
            "column", "columns", "comment", "commit", "committed", "compact", "complete",
            "config", "connection", "connection_id", "consistent", "constraint", "constraints",
            "convert", "copy", "count", "create", "creation", "cron", "cross", "cube", "current",
            "current_catalog", "current_date", "current_time", "current_timestamp",
            "current_user", "data", "database", "databases", "date", "date_add", "date_ceil",
            "date_diff", "date_floor", "date_sub", "dateadd", "datediff", "datetime",
            "datetimev2", "datev2", "datetimev1", "datev1", "day", "days_add", "days_sub",
            "decimal", "decimalv2", "decimalv3", "decommission", "default", "deferred", "delete",
            "demand", "desc", "describe", "diagnose", "disk", "distinct", "distinctpc",
            "distinctpcsa", "distributed", "distribution", "div", "do",
            "doris_internal_table_id", "double", "drop", "dropp", "dual", "duplicate", "dynamic",
            "else", "enable", "encryptkey", "encryptkeys", "end", "ends", "engine", "engines",
            "enter", "errors", "events", "every", "except", "exclude", "execute", "exists",
            "expired", "explain", "export", "extended", "external", "extract",
            "failed_login_attempts", "false", "fast", "feature", "fields", "file", "filter",
            "first", "float", "follower", "following", "for", "foreign", "force", "format",
            "free", "from", "frontend", "frontends", "full", "function", "functions", "generic",
            "global", "grant", "grants", "graph", "group", "grouping", "groups", "hash",
            "having", "hdfs", "help", "histogram", "hll", "hll_union", "hostname", "hour", "hub",
            "identified", "if", "ignore", "immediate", "in", "incremental", "index", "indexes",
            "infile", "inner", "insert", "install", "int", "integer", "intermediate",
            "intersect", "interval", "into", "inverted", "ipv4", "ipv6", "is",
            "is_not_null_pred", "is_null_pred", "isnull", "isolation", "job", "jobs", "join",
            "json", "jsonb", "key", "keys", "kill", "label", "largeint", "last", "lateral",
            "ldap", "ldap_admin_password", "left", "less", "level", "like", "limit", "lines",
            "link", "list", "load", "local", "localtime", "localtimestamp", "location", "lock",
            "logical", "low_priority", "manual", "map", "match", "match_all", "match_any",
            "match_phrase", "match_phrase_edge", "match_phrase_prefix", "match_regexp",
            "materialized", "max", "maxvalue", "memo", "merge", "migrate", "migrations", "min",
            "minus", "minute", "modify", "month", "mtmv", "name", "names", "natural", "negative",
            "never", "next", "ngram_bf", "no", "non_nullable", "not", "null", "nulls",
            "observer", "of", "offset", "on", "only", "open", "optimized", "or", "order",
            "outer", "outfile", "over", "overwrite", "parameter", "parsed", "partition",
            "partitions", "password", "password_expire", "password_history",
            "password_lock_time", "password_reuse", "path", "pause", "percent", "period",
            "permissive", "physical", "plan", "process", "plugin", "plugins", "policy",
            "preceding", "prepare", "primary", "proc", "procedure", "processlist", "profile",
            "properties", "property", "quantile_state", "quantile_union", "query", "quota",
            "random", "range", "read", "real", "rebalance", "recover", "recycle", "refresh",
            "references", "regexp", "release", "rename", "repair", "repeatable", "replace",
            "replace_if_not_null", "replica", "repositories", "repository", "resource",
            "resources", "restore", "restrictive", "resume", "returns", "revoke", "rewritten",
            "right", "rlike", "role", "roles", "rollback", "rollup", "routine", "row", "rows",
            "s3", "sample", "schedule", "scheduler", "schema", "schemas", "second", "select",
            "semi", "sequence", "serializable", "session", "set", "sets", "shape", "show",
            "signed", "skew", "smallint", "snapshot", "soname", "split", "sql_block_rule",
            "start", "starts", "stats", "status", "stop", "storage", "stream", "streaming",
            "string", "struct", "subdate", "sum", "superuser", "switch", "sync", "system",
            "table", "tables", "tablesample", "tablet", "tablets", "task", "tasks", "temporary",
            "terminated", "text", "than", "then", "time", "timestamp", "timestampadd",
            "timestampdiff", "tinyint", "to", "transaction", "trash", "tree", "triggers", "trim",
            "true", "truncate", "type", "type_cast", "types", "unbounded", "uncommitted",
            "uninstall", "union", "unique", "unlock", "unsigned", "update", "use", "user",
            "using", "value", "values", "varchar", "variables", "variant", "vault", "verbose",
            "version", "view", "warnings", "week", "when", "where", "whitelist", "with", "work",
            "workload", "write", "xor", "year",
        )
    }
}
