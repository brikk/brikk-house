package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.MysqlTokenizerTables
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's MySQL dialect umbrella (reference/sqlglot/sqlglot/dialects/mysql.py
 * class MySQL). Tokenizer tables were generated earlier (MysqlTokenizerTables); the
 * parser and generator subclasses live in MysqlParser.kt / MysqlGenerator.kt.
 */
// sqlglot: dialects.mysql.MySQL
class MysqlDialect : Dialect() {

    override val name: String get() = "mysql"

    // sqlglot: MySQL.EXPRESSION_METADATA (sqlglot/typing/mysql.py)
    override val expressionMetadata get() = dev.brikk.house.sql.ast.GeneratedTypingMetadata.MYSQL

    // sqlglot: MySQL.NORMALIZATION_STRATEGY
    override val normalizationStrategy get() = NormalizationStrategy.CASE_SENSITIVE

    override val tokenizerConfig: TokenizerConfig get() = MysqlTokenizerTables.CONFIG

    // sqlglot: MySQL.TIME_MAPPING
    override val timeMapping: Map<String, String> get() = TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        MysqlParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        MysqlGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        // sqlglot: MySQL.TIME_MAPPING
        val TIME_MAPPING: Map<String, String> = mapOf(
            "%M" to "%B",
            "%c" to "%-m",
            "%e" to "%-d",
            "%h" to "%I",
            "%i" to "%M",
            "%s" to "%S",
            "%u" to "%W",
            "%k" to "%-H",
            "%l" to "%-I",
            "%T" to "%H:%M:%S",
            "%W" to "%A",
        )

        // sqlglot: MySQL.INVERSE_TIME_MAPPING
        val INVERSE_TIME_MAPPING: Map<String, String> =
            TIME_MAPPING.entries.associate { (k, v) -> v to k }
    }
}
