package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.generator.GeneratorTables
import dev.brikk.house.sql.parser.TrinoTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.reflect.KClass

/**
 * Port of sqlglot's TrinoGenerator (reference/sqlglot/sqlglot/generators/trino.py).
 * TRANSFORMS entries live in [TRANSFORMS], passed through PrestoGenerator's dispatch
 * overlay; flag overrides are open-val overrides; multi-line methods below.
 *
 * NOT PORTED (same infrastructure gaps as PrestoGenerator): the exp.Select preprocess
 * pipeline and the SUPPORTED_JSON_PATH_PARTS restriction. Mismatches are ledgered.
 */
// sqlglot: generators.trino.TrinoGenerator
open class TrinoGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = TrinoTokenizerTables.CONFIG,
) : PrestoGenerator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = TRANSFORMS,
) {

    // sqlglot: TrinoGenerator.EXCEPT_INTERSECT_SUPPORT_ALL_CLAUSE = True
    override val exceptIntersectSupportAllClause: Boolean get() = true

    // sqlglot: Trino.CONCAT_WS_COALESCE = True
    override val dialectConcatWsCoalesce: Boolean get() = true

    // sqlglot: Trino.LOG_BASE_FIRST = True
    override val dialectLogBaseFirst: Boolean? get() = true

    // sqlglot: TrinoGenerator.PROPERTIES_LOCATION
    override val propertiesLocation: Map<KClass<out Expression>, GeneratorTables.PropLocation>
        get() = TRINO_PROPERTIES_LOCATION

    // sqlglot: Generator.log_sql (LOG_BASE_FIRST True restores the base behavior)
    override fun logSql(expression: Log): String =
        func("LOG", expression.thisArg, expression.args["expression"])

    // sqlglot: dialect.trim_sql (Trino TRANSFORMS[exp.Trim])
    open fun trinoTrimSql(expression: Trim): String {
        val removeChars = sql(expression, "expression")

        // Use TRIM/LTRIM/RTRIM syntax if the expression isn't dialect-specific
        if (removeChars.isEmpty()) return trimSql(expression)

        val target = sql(expression, "this")
        var trimType = sql(expression, "position")
        var collation = sql(expression, "collation")

        trimType = if (trimType.isNotEmpty()) "$trimType " else ""
        val remove = "$removeChars "
        val fromPart = "FROM "
        collation = if (collation.isNotEmpty()) " COLLATE $collation" else ""

        return "TRIM($trimType$remove$fromPart$target$collation)"
    }

    // sqlglot: dialect.groupconcat_sql(func_name="LISTAGG", sep=",", within_group=True,
    // on_overflow=True) — Trino TRANSFORMS[exp.GroupConcat]
    override fun groupconcatSql(expression: GroupConcat): String {
        var this_ = expression.thisArg as? Expression
        val separator = sql(expression.args["separator"] ?: Literal.string(","))

        var onOverflowSql = sql(expression, "on_overflow")
        onOverflowSql = if (onOverflowSql.isNotEmpty()) " ON OVERFLOW $onOverflowSql" else ""

        var limit: Expression? = null
        if (this_ is Limit && this_.thisArg != null) {
            limit = this_
            val inner = this_.thisArg as Expression
            this_.set("this", null)
            this_ = inner
        }

        val order = this_?.find(Order::class) as? Order
        if (order != null && order.thisArg != null) {
            val inner = order.thisArg as Expression
            order.set("this", null)
            this_ = if (this_ === order) inner else this_
        }

        val sepArg =
            if (separator.isNotEmpty() || onOverflowSql.isNotEmpty()) "$separator$onOverflowSql"
            else null
        val argsSql = formatArgs(this_, sepArg)

        var modifiers = sql(limit)
        var orderPart: String? = null
        if (order != null) {
            orderPart = orderSql(order).drop(1) // order has a leading space
        }

        val fullArgs = "$argsSql$modifiers"
        val listagg = func("LISTAGG", fullArgs)

        if (orderPart != null) {
            return "$listagg WITHIN GROUP ($orderPart)"
        }
        return listagg
    }

    // sqlglot: dialect.Dialect.normalize_identifier for trino (lowercases unquoted)
    private fun normalizeIdentifierName(identifier: Expression?): String? {
        if (identifier == null) return null
        val quoted = (identifier as? Identifier)?.args?.get("quoted") == true
        return if (quoted) identifier.name else identifier.name.lowercase()
    }

    // sqlglot: dialect.merge_without_target_sql (Trino TRANSFORMS[exp.Merge])
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

    // sqlglot: TrinoGenerator.jsonextract_sql
    override fun jsonextractSql(expression: JSONExtract): String {
        if (!isTruthy(expression.args["json_query"])) {
            return super.jsonextractSql(expression)
        }

        val jsonPath = sql(expression, "expression")

        var option = sql(expression, "option")
        option = if (option.isNotEmpty()) " $option" else ""

        var quote = sql(expression, "quote")
        quote = if (quote.isNotEmpty()) " $quote" else ""

        var onCondition = sql(expression, "on_condition")
        onCondition = if (onCondition.isNotEmpty()) " $onCondition" else ""

        return func(
            "JSON_QUERY",
            expression.thisArg,
            jsonPath + option + quote + onCondition,
        )
    }

    companion object {

        // sqlglot: TrinoGenerator.PROPERTIES_LOCATION
        val TRINO_PROPERTIES_LOCATION: Map<KClass<out Expression>, GeneratorTables.PropLocation> =
            PrestoGenerator.PROPERTIES_LOCATION + mapOf(
                LocationProperty::class to GeneratorTables.PropLocation.POST_WITH,
            )

        // sqlglot: TrinoGenerator.TRANSFORMS (dispatch-map overlay over PrestoGenerator's;
        // multi-line entries are methods on TrinoGenerator, one-liners inlined)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.tg(): TrinoGenerator = this as TrinoGenerator

            reg(ArraySum::class) { e ->
                "REDUCE(${sql(e, "this")}, 0, (acc, x) -> acc + x, acc -> acc)"
            }
            reg(ArrayUniqueAgg::class) { e -> "ARRAY_AGG(DISTINCT ${sql(e, "this")})" }
            reg(CurrentVersion::class) { e -> tg().renameFuncSql("VERSION", e) }
            reg(FromISO8601TimestampNanos::class) { e ->
                tg().renameFuncSql("FROM_ISO8601_TIMESTAMP_NANOS", e)
            }
            reg(GroupConcat::class) { e -> tg().groupconcatSql(e as GroupConcat) }
            reg(LocationProperty::class) { e -> propertySql(e as Property) }
            reg(Merge::class) { e -> tg().mergeWithoutTargetSql(e as Merge) }
            reg(TimeStrToTime::class) { e -> tg().timestrtotimeSql(e, includePrecision = true) }
            reg(Trim::class) { e -> tg().trinoTrimSql(e as Trim) }
        }
    }
}
