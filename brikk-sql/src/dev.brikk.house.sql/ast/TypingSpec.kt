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
     * clickhouse exp.MD5Digest: self._set_type(e, exp.DataType.build("FixedString(16)",
     * dialect="clickhouse")) — a parametrized, non-nullable type with a single integer
     * DataTypeParam.
     */
    data class SetSizedType(
        val dtype: DType,
        val size: kotlin.Int,
        val nullable: kotlin.Boolean = false,
    ) : AnnotatorRef()
}
