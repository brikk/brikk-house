package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Array as ArrayNode
import dev.brikk.house.sql.ast.Map as MapNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.generator.eliminateDistinctOn
import dev.brikk.house.sql.parser.StarrocksTokenizerTables
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
 * Port of sqlglot's StarRocksGenerator (reference/sqlglot/sqlglot/generators/starrocks.py
 * class StarRocksGenerator(MySQLGenerator)). TRANSFORMS entries live in [TRANSFORMS],
 * passed through MysqlGenerator's dispatch overlay; flag overrides are open-val overrides;
 * multi-line methods below.
 */
// sqlglot: generators.starrocks.StarRocksGenerator
open class StarrocksGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = StarrocksTokenizerTables.CONFIG,
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
    override val dialect: Dialect get() = Dialects.STARROCKS

    // ------------------------------------------------------------------
    // Flags (sqlglot: StarRocksGenerator class attributes)
    // ------------------------------------------------------------------

    // sqlglot: StarRocksGenerator.EXCEPT_INTERSECT_SUPPORT_ALL_CLAUSE = False
    override val exceptIntersectSupportAllClause: Boolean get() = false

    // sqlglot: StarRocksGenerator.JSON_TYPE_REQUIRED_FOR_EXTRACTION = False is realized by
    // starrocksArrowJsonExtractSql (below), which — unlike MysqlGenerator's override — does
    // NOT wrap string literals in CAST(... AS JSON). No base-flag needed.

    // sqlglot: StarRocksGenerator.VARCHAR_REQUIRES_SIZE = False
    override val varcharRequiresSize: Boolean get() = false

    // sqlglot: StarRocksGenerator.PARSE_JSON_NAME = "PARSE_JSON" (MySQL resets to null)
    override val parseJsonName: String? get() = "PARSE_JSON"

    // sqlglot: StarRocksGenerator.WITH_PROPERTIES_PREFIX = "PROPERTIES"
    override val withPropertiesPrefix: String get() = "PROPERTIES"

    // sqlglot: StarRocksGenerator.UPDATE_STATEMENT_SUPPORTS_FROM = True
    override val updateStatementSupportsFrom: Boolean get() = true

    // sqlglot: StarRocksGenerator.INSERT_OVERWRITE = " OVERWRITE"
    override val insertOverwrite: String get() = " OVERWRITE"

    // sqlglot: StarRocksGenerator.IS_BOOL_ALLOWED = False
    override val isBoolAllowed: Boolean get() = false

    // sqlglot: StarRocksGenerator.RENAME_TABLE_WITH_DB = False
    override val renameTableWithDb: Boolean get() = false

    // sqlglot: StarRocks.INDEX_OFFSET = 1 (mirrored by StarrocksParser.indexOffset)
    override val dialectIndexOffset: Int get() = 1

    // sqlglot: StarRocksGenerator.CAST_MAPPING = {}
    override val castMapping: Map<DType, String> get() = emptyMap()

    // sqlglot: StarRocksGenerator.TIMESTAMP_FUNC_TYPES inherited empty (MySQL sets it empty)
    override val timestampFuncTypes: Set<DType> get() = emptySet()

    // sqlglot: StarRocksGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = TYPE_MAPPING

    // sqlglot: StarRocksGenerator.RESERVED_KEYWORDS
    override val reservedKeywords: Set<String> get() = RESERVED_KEYWORDS

    // sqlglot: StarRocksGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = PROPERTIES_LOCATION

    // ------------------------------------------------------------------
    // Transform helpers (sqlglot: module-level functions in generators/starrocks.py)
    // ------------------------------------------------------------------

    // sqlglot: StarRocks does NOT inherit MySQL's locate_properties override (which moves
    // SQL SECURITY before VIEW). StarRocks keeps SqlSecurityProperty at POST_SCHEMA
    // (SQL_SECURITY_VIEW_LOCATION = POST_SCHEMA) so it renders after the column list:
    // CREATE VIEW foo (col) SECURITY NONE AS ... . Bypass the MySQL override to the base.
    override fun locateProperties(properties: Properties): Map<GeneratorTables.PropLocation, List<Expression>> {
        // Base (non-MySQL) location assignment, without MySQL's SQL-SECURITY-before-VIEW move.
        val locations = LinkedHashMap<GeneratorTables.PropLocation, MutableList<Expression>>()
        for (p in properties.expressionsArg) {
            if (p !is Expression) continue
            val loc = propertiesLocation[p::class]
                ?: throw dev.brikk.house.sql.generator.UnsupportedError(
                    "Property location unknown for ${p::class.simpleName}"
                )
            if (loc != GeneratorTables.PropLocation.UNSUPPORTED) {
                locations.getOrPut(loc) { mutableListOf() }.add(p)
            } else {
                unsupported("Unsupported property ${p.key}")
            }
        }
        return locations
    }

    // sqlglot: dialect.arrow_json_extract_sql, with JSON_TYPE_REQUIRED_FOR_EXTRACTION=False.
    // Unlike MysqlGenerator's override (which always wraps string literals in CAST(... AS
    // JSON)), StarRocks does not require the JSON type for extraction, so the CAST wrap is
    // skipped; a Binary/Predicate/Not RHS is parenthesized to match the base helper.
    open fun starrocksArrowJsonExtractSql(expression: Binary): String {
        val rhs = expression.args["expression"] as? Expression
        if (rhs is Binary || rhs is Predicate || rhs is Not) {
            expression.set("expression", Paren(args("this" to rhs)))
        }
        return binary(expression, if (expression is JSONExtract) "->" else "->>")
    }

    // sqlglot: dialect.approx_count_distinct_sql
    open fun approxCountDistinctSql(expression: ApproxDistinct): String =
        func("APPROX_COUNT_DISTINCT", expression.thisArg)

    // sqlglot: dialect.var_map_sql — StarRocks' variadic MAP(k1, v1, k2, v2, ...)
    open fun starrocksVarMapSql(expression: Expression): String {
        val keys = (expression.args["keys"] as? Expression)?.expressionsArg ?: emptyList()
        val values = (expression.args["values"] as? Expression)?.expressionsArg ?: emptyList()

        if (expression is MapNode && (keys.isEmpty() || values.isEmpty())) {
            // sqlglot: bare exp.Map with no keys/values renders as MAP()
            return "MAP()"
        }

        val argsList = mutableListOf<kotlin.Any?>()
        for (i in keys.indices) {
            argsList.add(keys[i])
            argsList.add(values.getOrNull(i))
        }
        return func("MAP", *argsList.toTypedArray())
    }

    // sqlglot: dialect.inline_array_sql
    open fun inlineArraySql(expression: Expression): String =
        "[${expressions(expression, dynamic = true, newLine = true, skipFirst = true, skipLast = true)}]"

    // sqlglot: dialect.weekstart_unit_to_str — WeekStart nodes render to their week-start
    // name; otherwise falls back to plain unit_to_str.
    open fun weekstartUnitToStr(expression: Expression, default: String = "DAY"): Expression? {
        val unit = expression.args["unit"]
        if (unit is WeekStart) return Literal.string(weekstartName(unit))
        return unitToStr(expression, default)
    }

    // sqlglot: Generator.weekstart_name (WEEK(<day>) is BigQuery-only; degrades to WEEK)
    open fun weekstartName(expression: WeekStart): String {
        val this_ = (expression.thisArg as? Expression)?.name?.uppercase() ?: "SUNDAY"
        val dowFromWeekStartDay = WEEK_START_DAY_TO_DOW[this_]
        // StarRocks does not override WEEK_OFFSET (base default 0 => Sunday, dow=7).
        val dowFromWeekOffset = weekOffsetToDow(0)
        if (dowFromWeekStartDay != dowFromWeekOffset) {
            unsupported("WEEK($this_) is not supported; falling back to the default week start day")
        }
        return "WEEK"
    }

    // sqlglot: generators.starrocks.st_distance_sphere
    open fun stDistanceSphereSql(expression: StDistance): String {
        val point1 = expression.thisArg
        val point2 = expression.args["expression"] as? Expression

        val point1X = func("ST_X", point1)
        val point1Y = func("ST_Y", point1)
        val point2X = func("ST_X", point2)
        val point2Y = func("ST_Y", point2)

        return "ST_Distance_Sphere($point1X, $point1Y, $point2X, $point2Y)"
    }

    // sqlglot: generators.starrocks._eliminate_between_in_delete — StarRocks doesn't support
    // BETWEEN in DELETE statements, so convert to explicit comparisons.
    override fun deleteSql(expression: Delete): String {
        val where = expression.args["where"] as? Expression
        if (where != null) {
            for (between in where.findAll(Between::class).toList()) {
                val target = between.thisArg as? Expression
                between.replace(
                    And(
                        args(
                            "this" to GTE(
                                args(
                                    "this" to target?.copy(),
                                    "expression" to between.args["low"],
                                )
                            ),
                            "expression" to LTE(
                                args(
                                    "this" to target?.copy(),
                                    "expression" to between.args["high"],
                                )
                            ),
                        )
                    )
                )
            }
        }
        return super.deleteSql(expression)
    }

    // sqlglot: StarRocksGenerator.create_sql — StarRocks' primary key is defined outside
    // the schema, so move it there.
    override fun createSql(expression: Create): String {
        val schema = expression.thisArg
        if (schema is Schema) {
            val primaryKey = schema.find(PrimaryKey::class)
            if (primaryKey != null) {
                var props = expression.args["properties"] as? Properties
                if (props == null) {
                    props = Properties(args("expressions" to mutableListOf<Expression>()))
                    expression.set("properties", props)
                }

                // Insert after the ENGINE property if present, else at the beginning.
                val engine = props.find(EngineProperty::class)
                val engineIndex = if (engine != null) (engine.index ?: 0) else -1
                @Suppress("UNCHECKED_CAST")
                val exprs = (props.args["expressions"] as? MutableList<Expression>)
                    ?: mutableListOf<Expression>().also { props.set("expressions", it) }
                val pk = primaryKey.pop() as Expression
                exprs.add((engineIndex + 1).coerceIn(0, exprs.size), pk)
            }
        }

        return super.createSql(expression)
    }

    // sqlglot: StarRocksGenerator.partitionedbyproperty_sql
    open fun partitionedbypropertySql(expression: PartitionedByProperty): String {
        val this_ = expression.thisArg
        if (this_ is Schema) {
            // For MVs, StarRocks needs outer parentheses.
            val create = expression.findAncestor(Create::class)
            var sqlStr = expressions(this_, flat = true)
            if ((create != null && create.args["kind"] == "VIEW") ||
                this_.expressionsArg.all { it is Column || it is Identifier }
            ) {
                sqlStr = "($sqlStr)"
            }
            return "PARTITION BY $sqlStr"
        }
        return "PARTITION BY ${sql(this_)}"
    }

    // sqlglot: StarRocksGenerator.clusterproperty_sql
    override fun clusterpropertySql(expression: ClusterProperty): String {
        if (expression.thisArg != null) {
            unsupported("Unsupported CLUSTER BY ${sql(expression, "this")}")
            return ""
        }
        val exprs = expressions(expression, flat = true)
        return "ORDER BY ($exprs)"
    }

    // sqlglot: StarRocksGenerator.refreshtriggerproperty_sql — StarRocks REFRESH clause for
    // materialized views (slightly different syntax from Doris).
    override fun refreshtriggerpropertySql(expression: RefreshTriggerProperty): String {
        var method = sql(expression, "method")
        method = if (method.isNotEmpty()) " $method" else ""
        var kind = sql(expression, "kind")
        kind = if (kind.isNotEmpty()) " $kind" else ""
        var starts = sql(expression, "starts")
        starts = if (starts.isNotEmpty()) " START ($starts)" else ""
        val every = sql(expression, "every")
        val unit = sql(expression, "unit")
        val everyClause = if (every.isNotEmpty() && unit.isNotEmpty()) " EVERY (INTERVAL $every $unit)" else ""

        return "REFRESH$method$kind$starts$everyClause"
    }

    // sqlglot: StarRocksGenerator.rollupproperty_sql / rollupindex_sql are auto-discovered.
    override fun rolluppropertySql(expression: RollupProperty): String =
        "ROLLUP (${expressions(expression, flat = true)})"

    // sqlglot: Generator.rollupindex_sql -> {this}({columns})[ FROM x][ PROPERTIES (...)]
    open fun rollupindexSql(expression: RollupIndex): String {
        val this_ = sql(expression, "this")
        val columns = expressions(expression, flat = true)
        val fromSql = sql(expression, "from_index")
        val fromClause = if (fromSql.isNotEmpty()) " FROM $fromSql" else ""
        val properties = expression.args["properties"] as? Properties
        val propsClause =
            if (properties != null) " ${properties(properties, prefix = "PROPERTIES")}" else ""
        return "$this_($columns)$fromClause$propsClause"
    }

    // sqlglot: Generator.tablefromrows_sql — TABLE(<tvf>) [AS alias][pivots][sample][joins]
    open fun tablefromrowsSql(expression: TableFromRows): String {
        val table = func("TABLE", expression.thisArg)
        var alias = sql(expression, "alias")
        alias = if (alias.isNotEmpty()) " AS $alias" else ""
        val sample = sql(expression, "sample")
        val pivots = expressions(expression, key = "pivots", sep = "", flat = true)
        val joins = indent(expressions(expression, key = "joins", sep = "", flat = true), skipFirst = true)
        return "$table$alias$pivots$sample$joins"
    }

    // sqlglot: Generator.partitionbyrangepropertydynamic_sql
    open fun partitionbyrangepropertydynamicSql(expression: PartitionByRangePropertyDynamic): String {
        val start = sql(expression, "start")
        val end = sql(expression, "end")

        val every = expression.args["every"]
        if (every is Interval) {
            val everyThis = every.thisArg
            if (everyThis is Literal && everyThis.isString) {
                everyThis.replace(Literal.number(every.name))
            }
        }

        return "START ${wrap(start)} END ${wrap(end)} EVERY ${wrap(sql(every))}"
    }

    // sqlglot: StarRocks' Select preprocess = [eliminate_distinct_on,
    // unnest_generate_date_array_using_recursive_cte]. StarRocks natively supports QUALIFY,
    // FULL OUTER JOIN, SEMI/ANTI JOIN, so it drops MySQL's eliminate_qualify /
    // eliminate_semi_and_anti_joins.
    open fun starrocksSelectSql(expression: Expression): String {
        var s = eliminateDistinctOn(expression)
        s = unnestGenerateDateArrayUsingRecursiveCte(s)
        return selectSql(s as Select)
    }

    companion object {

        // sqlglot: dialect.WEEK_START_DAY_TO_DOW
        private val WEEK_START_DAY_TO_DOW: Map<String, Int> = mapOf(
            "MONDAY" to 1, "TUESDAY" to 2, "WEDNESDAY" to 3, "THURSDAY" to 4,
            "FRIDAY" to 5, "SATURDAY" to 6, "SUNDAY" to 7,
        )

        // sqlglot: dialect.week_offset_to_dow (WEEK_OFFSET 0 => Sunday's dow=7)
        private fun weekOffsetToDow(weekOffset: Int): Int = ((weekOffset + 6) % 7) + 1

        // sqlglot: StarRocksGenerator.TYPE_MAPPING
        val TYPE_MAPPING: Map<DType, String> = MysqlGenerator.TYPE_MAPPING + mapOf(
            DType.INT128 to "LARGEINT",
            DType.TEXT to "STRING",
            DType.TIMESTAMP to "DATETIME",
            DType.TIMESTAMPTZ to "DATETIME",
        )

        // sqlglot: StarRocksGenerator.PROPERTIES_LOCATION
        val PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            MysqlGenerator.PROPERTIES_LOCATION + mapOf(
                PrimaryKey::class to GeneratorTables.PropLocation.POST_SCHEMA,
                UniqueKeyProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                RollupProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
                PartitionedByProperty::class to GeneratorTables.PropLocation.POST_SCHEMA,
            )

        // sqlglot: StarRocksGenerator.TRANSFORMS (dispatch-map overlay over MysqlGenerator's;
        // multi-line entries are methods on StarRocksGenerator, one-liners inlined). Notably
        // DateTrunc and Trim are REMOVED from MySQL's transforms (StarRocks uses native forms).
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.sg(): StarrocksGenerator = this as StarrocksGenerator

            reg(ArgMax::class) { e -> sg().renameFuncSql("MAX_BY", e) }
            reg(ArgMin::class) { e -> sg().renameFuncSql("MIN_BY", e) }
            reg(ArrayNode::class) { e -> sg().inlineArraySql(e) }
            reg(ArrayAgg::class) { e -> sg().renameFuncSql("ARRAY_AGG", e) }
            // a <@ b (ArrayContainedBy) is equivalent to ARRAY_CONTAINS_ALL(b, a)
            reg(ArrayContainedBy::class) { e ->
                func("ARRAY_CONTAINS_ALL", (e as Binary).right, e.left)
            }
            reg(ArrayContainsAll::class) { e -> sg().renameFuncSql("ARRAY_CONTAINS_ALL", e) }
            reg(ArrayFilter::class) { e -> sg().renameFuncSql("ARRAY_FILTER", e) }
            reg(ArrayToString::class) { e -> sg().renameFuncSql("ARRAY_JOIN", e) }
            reg(ApproxDistinct::class) { e -> sg().approxCountDistinctSql(e as ApproxDistinct) }
            reg(CurrentVersion::class) { _ -> "CURRENT_VERSION()" }
            reg(DateDiff::class) { e ->
                func("DATE_DIFF", sg().weekstartUnitToStr(e), e.thisArg, e.args["expression"])
            }
            reg(Delete::class) { e -> sg().deleteSql(e as Delete) }
            reg(Flatten::class) { e -> sg().renameFuncSql("ARRAY_FLATTEN", e) }
            reg(JSONExtractScalar::class) { e -> sg().starrocksArrowJsonExtractSql(e as Binary) }
            reg(JSONExtract::class) { e -> sg().starrocksArrowJsonExtractSql(e as Binary) }
            // Both MAP forms generate StarRocks' variadic MAP(k1, v1, k2, v2, ...)
            reg(MapNode::class) { e -> sg().starrocksVarMapSql(e) }
            reg(Property::class) { e ->
                "${propertyName(e as Property, stringKey = true)}=${sql(e, "value")}"
            }
            reg(RegexpLike::class) { e -> sg().renameFuncSql("REGEXP", e) }
            reg(Select::class) { e -> sg().starrocksSelectSql(e) }
            reg(SchemaCommentProperty::class) { e -> nakedProperty(e as Property) }
            reg(SqlSecurityProperty::class) { e -> "SECURITY ${sql(e, "this")}" }
            reg(StDistance::class) { e -> sg().stDistanceSphereSql(e as StDistance) }
            reg(StrToUnix::class) { e -> func("UNIX_TIMESTAMP", e.thisArg, formatTime(e)) }
            reg(TimestampTrunc::class) { e ->
                func("DATE_TRUNC", sg().weekstartUnitToStr(e), e.thisArg)
            }
            reg(TimeStrToDate::class) { e -> sg().renameFuncSql("TO_DATE", e) }
            reg(UnixToStr::class) { e -> func("FROM_UNIXTIME", e.thisArg, formatTime(e)) }
            reg(UnixToTime::class) { e -> sg().renameFuncSql("FROM_UNIXTIME", e) }
            reg(VarMap::class) { e -> sg().starrocksVarMapSql(e) }

            // sqlglot: auto-discovered <name>_sql methods with no base dispatch entry
            reg(ClusterProperty::class) { e -> sg().clusterpropertySql(e as ClusterProperty) }
            reg(PartitionedByProperty::class) { e ->
                sg().partitionedbypropertySql(e as PartitionedByProperty)
            }
            reg(RefreshTriggerProperty::class) { e ->
                sg().refreshtriggerpropertySql(e as RefreshTriggerProperty)
            }
            reg(RollupProperty::class) { e -> sg().rolluppropertySql(e as RollupProperty) }
            reg(RollupIndex::class) { e -> sg().rollupindexSql(e as RollupIndex) }
            reg(TableFromRows::class) { e -> sg().tablefromrowsSql(e as TableFromRows) }
            reg(PartitionByRangePropertyDynamic::class) { e ->
                sg().partitionbyrangepropertydynamicSql(e as PartitionByRangePropertyDynamic)
            }

            // sqlglot: base generator TRANSFORMS entries not ported in the Kotlin base but
            // exercised by StarRocks (exp.SwapTable, exp.Trim falls back to base trim_sql).
            reg(SwapTable::class) { e -> "SWAP WITH ${sql(e, "this")}" }
            // StarRocks removes exp.Trim from MySQL's TRANSFORMS -> base trim_sql
            // (TRIM/LTRIM/RTRIM(str, chars)), not MySQL's TRIM(chars FROM str).
            reg(Trim::class) { e -> sg().trimSql(e as Trim) }
        }

        // sqlglot: StarRocksGenerator.RESERVED_KEYWORDS
        // https://docs.starrocks.io/docs/sql-reference/sql-statements/keywords/#reserved-keywords
        val RESERVED_KEYWORDS: Set<String> = setOf(
            "add", "all", "alter", "analyze", "and", "array", "as", "asc", "between", "bigint",
            "bitmap", "both", "by", "case", "char", "character", "check", "collate", "column",
            "compaction", "convert", "create", "cross", "cube", "current_date", "current_role",
            "current_time", "current_timestamp", "current_user", "database", "databases",
            "decimal", "decimalv2", "decimal32", "decimal64", "decimal128", "default", "deferred",
            "delete", "dense_rank", "desc", "describe", "distinct", "double", "drop", "dual",
            "else", "except", "exists", "explain", "false", "first_value", "float", "for", "force",
            "from", "full", "function", "grant", "group", "grouping", "grouping_id", "groups",
            "having", "hll", "host", "if", "ignore", "immediate", "in", "index", "infile", "inner",
            "insert", "int", "integer", "intersect", "into", "is", "join", "json", "key", "keys",
            "kill", "lag", "largeint", "last_value", "lateral", "lead", "left", "like", "limit",
            "load", "localtime", "localtimestamp", "maxvalue", "minus", "mod", "not", "ntile",
            "null", "on", "or", "order", "outer", "outfile", "over", "partition", "percentile",
            "primary", "procedure", "qualify", "range", "rank", "read", "regexp", "release",
            "rename", "replace", "revoke", "right", "rlike", "row", "row_number", "rows", "schema",
            "schemas", "select", "set", "set_var", "show", "smallint", "system", "table",
            "terminated", "text", "then", "tinyint", "to", "true", "union", "unique", "unsigned",
            "update", "use", "using", "values", "varchar", "when", "where", "with",
        )
    }
}

// sqlglot: transforms.unnest_generate_date_array_using_recursive_cte. Rewrites
// UNNEST(GENERATE_DATE_ARRAY(start, end, INTERVAL n unit)) in FROM/JOIN position into a
// recursive CTE (StarRocks has no GENERATE_DATE_ARRAY / UNNEST-of-series). Narrow BigQuery
// -source pattern; ported faithfully for cross-dialect transpile parity.
internal fun unnestGenerateDateArrayUsingRecursiveCte(expression: Expression): Expression {
    if (expression !is Select) return expression

    var count = 0
    val recursiveCtes = mutableListOf<Expression>()

    for (unnest in expression.findAll(Unnest::class).toList()) {
        val parent = unnest.parent
        val exprs = unnest.expressionsArg
        if ((parent !is From && parent !is Join) || exprs.size != 1) continue
        val generateDateArray = exprs[0] as? GenerateDateArray ?: continue

        val start = generateDateArray.args["start"] as? Expression
        val end = generateDateArray.args["end"] as? Expression
        val step = generateDateArray.args["step"] as? Expression
        if (start == null || end == null || step !is Interval) continue

        val alias = unnest.args["alias"] as? TableAlias
        val columnName: String = (alias?.args?.get("columns") as? List<*>)
            ?.firstOrNull()?.let { (it as? Expression)?.name } ?: "date_value"

        // sqlglot: exp.cast is idempotent — if the arg is already a Cast to DATE, keep it.
        fun castDate(e: Expression): Expression {
            if (e is Cast && (e.args["to"] as? DataType)?.thisArg == DType.DATE) return e
            return Cast(args("this" to e, "to" to DataType(args("this" to DType.DATE))))
        }

        val startCast = castDate(start.copy())
        // sqlglot: exp.func("date_add", column_name, Literal.number(step.name), step.unit)
        // resolves to a DateAdd node -> StarRocks DATE_ADD(col, INTERVAL n unit).
        val dateAdd = DateAdd(
            args(
                "this" to Column(args("this" to Identifier(args("this" to columnName, "quoted" to false)))),
                "expression" to Literal.number(step.name),
                "unit" to step.args["unit"],
            )
        )
        val castDateAdd = castDate(dateAdd)

        val cteName = "_generated_dates" + (if (count > 0) "_$count" else "")

        // base_query: SELECT CAST(start AS DATE) AS columnName
        val baseQuery = Select(
            args(
                "expressions" to listOf(
                    Alias(
                        args(
                            "this" to startCast,
                            "alias" to Identifier(args("this" to columnName, "quoted" to false)),
                        )
                    )
                )
            )
        )
        // recursive_query: SELECT castDateAdd FROM cteName WHERE castDateAdd <= CAST(end AS DATE)
        val recursiveQuery = Select(
            args(
                "expressions" to listOf(castDateAdd),
                "from_" to From(
                    args("this" to Table(args("this" to Identifier(args("this" to cteName, "quoted" to false)))))
                ),
                "where" to Where(
                    args(
                        "this" to LTE(args("this" to castDateAdd.copy(), "expression" to castDate(end.copy())))
                    )
                ),
            )
        )
        val cteQuery = Union(
            args("this" to baseQuery, "expression" to recursiveQuery, "distinct" to false)
        )

        // Replace the UNNEST with (SELECT columnName FROM cteName) AS cteName.
        val generateDatesQuery = Select(
            args(
                "expressions" to listOf(
                    Column(args("this" to Identifier(args("this" to columnName, "quoted" to false))))
                ),
                "from_" to From(
                    args("this" to Table(args("this" to Identifier(args("this" to cteName, "quoted" to false)))))
                ),
            )
        )
        unnest.replace(
            Subquery(
                args(
                    "this" to generateDatesQuery,
                    "alias" to TableAlias(args("this" to Identifier(args("this" to cteName, "quoted" to false)))),
                )
            )
        )

        recursiveCtes.add(
            CTE(
                args(
                    "this" to cteQuery,
                    "alias" to TableAlias(
                        args(
                            "this" to Identifier(args("this" to cteName, "quoted" to false)),
                            "columns" to listOf(Identifier(args("this" to columnName, "quoted" to false))),
                        )
                    ),
                )
            )
        )
        count += 1
    }

    if (recursiveCtes.isNotEmpty()) {
        val withExpression = (expression.args["with_"] as? With) ?: With()
        withExpression.set("recursive", true)
        val existing = (withExpression.args["expressions"] as? List<*>).orEmpty()
        withExpression.set("expressions", recursiveCtes + existing.filterNotNull())
        expression.set("with_", withExpression)
    }

    return expression
}
