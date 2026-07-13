package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.SparkTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.reflect.KClass

// sqlglot: dialect.unit_to_var
private fun sparkUnitToVar(expression: Expression, default: String = "DAY"): Expression? {
    val unit = expression.args["unit"] as? Expression ?: return if (default.isNotEmpty()) Var(args("this" to default)) else null
    if (unit is Var) return unit
    return Var(args("this" to unit.name))
}

/**
 * Port of sqlglot's SparkGenerator (reference/sqlglot/sqlglot/generators/spark.py class
 * SparkGenerator(Spark2Generator)). TRANSFORMS entries live in [TRANSFORMS], passed
 * through Spark2Generator's dispatch overlay; flag overrides are open-val overrides.
 *
 * We model Spark 3 (dialect version < 4): GroupConcat rewrites to ARRAY_JOIN(COLLECT_LIST).
 *
 * NOT PORTED (no Kotlin equivalents of the underlying transforms/helpers yet, ledgered):
 * exp.Create preprocess (remove_unique_constraints / ctas_with_tmp_tables_to_create_tmp_view
 * / move_partitioned_by_to_schema_columns), the interval-op rewrites for Datetime/Time/
 * Timestamp Add/Sub (date_delta_to_binary_interval_op), TimestampDiff/DatetimeDiff
 * (timestampdiff_sql), ArrayAppend/ArrayPrepend (array_append_sql), ReadParquet, IfBlock.
 */
// sqlglot: generators.spark.SparkGenerator
open class SparkGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    tokenizerConfig: TokenizerConfig = SparkTokenizerTables.CONFIG,
) : Spark2Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    tokenizerConfig = tokenizerConfig,
    overrides = TRANSFORMS,
) {

    // sqlglot: dialect back-reference for annotate_types-driven paths
    override val dialect: Dialect get() = Dialects.SPARK

    // sqlglot: SparkGenerator.SUPPORTS_TO_NUMBER = True
    override val supportsToNumber: Boolean get() = true

    // sqlglot: SparkGenerator.PAD_FILL_PATTERN_IS_REQUIRED = False
    override val padFillPatternIsRequired: Boolean get() = false

    // sqlglot: SparkGenerator.SUPPORTS_MEDIAN = True
    override val supportsMedian: Boolean get() = true

    // sqlglot: SparkGenerator.SET_ASSIGNMENT_REQUIRES_VARIABLE_KEYWORD = True
    override val setAssignmentRequiresVariableKeyword: Boolean get() = true

    // sqlglot: SparkGenerator.TYPE_MAPPING
    override val typeMapping: Map<DType, String> get() = SPARK_TYPE_MAPPING

    // sqlglot: SparkGenerator.ignorenulls_sql — restores the base Generator behavior
    // (bypasses HiveGenerator's IGNORE_NULLS_FUNCS suppression)
    override fun ignorenullsSql(expression: IgnoreNulls): String =
        embedIgnoreNulls(expression, "IGNORE NULLS")

    // sqlglot: SparkGenerator.bracket_sql
    override fun bracketSql(expression: Bracket): String {
        if (expression.args["safe"] == true) {
            val key = bracketOffsetExpressions(expression, indexOffset = 1).firstOrNull()
            return func("TRY_ELEMENT_AT", expression.thisArg, key)
        }
        return super.bracketSql(expression)
    }

    // sqlglot: SparkGenerator.computedcolumnconstraint_sql
    override fun computedcolumnconstraintSql(expression: ComputedColumnConstraint): String =
        "GENERATED ALWAYS AS (${sql(expression, "this")})"

    // sqlglot: SparkGenerator.anyvalue_sql
    override fun anyvalueSql(expression: AnyValue): String = functionFallbackSql(expression)

    // sqlglot: SparkGenerator.datediff_sql
    open fun datediffSpark(expression: DateDiff): String {
        val end = sql(expression, "this")
        val start = sql(expression, "expression")
        if (expression.args["unit"] != null) {
            return func("DATEDIFF", sparkUnitToVar(expression), start, end)
        }
        return func("DATEDIFF", end, start)
    }

    // sqlglot: SparkGenerator.placeholder_sql
    override fun placeholderSql(expression: Placeholder): String {
        if (expression.args["widget"] != true) return super.placeholderSql(expression)
        return "{${expression.name}}"
    }

    // sqlglot: SparkGenerator TRANSFORMS[exp.GroupConcat] (_groupconcat_sql, version < 4)
    internal fun groupConcatSpark(expression: GroupConcat): String {
        val expr = ArrayToString(
            args(
                "this" to ArrayAgg(args("this" to expression.thisArg)),
                "expression" to (expression.args["separator"] ?: Literal.string("")),
            )
        )
        return sql(expr)
    }

    companion object {
        // sqlglot: SparkGenerator.TYPE_MAPPING
        val SPARK_TYPE_MAPPING: Map<DType, String> = HiveGenerator.TYPE_MAPPING + mapOf(
            DType.MONEY to "DECIMAL",
            DType.SMALLMONEY to "DECIMAL",
            DType.UUID to "STRING",
            DType.TIMESTAMPLTZ to "TIMESTAMP_LTZ",
            DType.TIMESTAMPNTZ to "TIMESTAMP_NTZ",
        )

        // sqlglot: SparkGenerator.TRANSFORMS (dispatch-map overlay over Spark2Generator's)
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }
            fun Generator.sg(): SparkGenerator = this as SparkGenerator

            reg(ArrayConstructCompact::class) { e ->
                func("ARRAY_COMPACT", func("ARRAY", *e.expressionsArg.toTypedArray()))
            }
            reg(ArrayInsert::class) { e ->
                func("ARRAY_INSERT", e.thisArg, e.args["position"], e.args["expression"])
            }
            // sqlglot: SparkGenerator TRANSFORMS[exp.AnyValue] = None -> anyvalue_sql method
            reg(AnyValue::class) { e -> sg().anyvalueSql(e as AnyValue) }
            reg(BitwiseAndAgg::class) { e -> sg().renameFuncSql("BIT_AND", e) }
            reg(BitwiseOrAgg::class) { e -> sg().renameFuncSql("BIT_OR", e) }
            reg(BitwiseXorAgg::class) { e -> sg().renameFuncSql("BIT_XOR", e) }
            reg(BitwiseCount::class) { e -> sg().renameFuncSql("BIT_COUNT", e) }
            reg(CurrentVersion::class) { e -> sg().renameFuncSql("VERSION", e) }
            reg(DateFromUnixDate::class) { e -> sg().renameFuncSql("DATE_FROM_UNIX_DATE", e) }
            reg(GroupConcat::class) { e -> sg().groupConcatSpark(e as GroupConcat) }
            reg(EndsWith::class) { e -> sg().renameFuncSql("ENDSWITH", e) }
            reg(JSONKeys::class) { e -> sg().renameFuncSql("JSON_OBJECT_KEYS", e) }
            reg(SafeAdd::class) { e -> sg().renameFuncSql("TRY_ADD", e) }
            reg(SafeDivide::class) { e -> sg().renameFuncSql("TRY_DIVIDE", e) }
            reg(SafeMultiply::class) { e -> sg().renameFuncSql("TRY_MULTIPLY", e) }
            reg(SafeSubtract::class) { e -> sg().renameFuncSql("TRY_SUBTRACT", e) }
            reg(StartsWith::class) { e -> sg().renameFuncSql("STARTSWITH", e) }
            reg(TimestampFromParts::class) { e -> sg().renameFuncSql("MAKE_TIMESTAMP", e) }
            reg(DateDiff::class) { e -> sg().datediffSpark(e as DateDiff) }
            reg(TryCast::class) { e ->
                if (e.args["safe"] == true) sg().trycastSql(e as TryCast) else sg().castSql(e as Cast)
            }
        }
    }
}
