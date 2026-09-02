package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.metadata.DORIS_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionCatalog
import dev.brikk.house.sql.parser.DorisTokenizerTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
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

    // brikk-native: generated tables plus the Doris type keywords sqlglot lacks (see
    // TOKENIZER_CONFIG).
    override val tokenizerConfig: TokenizerConfig get() = TOKENIZER_CONFIG

    // sqlglot: Doris.TIME_MAPPING (inherited from MySQL)
    override val timeMapping: Map<String, String> get() = MysqlDialect.TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        DorisParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        DorisGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        /**
         * brikk-native (docs/brikk-extensions.md #19, NOT sqlglot parity): Doris DDL type
         * keywords missing from sqlglot's Doris tokenizer, which is tuned for transpiling
         * queries *into* Doris and never sees `SHOW CREATE TABLE` output. Layered over the
         * GENERATED [DorisTokenizerTables.CONFIG] so the oracle-parity token corpus test
         * keeps using the generated tables unchanged.
         *
         * - `LARGEINT` -> INT128: StarRocks' mapping, never propagated to Doris upstream.
         * - `IPV4` / `IPV6`: existing TokenType/DType members (ClickHouse tokenizer has them).
         * - `DECIMALV2` / `DECIMALV3` -> DECIMAL: Doris renders the current DECIMAL
         *   implementation as `DECIMALV3(p, s)` in 1.2-era `SHOW CREATE TABLE` output.
         * - `BITMAP` / `HLL` / `QUANTILE_STATE`: brikk-native TokenType + DType members
         *   (Doris aggregate-storage types; no sqlglot counterpart).
         */
        val TOKENIZER_CONFIG: TokenizerConfig = DorisTokenizerTables.CONFIG.withKeywords(
            mapOf(
                "LARGEINT" to TokenType.INT128,
                "IPV4" to TokenType.IPV4,
                "IPV6" to TokenType.IPV6,
                "DECIMALV2" to TokenType.DECIMAL,
                "DECIMALV3" to TokenType.DECIMAL,
                "BITMAP" to TokenType.BITMAP,
                "HLL" to TokenType.HLL,
                "QUANTILE_STATE" to TokenType.QUANTILE_STATE,
            )
        )

        // sqlglot: Doris.DATE_FORMAT / DATEINT_FORMAT / TIME_FORMAT
        const val DATE_FORMAT: String = "'yyyy-MM-dd'"
        const val DATEINT_FORMAT: String = "'yyyyMMdd'"
        const val TIME_FORMAT: String = "'yyyy-MM-dd HH:mm:ss'"
    }
}
