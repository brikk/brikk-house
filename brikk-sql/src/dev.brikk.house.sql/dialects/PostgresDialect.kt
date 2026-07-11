package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.PostgresTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's Postgres dialect umbrella (reference/sqlglot/sqlglot/dialects/postgres.py
 * class Postgres). Tokenizer tables are generated (PostgresTokenizerTables); the parser and
 * generator subclasses live in PostgresParser.kt / PostgresGenerator.kt.
 *
 * NOT PORTED: TABLES_REFERENCEABLE_AS_COLUMNS (only read by the optimizer's
 * qualify_columns, which has no Kotlin equivalent) and DEFAULT_FUNCTIONS_COLUMN_NAMES
 * (also optimizer-only).
 */
// sqlglot: dialects.postgres.Postgres
open class PostgresDialect : Dialect() {

    override val name: String get() = "postgres"

    // sqlglot: Postgres.EXPRESSION_METADATA (sqlglot/typing/postgres.py)
    override val expressionMetadata get() = dev.brikk.house.sql.ast.GeneratedTypingMetadata.POSTGRES

    override val tokenizerConfig: TokenizerConfig get() = PostgresTokenizerTables.CONFIG

    // sqlglot: Postgres.TIME_MAPPING
    override val timeMapping: Map<String, String> get() = TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        PostgresParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        PostgresGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig)

    companion object {
        // sqlglot: Postgres.TIME_MAPPING
        val TIME_MAPPING: Map<String, String> = mapOf(
            "d" to "%u", // 1-based day of week
            "D" to "%u", // 1-based day of week
            "dd" to "%d", // day of month
            "DD" to "%d", // day of month
            "ddd" to "%j", // zero padded day of year
            "DDD" to "%j", // zero padded day of year
            "FMDD" to "%-d", // - is no leading zero for Python; same for FM in postgres
            "FMDDD" to "%-j", // day of year
            "FMHH12" to "%-I", // 9
            "FMHH24" to "%-H", // 9
            "FMMI" to "%-M", // Minute
            "FMMM" to "%-m", // 1
            "FMSS" to "%-S", // Second
            "HH12" to "%I", // 09
            "HH24" to "%H", // 09
            "mi" to "%M", // zero padded minute
            "MI" to "%M", // zero padded minute
            "mm" to "%m", // 01
            "MM" to "%m", // 01
            "OF" to "%z", // utc offset
            "ss" to "%S", // zero padded second
            "SS" to "%S", // zero padded second
            "TMDay" to "%A", // TM is locale dependent
            "TMDy" to "%a",
            "TMMon" to "%b", // Sep
            "TMMonth" to "%B", // September
            "day" to "%Aenlower", // tuesday
            "dy" to "%aenlower", // tue
            "TZ" to "%Z", // uppercase timezone name
            "US" to "%f", // zero padded microsecond
            "ww" to "%U", // 1-based week of year
            "WW" to "%U", // 1-based week of year
            "yy" to "%y", // 15
            "YY" to "%y", // 15
            "yyy" to "%Ythree", // 015
            "YYY" to "%Ythree", // 015
            "yyyy" to "%Y", // 2015
            "YYYY" to "%Y", // 2015
        )

        // sqlglot: Postgres.INVERSE_TIME_MAPPING ({v: k for k, v} keeps the last key)
        val INVERSE_TIME_MAPPING: Map<String, String> =
            TIME_MAPPING.entries.associate { (k, v) -> v to k }
    }
}
