package dev.brikk.house.sql.dialects

// Explicit kotlin imports shield builtins from same-named ast classes.
import dev.brikk.house.sql.ast.*
import dev.brikk.house.sql.ast.Boolean as BooleanNode
import dev.brikk.house.sql.generator.GenMethod
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.TokenizerConfig
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.reflect.KClass

/**
 * Apache DataFusion generator — brikk-native, NO sqlglot oracle.
 *
 * Flag overrides are modeled on polyglot's GeneratorConfig for DataFusion
 * (reference/polyglot/crates/polyglot-sql/src/dialects/datafusion.rs) cross-checked
 * against https://datafusion.apache.org/user-guide/sql/. Provenance style is
 * `// brikk: ...` throughout (no `// sqlglot:` source exists).
 *
 * MOST of polyglot's GeneratorConfig knobs already coincide with brikk's BASE
 * Generator defaults, so the explicit overrides below are only the genuine deltas:
 *  - normalize_functions = Lower  -> constructor default flipped to "lower"
 *    (DataFusion renders function names in lowercase)
 *  - supports_window_exclude = false (BASE default is false -> pinned for docs)
 *  - copy_has_into_keyword = false -> [copyHasIntoKeyword] = false (COPY ... TO)
 *  - supports_like_quantifiers = false -> [supportsLikeQuantifiers] = false
 *  - multi_arg_distinct = false -> [multiArgDistinct] = false
 * BASE already matches for: try_supported (TRY_CAST via trycastSql), star_except
 * ("EXCEPT"), semi_anti_join_with_side (true), interval_allows_plural_form (true),
 * aggregate_filter_supported (true), limit_fetch_style = Limit (BASE limitIsTop=false),
 * supports_between_flags (false). Those are asserted by the fixture/hand gates, not
 * re-declared here.
 *
 * Transpile renames (polyglot transform_expr / transform_aggregate_function) are
 * applied as dispatch overrides in [TRANSFORMS] plus an [anonymousSql] override for
 * names that parse to a generic function node:
 *   IFNULL      -> coalesce   (already: BASE parses IFNULL into exp.Coalesce)
 *   SQUARE(x)   -> power(x, 2)
 *   REGEXP_MATCHES -> regexp_match
 *   DATE_FORMAT / TIME_TO_STR -> to_char
 *   GROUP_CONCAT / LISTAGG    -> string_agg
 *
 * NOT WIRED (deferred to phase 2): typing metadata / EXPRESSION_METADATA (annotate
 * falls back to BASE), a FunctionCatalog, and any engine verifier.
 */
// brikk: no sqlglot oracle — flags per polyglot datafusion.rs + DataFusion SQL docs
open class DatafusionGenerator(
    pretty: Boolean = false,
    identify: kotlin.Any = false,
    comments: Boolean = true,
    // brikk: DataFusion lowercases function names (polyglot NormalizeFunctions::Lower)
    normalizeFunctions: kotlin.Any = "lower",
    tokenizerConfig: TokenizerConfig = TokenizerConfig.BASE,
    overrides: Map<KClass<out Expression>, GenMethod> = emptyMap(),
) : Generator(
    pretty = pretty,
    identify = identify,
    comments = comments,
    normalizeFunctions = normalizeFunctions,
    tokenizerConfig = tokenizerConfig,
    overrides = if (overrides.isEmpty()) TRANSFORMS else TRANSFORMS + overrides,
) {

    // brikk: dialect back-reference for annotate_types-driven paths (falls back to BASE)
    override val dialect: Dialect get() = Dialects.DATAFUSION

    // ------------------------------------------------------------------
    // Flags (brikk: per polyglot datafusion.rs GeneratorConfig; BASE-matching knobs
    // are documented in the class KDoc rather than re-declared)
    // ------------------------------------------------------------------

    // brikk: COPY ... TO (no INTO keyword) — polyglot copy_has_into_keyword=false
    override val copyHasIntoKeyword: Boolean get() = false

    // brikk: DataFusion has no LIKE ANY/ALL quantifiers — polyglot supports_like_quantifiers=false
    override val supportsLikeQuantifiers: Boolean get() = false

    // brikk: no multi-arg DISTINCT — polyglot multi_arg_distinct=false
    override val multiArgDistinct: Boolean get() = false

    // brikk: window frame EXCLUDE unsupported — polyglot supports_window_exclude=false
    override val supportsWindowExclude: Boolean get() = false

    // ------------------------------------------------------------------
    // Renames for generic (Anonymous) function nodes
    // ------------------------------------------------------------------

    // brikk: names that DataFusion spells differently and that BASE parses as generic
    // Anonymous functions (no dedicated AST node). Applied before lowercasing.
    override fun anonymousSql(expression: Anonymous): String {
        val parent = expression.parent
        val isQualified = parent is Dot && expression === parent.expressionArg
        if (!isQualified) {
            when (expression.name.uppercase()) {
                // brikk: SQUARE(x) -> power(x, 2)
                "SQUARE" -> return func(
                    "power",
                    *(expression.expressionsArg + Literal.number("2")).toTypedArray(),
                )
                // brikk: REGEXP_MATCHES -> regexp_match
                "REGEXP_MATCHES" -> return func("regexp_match", *expression.expressionsArg.toTypedArray())
                // brikk: DATE_FORMAT -> to_char (TIME_TO_STR handled via its AST node)
                "DATE_FORMAT" -> return func("to_char", *expression.expressionsArg.toTypedArray())
                // brikk: LISTAGG -> string_agg (GROUP_CONCAT handled via its AST node)
                "LISTAGG" -> return func("string_agg", *expression.expressionsArg.toTypedArray())
            }
        }
        return super.anonymousSql(expression)
    }

    companion object {
        // brikk: dispatch-map overlay (dedicated AST nodes that need renaming). Names
        // that parse to Anonymous are handled in [anonymousSql] instead.
        val TRANSFORMS: Map<KClass<out Expression>, GenMethod> = buildMap {
            fun reg(cls: KClass<out Expression>, method: GenMethod) { put(cls, method) }

            fun Generator.flattenArgs(e: Expression): kotlin.Array<kotlin.Any?> =
                e.args.values.flatMap { v -> if (v is List<*>) v else listOf(v) }.toTypedArray()

            // brikk: GROUP_CONCAT -> string_agg (dedicated exp.GroupConcat node)
            reg(GroupConcat::class) { e -> func("string_agg", *flattenArgs(e)) }
            // brikk: TIME_TO_STR -> to_char (dedicated exp.TimeToStr node)
            reg(TimeToStr::class) { e -> func("to_char", *flattenArgs(e)) }
        }
    }
}
