package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.HiveTokenizerTables
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's Hive dialect umbrella (reference/sqlglot/sqlglot/dialects/hive.py
 * class Hive(Dialect)). Tokenizer tables are generated (HiveTokenizerTables); the parser
 * and generator subclasses live in HiveParser.kt / HiveGenerator.kt.
 *
 * Hive is the root of the Spark chain: Spark2(Hive), Spark(Spark2).
 */
// sqlglot: dialects.hive.Hive
open class HiveDialect : Dialect() {

    override val name: String get() = "hive"

    // sqlglot: Hive.EXPRESSION_METADATA (sqlglot/typing/hive.py)
    override val expressionMetadata get() = GeneratedTypingMetadata.HIVE

    // sqlglot: Hive.NORMALIZATION_STRATEGY = CASE_INSENSITIVE
    // https://spark.apache.org/docs/latest/sql-ref-identifier.html#description
    override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE

    // sqlglot: Hive.COERCES_TO — non-ANSI mode: NUMERIC/TEMPORAL/INTERVAL targets also
    // coerce from TEXT (default for Hive, Spark2, Spark). Built from the base lattice.
    override val coercesTo: Map<DType, Set<DType>> get() = HIVE_COERCES_TO

    override val tokenizerConfig: TokenizerConfig get() = HiveTokenizerTables.CONFIG

    // sqlglot: Hive.TIME_MAPPING (dialect format specifier -> python strftime)
    override val timeMapping: Map<String, String> get() = TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        HiveParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        HiveGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        // sqlglot: Hive.DATE_FORMAT / DATEINT_FORMAT / TIME_FORMAT
        const val DATE_FORMAT: String = "'yyyy-MM-dd'"
        const val DATEINT_FORMAT: String = "'yyyyMMdd'"
        const val TIME_FORMAT: String = "'yyyy-MM-dd HH:mm:ss'"

        // sqlglot: Hive.INITCAP_DEFAULT_DELIMITER_CHARS
        const val INITCAP_DEFAULT_DELIMITER_CHARS: String = " \t\n\r\u000c\u000b\u001c\u001d\u001e\u001f"

        // sqlglot: Hive.TIME_MAPPING
        val TIME_MAPPING: Map<String, String> = mapOf(
            "y" to "%Y", "Y" to "%Y", "YYYY" to "%Y", "yyyy" to "%Y",
            "YY" to "%y", "yy" to "%y",
            "MMMM" to "%B", "MMM" to "%b", "MM" to "%m", "M" to "%-m",
            "dd" to "%d", "d" to "%-d",
            "HH" to "%H", "H" to "%-H",
            "hh" to "%I", "h" to "%-I",
            "mm" to "%M", "m" to "%-M",
            "ss" to "%S", "s" to "%-S",
            "SSSSSS" to "%f",
            "a" to "%p",
            "DD" to "%j", "D" to "%-j",
            "E" to "%a", "EE" to "%a", "EEE" to "%a", "EEEE" to "%A",
            "z" to "%Z", "Z" to "%z",
        )

        // sqlglot: Hive.COERCES_TO = defaultdict(set, deepcopy(TypeAnnotator.COERCES_TO))
        // augmented so NUMERIC/TEMPORAL/INTERVAL targets also coerce from TEXT_TYPES.
        val HIVE_COERCES_TO: Map<DType, Set<DType>> = buildMap {
            for ((k, v) in GeneratedTypingMetadata.COERCES_TO) put(k, v)
            val textTargets = DataType.NUMERIC_TYPES + DataType.TEMPORAL_TYPES + setOf(DType.INTERVAL)
            for (target in textTargets) {
                put(target, (this[target] ?: emptySet()) + DataType.TEXT_TYPES)
            }
        }
    }
}
