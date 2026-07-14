package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.UserDefinedFunction
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.BigqueryTokenizerTables
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's BigQuery dialect umbrella (reference/sqlglot/sqlglot/dialects/
 * bigquery.py class BigQuery(Dialect)). Tokenizer tables are generated
 * (BigqueryTokenizerTables); the parser and generator subclasses live in
 * BigqueryParser.kt / BigqueryGenerator.kt.
 *
 * BigQuery is a direct child of the base Dialect and the origin of the pipe (|>)
 * syntax that brikk keeps first-class.
 */
// sqlglot: dialects.bigquery.BigQuery
open class BigqueryDialect : Dialect() {

    override val name: String get() = "bigquery"

    // sqlglot: BigQuery.EXPRESSION_METADATA (sqlglot/typing/bigquery.py)
    override val expressionMetadata get() = GeneratedTypingMetadata.BIGQUERY

    // sqlglot: BigQuery.NORMALIZATION_STRATEGY = NormalizationStrategy.CASE_INSENSITIVE
    override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE

    // sqlglot: BigQuery.COERCES_TO — augments the base lattice (BIGNUMERIC targets etc.)
    override val coercesTo: Map<DType, Set<DType>> get() = BIGQUERY_COERCES_TO

    // sqlglot: BigQuery.DEFAULT_NULL_TYPE = exp.DType.BIGINT
    override val defaultNullType: DType get() = DType.BIGINT

    // sqlglot: BigQuery.PRIORITIZE_NON_LITERAL_TYPES = True
    override val prioritizeNonLiteralTypes: Boolean get() = true

    // sqlglot: BigQuery.QUERY_RESULTS_ARE_STRUCTS = True
    override val queryResultsAreStructs: Boolean get() = true

    // sqlglot: BigQuery.UNNEST_COLUMN_ONLY = True
    override val unnestColumnOnly: Boolean get() = true

    // sqlglot: BigQuery.FORCE_EARLY_ALIAS_REF_EXPANSION = True
    override val forceEarlyAliasRefExpansion: Boolean get() = true

    // sqlglot: BigQuery.EXPAND_ONLY_GROUP_ALIAS_REF = True
    override val expandOnlyGroupAliasRef: Boolean get() = true

    // sqlglot: BigQuery.ANNOTATE_ALL_SCOPES = True
    override val annotateAllScopes: Boolean get() = true

    // sqlglot: BigQuery.PROJECTION_ALIASES_SHADOW_SOURCE_NAMES = True
    override val projectionAliasesShadowSourceNames: Boolean get() = true

    // sqlglot: BigQuery.TABLES_REFERENCEABLE_AS_COLUMNS = True
    override val tablesReferenceableAsColumns: Boolean get() = true

    // sqlglot: BigQuery.SUPPORTS_STRUCT_STAR_EXPANSION = True
    override val supportsStructStarExpansion: Boolean get() = true

    // sqlglot: BigQuery.EXCLUDES_PSEUDOCOLUMNS_FROM_STAR = True
    override val excludesPseudocolumnsFromStar: Boolean get() = true

    // sqlglot: BigQuery.PSEUDOCOLUMNS
    override val pseudocolumns: Set<String> get() = PSEUDOCOLUMNS

    // sqlglot: BigQuery.TIME_MAPPING (dialect format specifier -> python strftime)
    override val timeMapping: Map<String, String> get() = TIME_MAPPING

    override val tokenizerConfig: TokenizerConfig get() = BigqueryTokenizerTables.CONFIG

    override fun parser(errorLevel: ErrorLevel?): Parser =
        BigqueryParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        BigqueryGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    /**
     * sqlglot: BigQuery.normalize_identifier — CTEs are case-insensitive, but UDF and
     * table names are case-sensitive by default. Uses a heuristic to detect tables
     * based on whether they are qualified.
     */
    fun <E : Expression> normalizeIdentifierBq(expression: E): E {
        if (
            expression is Identifier &&
            normalizationStrategy == NormalizationStrategy.CASE_INSENSITIVE
        ) {
            var parent = expression.parent
            while (parent is Dot) {
                parent = parent.parent
            }

            val meta = expression.metaOrNull
            val caseSensitive =
                parent is UserDefinedFunction ||
                    (
                        parent is Table &&
                            parent.args["db"] != null &&
                            (
                                meta?.get("quoted_table") == true ||
                                    meta?.get("maybe_column") != true
                                )
                        ) ||
                    meta?.get("is_table") == true
            if (!caseSensitive) {
                expression.set("this", (expression.thisArg as String).lowercase())
            }
            @Suppress("UNCHECKED_CAST")
            return expression as E
        }

        return normalizeIdentifier(expression)
    }

    companion object {
        // sqlglot: BigQuery.PSEUDOCOLUMNS
        val PSEUDOCOLUMNS: Set<String> = setOf(
            "_PARTITIONTIME",
            "_PARTITIONDATE",
            "_TABLE_SUFFIX",
            "_FILE_NAME",
            "_DBT_MAX_PARTITION",
        )

        // sqlglot: BigQuery.INITCAP_DEFAULT_DELIMITER_CHARS
        const val INITCAP_DEFAULT_DELIMITER_CHARS: String =
            " \t\n\r\u000c\u000b[](){}/|<>!?@\"^#\$&~_,.:;*%+-"

        // sqlglot: BigQuery.TIME_MAPPING (dialect format specifier -> python strftime)
        val TIME_MAPPING: Map<String, String> = mapOf(
            "%x" to "%m/%d/%y",
            "%D" to "%m/%d/%y",
            "%E6S" to "%S.%f",
            "%e" to "%-d",
            "%F" to "%Y-%m-%d",
            "%T" to "%H:%M:%S",
            "%c" to "%a %b %e %H:%M:%S %Y",
        )

        // sqlglot: BigQuery.FORMAT_MAPPING
        val FORMAT_MAPPING: Map<String, String> = mapOf(
            "dd" to "%d", "DD" to "%d",
            "mm" to "%m", "MM" to "%m",
            "mon" to "%b", "MON" to "%b",
            "month" to "%B", "MONTH" to "%B",
            "yyyy" to "%Y", "YYYY" to "%Y",
            "yy" to "%y", "YY" to "%y",
            "HH" to "%I", "HH12" to "%I",
            "hh24" to "%H", "HH24" to "%H",
            "mi" to "%M", "MI" to "%M",
            "ss" to "%S", "SS" to "%S",
            "SSSSS" to "%f",
            "tzh" to "%z", "TZH" to "%z",
        )

        // sqlglot: BigQuery.COERCES_TO — deepcopy(TypeAnnotator.COERCES_TO) with the
        // BIGDECIMAL-target additions (see typing/bigquery.py PERCENTILE comment).
        val BIGQUERY_COERCES_TO: Map<DType, Set<DType>> = buildMap {
            for ((k, v) in GeneratedTypingMetadata.COERCES_TO) put(k, v)
            // exp.DType.BIGDECIMAL: {exp.DType.DOUBLE}
            put(DType.BIGDECIMAL, (this[DType.BIGDECIMAL] ?: emptySet()) + setOf(DType.DOUBLE))
            // COERCES_TO[DECIMAL] |= {BIGDECIMAL}
            put(DType.DECIMAL, (this[DType.DECIMAL] ?: emptySet()) + setOf(DType.BIGDECIMAL))
            // COERCES_TO[BIGINT] |= {BIGDECIMAL}
            put(DType.BIGINT, (this[DType.BIGINT] ?: emptySet()) + setOf(DType.BIGDECIMAL))
            // COERCES_TO[VARCHAR] |= {DATE, DATETIME, TIME, TIMESTAMP, TIMESTAMPTZ}
            put(
                DType.VARCHAR,
                (this[DType.VARCHAR] ?: emptySet()) + setOf(
                    DType.DATE, DType.DATETIME, DType.TIME, DType.TIMESTAMP, DType.TIMESTAMPTZ,
                ),
            )
        }
    }
}
