package dev.brikk.house.sql.ast

import kotlin.String
import kotlin.collections.List

/**
 * Data model for sqlglot's EXPRESSION_METADATA entries (sqlglot/typing/__init__.py and
 * the per-dialect sqlglot/typing/<dialect>.py tables).
 *
 * Python metadata values are either {"returns": DType} or {"annotator": lambda}. The
 * annotator lambdas form a small closed set of helper-call shapes; the codegen
 * (tools/gen_typing_metadata.py) classifies each lambda's source into one of the
 * [AnnotatorRef] variants below and FAILS LOUDLY on anything it can't classify, so new
 * upstream helpers never silently no-op. The Kotlin TypeAnnotator
 * (optimizer/AnnotateTypes.kt) maps each variant to its hand-ported implementation.
 */
sealed class TypingSpec {
    /** {"returns": exp.DType.X} */
    data class Returns(val dtype: DType) : TypingSpec()

    /**
     * {"returns": exp.DataType(...)} — a nested/parametrized return type such as
     * hive's RegexpSplit -> ARRAY<TEXT> or StrToMap -> MAP<TEXT, TEXT>. The codegen
     * (tools/gen_typing_metadata.py) reconstructs the full DataType node.
     */
    data class ReturnsDataType(val dtype: DataType) : TypingSpec()

    /** {"annotator": lambda self, e: ...} */
    data class Annotate(val ref: AnnotatorRef) : TypingSpec()
}

sealed class AnnotatorRef {
    /** self._annotate_binary(e) */
    object BinaryAnn : AnnotatorRef()

    /** self._annotate_unary(e) */
    object UnaryAnn : AnnotatorRef()

    /** self._annotate_by_args(e, *keys, promote=..., array=...) */
    data class ByArgs(
        val keys: List<String>,
        val promote: kotlin.Boolean = false,
        val array: kotlin.Boolean = false,
    ) : AnnotatorRef()

    /** exp.Case: self._annotate_by_args(e, *[if.args["true"] for if in e.args["ifs"]], "default") */
    object CaseArgs : AnnotatorRef()

    /** self._annotate_by_array_element(e) */
    object ByArrayElement : AnnotatorRef()

    /** exp.Anonymous: self._set_type(e, self.schema.get_udf_type(e)) */
    object UdfType : AnnotatorRef()

    /** self._annotate_timeunit(e) */
    object TimeUnitCoercion : AnnotatorRef()

    /** self._set_type(e, e.args[key]) — Cast/TryCast "to" */
    data class SetTypeFromArg(val key: String) : AnnotatorRef()

    /** self._annotate_map(e) */
    object MapAnn : AnnotatorRef()

    /** self._annotate_bracket(e) */
    object BracketAnn : AnnotatorRef()

    /** self._set_type(e, whenTrue if e.args.get(flag) else whenFalse) — Count/DateDiff/HexString/Timestamp */
    data class FlagType(val flag: String, val whenTrue: DType, val whenFalse: DType) : AnnotatorRef()

    /** exp.DataType: lambda _, e: e (identity; DataType nodes are their own type) */
    object Identity : AnnotatorRef()

    /** self._set_type(e, exp.DataType.from_str("ARRAY<X>")) — GenerateDateArray/GenerateTimestampArray */
    data class ArrayOfType(val element: DType) : AnnotatorRef()

    /** self._annotate_div(e) */
    object DivAnn : AnnotatorRef()

    /** self._annotate_dot(e) */
    object DotAnn : AnnotatorRef()

    /** self._annotate_explode(e) */
    object ExplodeAnn : AnnotatorRef()

    /** self._annotate_extract(e) */
    object ExtractAnn : AnnotatorRef()

    /** self._annotate_literal(e) */
    object LiteralAnn : AnnotatorRef()

    /** self._annotate_struct(e) */
    object StructAnn : AnnotatorRef()

    /** self._annotate_to_map(e) */
    object ToMapAnn : AnnotatorRef()

    /** self._annotate_unnest(e) */
    object UnnestAnn : AnnotatorRef()

    /** self._annotate_within_group(e) */
    object WithinGroupAnn : AnnotatorRef()

    /** self._annotate_subquery(e) */
    object SubqueryAnn : AnnotatorRef()

    /** presto exp.Rand: self._annotate_by_args(e, "this") if e.this else self._set_type(e, DOUBLE) */
    object RandThisOrDouble : AnnotatorRef()

    /**
     * spark2 exp.ApproxQuantile: self._annotate_by_args(e, "this",
     * array=e.args["quantile"].is_type(exp.DType.ARRAY)) — annotate by "this" with the
     * array flag driven by whether the "quantile" arg resolves to an ARRAY type.
     */
    object ApproxQuantileByArgs : AnnotatorRef()

    /**
     * spark2 _annotate_by_similar_args (CONCAT/LPAD/RPAD family, sqlglot/typing/spark2.py):
     * gather the args under [keys]; all-BINARY -> BINARY; else if any arg has a known,
     * non-ARRAY, non-BINARY type -> TEXT; else UNKNOWN.
     */
    data class BySimilarArgs(val keys: List<String>) : AnnotatorRef()

    /**
     * clickhouse exp.MD5Digest: self._set_type(e, exp.DataType.build("FixedString(16)",
     * dialect="clickhouse")) — a parametrized, non-nullable type with a single integer
     * DataTypeParam.
     */
    data class SetSizedType(
        val dtype: DType,
        val size: kotlin.Int,
        val nullable: kotlin.Boolean = false,
    ) : AnnotatorRef()

    /**
     * bigquery _annotate_math_functions (CEIL/FLOOR/AVG/... family, sqlglot/typing/
     * bigquery.py): INT64 input -> FLOAT64, otherwise the first arg's own type.
     */
    object MathFunctionsBq : AnnotatorRef()

    /**
     * bigquery _annotate_by_args_with_coerce (SafeAdd/SafeSubtract/SafeMultiply/
     * PercentileCont): _maybe_coerce(this.type, expression.type).
     */
    object ByArgsWithCoerceBq : AnnotatorRef()

    /**
     * bigquery _annotate_safe_divide: INT64/INT64 -> FLOAT64, else by-args-with-coerce.
     */
    object SafeDivideBq : AnnotatorRef()

    /**
     * bigquery _annotate_concat: by_args over "expressions"; unless BINARY/UNKNOWN,
     * coerce the result to VARCHAR.
     */
    object ConcatBq : AnnotatorRef()

    /**
     * bigquery _annotate_date_func (DATE_ADD/DATE_SUB/TRUNC family): a string-literal first
     * arg takes the function's own temporal type ([literalType]); otherwise by_args("this").
     */
    data class DateFuncBq(val literalType: DType) : AnnotatorRef()

    /**
     * bigquery _annotate_array: ARRAY(SELECT ...) / ARRAY(SELECT AS STRUCT ...) /
     * ARRAY(set-op) projection typing; falls back to by_args("expressions", array=true).
     */
    object ArrayBq : AnnotatorRef()

    /**
     * bigquery _annotate_by_args_approx_top (APPROX_TOP_K/APPROX_TOP_SUM): result is
     * ARRAY<STRUCT<this.type, INT64>>.
     */
    object ApproxTopKBq : AnnotatorRef()
}
