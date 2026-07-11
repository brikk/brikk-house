package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.DuckdbTokenizerTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's DuckDB dialect umbrella (reference/sqlglot/sqlglot/dialects/duckdb.py
 * class DuckDB). Tokenizer tables are generated (DuckdbTokenizerTables); the parser and
 * generator subclasses live in DuckdbParser.kt / DuckdbGenerator.kt.
 */
// sqlglot: dialects.duckdb.DuckDB
open class DuckdbDialect : Dialect() {

    override val name: String get() = "duckdb"

    // sqlglot: DuckDB.EXPRESSION_METADATA (sqlglot/typing/duckdb.py)
    override val expressionMetadata get() = dev.brikk.house.sql.ast.GeneratedTypingMetadata.DUCKDB

    // sqlglot: DuckDB.NORMALIZATION_STRATEGY
    override val normalizationStrategy get() = NormalizationStrategy.CASE_INSENSITIVE

    override val tokenizerConfig: TokenizerConfig get() = DuckdbTokenizerTables.CONFIG

    // sqlglot: DuckDB.TIME_MAPPING is the base (empty) mapping; only
    // INVERSE_TIME_MAPPING carries dialect-specific entries (see DuckdbGenerator).

    override fun parser(errorLevel: ErrorLevel?): Parser =
        DuckdbParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        DuckdbGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig)
}
