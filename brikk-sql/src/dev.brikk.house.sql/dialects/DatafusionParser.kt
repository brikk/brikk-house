package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.BitwiseNot
import dev.brikk.house.sql.ast.Cast
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.GenerateSeries
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.parser.BaseParserTables
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.fromArgList

/**
 * DataFusion parser — brikk-native, NO sqlglot oracle. It uses the BASE [Parser]
 * grammar and records DataFusion surface spelling needed for readable round trips.
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
 * DataFusion-specific handling also covers one-argument generate_series(stop), source
 * function/type aliases, `::` casts, SELECT ALL, comparison spelling, and explicit null
 * ordering. Arrow-native type modeling and typing metadata remain separate future work.
 */
// brikk: no sqlglot oracle — BASE parser accepts the datafusion fixture + SLT corpus
open class DatafusionParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = DatafusionDialect.TOKENIZER_CONFIG,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig) {

    override val nullOrdering: String get() = "nulls_are_last"

    override val preserveOperatorSpelling: Boolean get() = true

    override val preserveSelectAll: Boolean get() = true

    override val preserveExplicitNullOrdering: Boolean get() = true

    override val functions: Map<String, (List<Expression?>) -> Expression>
        get() = FUNCTIONS

    override val columnOperators: Map<TokenType, ((Parser, Expression?, Expression?) -> Expression?)?>
        get() = COLUMN_OPERATORS

    override fun parseFunction(
        functions: Map<String, (List<Expression?>) -> Expression>?,
        anonymous: Boolean,
        optionalParens: Boolean,
        anyToken: Boolean,
    ): Expression? {
        val sourceName = currToken.text
        val result = super.parseFunction(functions, anonymous, optionalParens, anyToken)
        val function = result?.walk()?.firstOrNull { it is dev.brikk.house.sql.ast.Func }
        if (function != null && sourceName.uppercase() in PRESERVED_FUNCTION_NAMES) {
            function.meta["datafusion_function_name"] = sourceName
        }
        return result
    }

    override fun parseTypes(
        checkFunc: Boolean,
        schema: Boolean,
        allowIdentifiers: Boolean,
        withCollation: Boolean,
    ): Expression? {
        val sourceName = currToken.text
        val result = super.parseTypes(checkFunc, schema, allowIdentifiers, withCollation)
        if (result is DataType && sourceName.uppercase() in PRESERVED_TYPE_NAMES) {
            result.meta["datafusion_type_name"] = sourceName
        }
        return result
    }

    // brikk: `~` is remapped from TILDA to RLIKE (binary regex match, like Postgres),
    // so prefix `~x` (bitwise not) must be restored here.
    override val unaryParsers: Map<TokenType, (Parser) -> Expression?>
        get() = UNARY_PARSERS

    companion object {
        private val PRESERVED_FUNCTION_NAMES = setOf(
            "ARRAY_HAS", "BOOL_AND", "BOOL_OR", "CHAR_LENGTH", "CURRENT_DATE",
            "DATE_TRUNC", "DENSE_RANK", "FIRST_VALUE", "LAG", "LAST_VALUE", "LEAD",
            "LENGTH", "LOG2", "LOG10", "NTH_VALUE", "NTILE", "RANDOM", "RANK",
            "ROW_NUMBER", "STRPOS", "SUBSTR",
        )

        private val PRESERVED_TYPE_NAMES = setOf("BYTEA", "DECIMAL", "INT", "INTEGER", "NUMERIC", "VARCHAR")

        private val FUNCTIONS = BaseParserTables.FUNCTIONS +
            ("GENERATE_SERIES" to { values: List<Expression?> ->
                val args = if (values.size == 1) listOf(Literal.number("0")) + values else values
                fromArgList(listOf("start", "end", "step", "is_end_exclusive"), false) {
                    GenerateSeries(it)
                }(args).also {
                    if (values.size == 1) it.meta["datafusion_omitted_series_start"] = true
                }
            })

        private val COLUMN_OPERATORS = BaseParserTables.COLUMN_OPERATORS +
            (TokenType.DCOLON to { p: Parser, this_: Expression?, to: Expression? ->
                p.buildCast(strict = p.strictCast, this_ = this_, to = to).also {
                    if (it is Cast) it.meta["datafusion_cast_style"] = "dcolon"
                }
            })

        private val UNARY_PARSERS: Map<TokenType, (Parser) -> Expression?> =
            BaseParserTables.UNARY_PARSERS + mapOf<TokenType, (Parser) -> Expression?>(
                TokenType.RLIKE to { p ->
                    p.expression(BitwiseNot(args("this" to p.parseUnary())))
                },
            )
    }
}
