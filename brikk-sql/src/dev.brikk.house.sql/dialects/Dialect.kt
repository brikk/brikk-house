package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.Token
import dev.brikk.house.sql.parser.Tokenizer
import dev.brikk.house.sql.parser.TokenizerConfig
import dev.brikk.house.sql.parser.formatTimeString

/**
 * Port of sqlglot's Dialect (reference/sqlglot/sqlglot/dialects/dialect.py) — the
 * umbrella object bundling a tokenizer config, a Parser subclass and a Generator
 * subclass, plus the dialect-level flags they share.
 *
 * Only the flags the ported dialects actually read are mirrored here (TIME_MAPPING
 * and its inverse); parser-/generator-level flags live as open vals on the Parser
 * and Generator subclasses themselves, matching how the Kotlin port distributes
 * sqlglot's Dialect class attributes.
 */
open class Dialect {

    /** Registry name ("" is the base sqlglot dialect). */
    open val name: String get() = ""

    // sqlglot: Dialect.tokenizer_class (tables only; the scanner is shared)
    open val tokenizerConfig: TokenizerConfig get() = TokenizerConfig.BASE

    // sqlglot: Dialect.TIME_MAPPING (dialect format specifier -> python strftime)
    open val timeMapping: Map<String, String> get() = emptyMap()

    // sqlglot: Dialect.INVERSE_TIME_MAPPING ({v: k for k, v in TIME_MAPPING.items()})
    val inverseTimeMapping: Map<String, String> by lazy {
        timeMapping.entries.associate { (k, v) -> v to k }
    }

    // sqlglot: Dialect.parser
    open fun parser(errorLevel: ErrorLevel? = null): Parser =
        Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    // sqlglot: Dialect.generator
    open fun generator(pretty: Boolean = false): Generator =
        Generator(pretty = pretty, tokenizerConfig = tokenizerConfig)

    // sqlglot: Dialect.tokenize
    fun tokenize(sql: String): List<Token> = Tokenizer(tokenizerConfig).tokenize(sql)

    // sqlglot: Dialect.parse
    fun parse(sql: String): List<Expression?> = parser().parse(tokenize(sql), sql)

    /** sqlglot: sqlglot.parse_one with this dialect. */
    fun parseOne(sql: String): Expression =
        parse(sql).firstOrNull() ?: throw ParseError("No expression was parsed from '$sql'")

    // sqlglot: Dialect.generate
    fun generate(expression: Expression, pretty: Boolean = false, copy: Boolean = true): String =
        generator(pretty = pretty).generate(expression, copy = copy)

    /**
     * sqlglot: Dialect.format_time — converts a time-format literal in this dialect
     * to its python-strftime equivalent. Non-string expressions pass through.
     */
    fun formatTime(expression: Expression?): Expression? {
        if (expression is Literal && expression.isString) {
            val converted = formatTimeString(expression.thisArg as? String, timeMapping)
            return Literal(args("this" to converted, "is_string" to true))
        }
        return expression
    }
}

/**
 * sqlglot: Dialect registry (Dialect.__getitem__ / get_or_raise). Unknown names
 * raise; use [forNameOrNull] for availability checks.
 */
object Dialects {
    val BASE: Dialect = Dialect()
    val MYSQL: Dialect = MysqlDialect()
    val DORIS: Dialect = DorisDialect()

    fun forNameOrNull(name: String): Dialect? = when (name.lowercase().trim()) {
        "", "sqlglot" -> BASE
        "mysql" -> MYSQL
        "doris" -> DORIS
        else -> null
    }

    fun forName(name: String): Dialect =
        forNameOrNull(name) ?: throw IllegalArgumentException("Unknown dialect: '$name'")
}

/**
 * sqlglot: sqlglot.transpile — parse under [read], generate under [write].
 * Single-statement convenience (Python returns a list over all statements).
 */
fun transpile(sql: String, read: String = "", write: String = "", pretty: Boolean = false): String =
    Dialects.forName(write).generate(Dialects.forName(read).parseOne(sql), pretty = pretty)

/** sqlglot: Expression.sql(dialect=...) convenience. */
fun Expression.sql(dialect: String = "", pretty: Boolean = false): String =
    Dialects.forName(dialect).generate(this, pretty = pretty)
