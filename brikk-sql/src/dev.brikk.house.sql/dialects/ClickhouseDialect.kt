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
 */
// sqlglot: dialects.clickhouse.ClickHouse
open class ClickhouseDialect : Dialect() {

    override val name: String get() = "clickhouse"

    // sqlglot: ClickHouse.FORCE_EARLY_ALIAS_REF_EXPANSION
    override val forceEarlyAliasRefExpansion: Boolean get() = true

    // sqlglot: ClickHouse.generate_values_aliases — CH VALUES may carry an embedded
    // structure string ('person String, place String') naming the columns; otherwise
    // default column aliases are "c1", "c2", ...
    override fun generateValuesAliases(
        expression: dev.brikk.house.sql.ast.Expression,
    ): List<dev.brikk.house.sql.ast.Identifier> {
        val values = (expression.expressionsArg.firstOrNull()
            as? dev.brikk.house.sql.ast.Expression)?.expressionsArg ?: emptyList()

        val first = values.getOrNull(0) as? dev.brikk.house.sql.ast.Expression
        val structure =
            if (values.size > 1 && first?.isString == true &&
                values[1] is dev.brikk.house.sql.ast.Tuple
            ) {
                first
            } else {
                null
            }

        return if (structure != null) {
            structure.name.split(",").map { coldef ->
                dev.brikk.house.sql.ast.toIdentifier(coldef.trim().split(" ")[0])!!
            }
        } else {
            val width = (values.getOrNull(0) as? dev.brikk.house.sql.ast.Expression)
                ?.expressionsArg?.size ?: 0
            (1..width).map { dev.brikk.house.sql.ast.toIdentifier("c$it")!! }
        }
    }

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
