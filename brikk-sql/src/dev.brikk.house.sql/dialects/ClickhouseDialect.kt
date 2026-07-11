package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ClickhouseTokenizerTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's ClickHouse dialect umbrella (reference/sqlglot/sqlglot/dialects/clickhouse.py
 * class ClickHouse). Tokenizer tables are generated (ClickhouseTokenizerTables); the parser
 * and generator subclasses live in ClickhouseParser.kt / ClickhouseGenerator.kt.
 *
 * NOT PORTED: FORCE_EARLY_ALIAS_REF_EXPANSION (read only by the optimizer's qualify
 * rule, which has no Kotlin equivalent) and generate_values_aliases (only read by
 * qualify_columns).
 */
// sqlglot: dialects.clickhouse.ClickHouse
open class ClickhouseDialect : Dialect() {

    override val name: String get() = "clickhouse"

    // sqlglot: ClickHouse.NORMALIZATION_STRATEGY = NormalizationStrategy.CASE_SENSITIVE
    override val normalizationStrategy: NormalizationStrategy
        get() = NormalizationStrategy.CASE_SENSITIVE

    // sqlglot: ClickHouse.EXPRESSION_METADATA (sqlglot/typing/clickhouse.py)
    override val expressionMetadata get() = GeneratedTypingMetadata.CLICKHOUSE

    override val tokenizerConfig: TokenizerConfig get() = ClickhouseTokenizerTables.CONFIG

    override fun parser(errorLevel: ErrorLevel?): Parser =
        ClickhouseParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        ClickhouseGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig)
}
