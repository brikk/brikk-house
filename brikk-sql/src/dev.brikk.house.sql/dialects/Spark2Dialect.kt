package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Spark2TokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's Spark2 dialect umbrella (reference/sqlglot/sqlglot/dialects/spark2.py
 * class Spark2(Hive)). Tokenizer tables are generated (Spark2TokenizerTables); the parser
 * and generator subclasses live in Spark2Parser.kt / Spark2Generator.kt.
 *
 * Spark2 is the middle of the Spark chain: Hive -> Spark2 -> Spark.
 */
// sqlglot: dialects.spark2.Spark2
open class Spark2Dialect : HiveDialect() {

    override val name: String get() = "spark2"

    // sqlglot: Spark2.EXPRESSION_METADATA (sqlglot/typing/spark2.py)
    override val expressionMetadata get() = GeneratedTypingMetadata.SPARK2

    override val tokenizerConfig: TokenizerConfig get() = Spark2TokenizerTables.CONFIG

    override fun parser(errorLevel: ErrorLevel?): Parser =
        Spark2Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        Spark2Generator(pretty = pretty, tokenizerConfig = tokenizerConfig)

    companion object {
        // sqlglot: Spark2.INITCAP_DEFAULT_DELIMITER_CHARS = " "
        const val INITCAP_DEFAULT_DELIMITER_CHARS: String = " "
    }
}
