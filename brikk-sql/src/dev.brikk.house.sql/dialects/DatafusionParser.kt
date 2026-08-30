package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.BitwiseNot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * DataFusion parser — brikk-native, NO sqlglot oracle. A near-empty passthrough over
 * the BASE [Parser].
 *
 * The polyglot fixture corpus and the curated DataFusion sqllogictest parse subset are
 * accepted by the BASE grammar with ONE delta (see below). Concretely, the following
 * are already BASE parser behaviors (verified via the fixture/SLT parse gates, not a
 * sqlglot oracle):
 *  - `::` cast operator and CAST/TRY_CAST
 *  - `arrow_cast(...)` / `arrow_typeof(...)` (parse as anonymous functions)
 *  - QUALIFY, SELECT * EXCEPT (...) and SELECT * EXCLUDE (...)
 *  - LEFT SEMI / LEFT ANTI joins
 *  - aggregate FILTER (WHERE ...)
 *  - the `|>` pipe operator (PIPE_GT -> PipeQuery)
 *  - postgres-style `~*`, `!~`, `!~*` regex operators
 *  - plural interval units, LIMIT/OFFSET in either order, COPY ... TO
 *
 * Delta: sqlparser-rs GenericDialect also accepts the binary `~` regex-match operator
 * (PGRegexMatch), which BASE only knows as unary bitwise-not. Like the Postgres port,
 * [DatafusionDialect.TOKENIZER_CONFIG] remaps `~` to RLIKE (base RANGE_PARSERS then
 * builds exp.RegexpLike) and [unaryParsers] restores prefix `~x` as BitwiseNot.
 *
 * DELIBERATELY NOT ADDED (out of scope for thin phase 1; would need an engine verifier
 * to justify): DataFusion Arrow-native type parsers (Int8/Int16/Utf8/... as first-class
 * DType surface), arrow_cast type-literal validation, DataFusion-specific function
 * signature parsing / a FunctionCatalog (phase 2), and any typing/EXPRESSION_METADATA
 * wiring (annotate falls back to BASE).
 */
// brikk: no sqlglot oracle — BASE parser accepts the datafusion fixture + SLT corpus
open class DatafusionParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = DatafusionDialect.TOKENIZER_CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    // brikk: `~` is remapped from TILDA to RLIKE (binary regex match, like Postgres),
    // so prefix `~x` (bitwise not) must be restored here.
    override val unaryParsers: Map<TokenType, (Parser) -> Expression?>
        get() = UNARY_PARSERS

    companion object {
        private val UNARY_PARSERS: Map<TokenType, (Parser) -> Expression?> =
            BaseParserTables.UNARY_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
                TokenType.RLIKE to { p ->
                    p.expression(BitwiseNot(args("this" to p.parseUnary())))
                },
            )
    }
}
