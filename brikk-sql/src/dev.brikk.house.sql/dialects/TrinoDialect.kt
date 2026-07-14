package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.metadata.TRINO_FUNCTION_CATALOG
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.TrinoTokenizerTables

/**
 * Port of sqlglot's Trino dialect umbrella (reference/sqlglot/sqlglot/dialects/trino.py
 * class Trino(Presto)). Tokenizer tables are generated (TrinoTokenizerTables); the
 * parser and generator subclasses live in TrinoParser.kt / TrinoGenerator.kt.
 */
// sqlglot: dialects.trino.Trino
class TrinoDialect : PrestoDialect() {

    override val name: String get() = "trino"

    // Generated from Trino 481's SHOW FUNCTIONS registry (tools/generate_trino_functions.py
    // over vendor/data/trino-functions-481.tsv). Trino-only: Presto's surface differs.
    override val functionCatalog: FunctionCatalog get() = TRINO_FUNCTION_CATALOG

    override val tokenizerConfig: TokenizerConfig get() = TrinoTokenizerTables.CONFIG

    override fun parser(errorLevel: ErrorLevel?): Parser =
        TrinoParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        TrinoGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)
}
