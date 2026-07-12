package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
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
) : MysqlGenerator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = TRANSFORMS,
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
            reg(ApproxDistinct::class) { e -> dg().approxCountDistinctSql(e as ApproxDistinct) }
            reg(ArgMax::class) { e -> dg().renameFuncSql("MAX_BY", e) }
            reg(ArgMin::class) { e -> dg().renameFuncSql("MIN_BY", e) }
            reg(ArrayAgg::class) { e -> dg().renameFuncSql("COLLECT_LIST", e) }
            reg(ArrayToString::class) { e -> dg().renameFuncSql("ARRAY_JOIN", e) }
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
            reg(JSONExtractScalar::class) { e ->
                func("JSON_EXTRACT", e.thisArg, e.args["expression"])
            }
            reg(Lag::class) { e -> dg().lagLeadSql(e) }
            reg(Lead::class) { e -> dg().lagLeadSql(e) }
            reg(MapNode::class) { e -> dg().renameFuncSql("ARRAY_MAP", e) }
            reg(Property::class) { e ->
                "${propertyName(e as Property, stringKey = true)}=${sql(e, "value")}"
            }
            reg(RegexpLike::class) { e -> dg().renameFuncSql("REGEXP", e) }
            reg(RegexpSplit::class) { e -> dg().renameFuncSql("SPLIT_BY_STRING", e) }
            reg(SchemaCommentProperty::class) { e -> nakedProperty(e as Property) }
            reg(Split::class) { e -> dg().renameFuncSql("SPLIT_BY_STRING", e) }
            reg(StringToArray::class) { e -> dg().renameFuncSql("SPLIT_BY_STRING", e) }
            reg(StrToUnix::class) { e -> func("UNIX_TIMESTAMP", e.thisArg, formatTime(e)) }
            reg(TimeStrToDate::class) { e -> dg().renameFuncSql("TO_DATE", e) }
            reg(TsOrDsAdd::class) { e -> func("DATE_ADD", e.thisArg, e.args["expression"]) }
            reg(TsOrDsToDate::class) { e -> func("TO_DATE", e.thisArg) }
            reg(TimeToUnix::class) { e -> dg().renameFuncSql("UNIX_TIMESTAMP", e) }
            reg(TimestampTrunc::class) { e -> func("DATE_TRUNC", e.thisArg, unitToStr(e)) }
            reg(UnixToStr::class) { e ->
                func("FROM_UNIXTIME", e.thisArg, dg().dorisTimeFormat(e))
            }
            reg(UnixToTime::class) { e -> dg().renameFuncSql("FROM_UNIXTIME", e) }

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
