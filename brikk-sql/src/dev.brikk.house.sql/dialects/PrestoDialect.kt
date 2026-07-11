package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.PrestoTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's Presto dialect umbrella (reference/sqlglot/sqlglot/dialects/presto.py
 * class Presto). Tokenizer tables are generated (PrestoTokenizerTables); the parser and
 * generator subclasses live in PrestoParser.kt / PrestoGenerator.kt.
 */
// sqlglot: dialects.presto.Presto
open class PrestoDialect : Dialect() {

    override val name: String get() = "presto"

    // sqlglot: Presto.NORMALIZATION_STRATEGY
    override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE

    override val tokenizerConfig: TokenizerConfig get() = PrestoTokenizerTables.CONFIG

    // sqlglot: Presto.TIME_MAPPING = MySQL.TIME_MAPPING
    override val timeMapping: Map<String, String> get() = MysqlDialect.TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        PrestoParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        PrestoGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig)
}
