package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.DorisTokenizerTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's Doris dialect umbrella (reference/sqlglot/sqlglot/dialects/doris.py
 * class Doris(MySQL)). Tokenizer tables were generated earlier (DorisTokenizerTables);
 * the parser and generator subclasses live in DorisParser.kt / DorisGenerator.kt.
 */
// sqlglot: dialects.doris.Doris
class DorisDialect : Dialect() {

    override val name: String get() = "doris"

    // sqlglot: Doris(MySQL) inherits MySQL.EXPRESSION_METADATA
    override val expressionMetadata get() = dev.brikk.house.sql.ast.GeneratedTypingMetadata.MYSQL

    // sqlglot: Doris inherits MySQL.NORMALIZATION_STRATEGY (CASE_SENSITIVE)
    override val normalizationStrategy get() = NormalizationStrategy.CASE_SENSITIVE

    // Generated from Doris's runtime function registry (tools/generate_doris_functions.py).
    override val functionCatalog: FunctionCatalog get() = DORIS_FUNCTION_CATALOG

    override val tokenizerConfig: TokenizerConfig get() = DorisTokenizerTables.CONFIG

    // sqlglot: Doris.TIME_MAPPING (inherited from MySQL)
    override val timeMapping: Map<String, String> get() = MysqlDialect.TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        DorisParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        DorisGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig)

    companion object {
        // sqlglot: Doris.DATE_FORMAT / DATEINT_FORMAT / TIME_FORMAT
        const val DATE_FORMAT: String = "'yyyy-MM-dd'"
        const val DATEINT_FORMAT: String = "'yyyyMMdd'"
        const val TIME_FORMAT: String = "'yyyy-MM-dd HH:mm:ss'"
    }
}
