package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.GeneratedTypingMetadata
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.SparkTokenizerTables
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Port of sqlglot's Spark dialect umbrella (reference/sqlglot/sqlglot/dialects/spark.py
 * class Spark(Spark2)). Tokenizer tables are generated (SparkTokenizerTables); the parser
 * and generator subclasses live in SparkParser.kt / SparkGenerator.kt.
 *
 * Spark is the leaf of the Spark chain: Hive -> Spark2 -> Spark (Spark 3+ / Databricks).
 */
// sqlglot: dialects.spark.Spark
open class SparkDialect : Spark2Dialect() {

    override val name: String get() = "spark"

    // sqlglot: Spark.EXPRESSION_METADATA (sqlglot/typing/spark.py)
    override val expressionMetadata get() = GeneratedTypingMetadata.SPARK

    // sqlglot: Spark.SUPPORTS_NULL_TYPE = True (Spark >=3 supports VOID)
    override val supportsNullType: Boolean get() = true

    override val tokenizerConfig: TokenizerConfig get() = SparkTokenizerTables.CONFIG

    // sqlglot: Spark.TIME_MAPPING — Spark 3+ parses MM/dd/HH/hh/mm/ss strictly,
    // unlike Spark 2 (SimpleDateFormat). Re-promote the strict tokens Spark2 stripped.
    override val timeMapping: Map<String, String> get() = TIME_MAPPING

    override fun parser(errorLevel: ErrorLevel?): Parser =
        SparkParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean, sourceDialect: String?): Generator =
        SparkGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig, sourceDialect = sourceDialect)

    companion object {
        // sqlglot: Spark.TIME_MAPPING = {**Spark2.TIME_MAPPING, MM/dd/HH/hh/mm/ss -> strict}
        val TIME_MAPPING: Map<String, String> = Spark2Dialect.TIME_MAPPING + mapOf(
            "MM" to "%mstrict",
            "dd" to "%dstrict",
            "HH" to "%Hstrict",
            "hh" to "%Istrict",
            "mm" to "%Mstrict",
            "ss" to "%Sstrict",
        )
    }
}
