package dev.brikk.house.sql.parser

/**
 * Port of the parser-facing pieces of reference/sqlglot/sqlglot/errors.py.
 */

// sqlglot: errors.ANSI_UNDERLINE / ANSI_RESET / ERROR_MESSAGE_CONTEXT_DEFAULT
internal const val ANSI_UNDERLINE = "\u001B[4m"
internal const val ANSI_RESET = "\u001B[0m"
internal const val ERROR_MESSAGE_CONTEXT_DEFAULT = 100

// sqlglot: errors.ErrorLevel
enum class ErrorLevel {
    /** Ignore all errors. */
    IGNORE,

    /** Log all errors. */
    WARN,

    /** Collect all errors and raise a single exception. */
    RAISE,

    /** Immediately raise an exception on the first error found. */
    IMMEDIATE,
}

/**
 * sqlglot: errors.ParseError.errors items (Python uses plain dicts; a typed class is the
 * Kotlin analog). All fields nullable, mirroring the dict values.
 */
class ParseErrorInfo(
    val description: String?,
    val line: Int?,
    val col: Int?,
    val startContext: String?,
    val highlight: String?,
    val endContext: String?,
    val intoExpression: String? = null,
)

// sqlglot: errors.ParseError
class ParseError(
    message: String,
    val errors: List<ParseErrorInfo> = emptyList(),
) : RuntimeException(message) {

    companion object {
        // sqlglot: ParseError.new
        fun new(
            message: String,
            description: String? = null,
            line: Int? = null,
            col: Int? = null,
            startContext: String? = null,
            highlight: String? = null,
            endContext: String? = null,
            intoExpression: String? = null,
        ): ParseError = ParseError(
            message,
            listOf(
                ParseErrorInfo(
                    description = description,
                    line = line,
                    col = col,
                    startContext = startContext,
                    highlight = highlight,
                    endContext = endContext,
                    intoExpression = intoExpression,
                )
            ),
        )
    }
}

/** Result quadruple of [highlightSql] (Python returns a 4-tuple). */
class HighlightedSql(
    val formattedSql: String,
    val startContext: String,
    val highlight: String,
    val endContext: String,
)

/**
 * Highlight a SQL string using ANSI codes at the given positions.
 *
 * sqlglot: errors.highlight_sql — positions are inclusive 0-based (start, end) ranges.
 */
fun highlightSql(
    sql: String,
    positions: List<Pair<Int, Int>>,
    contextLength: Int = ERROR_MESSAGE_CONTEXT_DEFAULT,
): HighlightedSql {
    require(positions.isNotEmpty()) { "positions must contain at least one (start, end) tuple" }

    var startContext = ""
    var endContext = ""
    var firstHighlightStart = 0
    val formattedParts = mutableListOf<String>()
    var previousPartEnd = 0
    val sortedPositions = positions.sortedBy { it.first }

    if (sortedPositions[0].first > 0) {
        firstHighlightStart = sortedPositions[0].first
        startContext = sql.substring(maxOf(0, firstHighlightStart - contextLength), firstHighlightStart)
        formattedParts.add(startContext)
        previousPartEnd = firstHighlightStart
    }

    for ((start, end) in sortedPositions) {
        val highlightStart = maxOf(start, previousPartEnd)
        val highlightEnd = end + 1
        if (highlightStart >= highlightEnd) continue // Skip invalid or overlapping highlights
        if (highlightStart > previousPartEnd) {
            formattedParts.add(sql.substring(previousPartEnd, highlightStart))
        }
        formattedParts.add("$ANSI_UNDERLINE${sql.substring(highlightStart, highlightEnd)}$ANSI_RESET")
        previousPartEnd = highlightEnd
    }

    if (previousPartEnd < sql.length) {
        endContext = sql.substring(previousPartEnd, minOf(sql.length, previousPartEnd + contextLength))
        formattedParts.add(endContext)
    }

    val formattedSql = formattedParts.joinToString("")
    val highlight = sql.substring(firstHighlightStart, previousPartEnd)

    return HighlightedSql(formattedSql, startContext, highlight, endContext)
}

// sqlglot: errors.concat_messages
fun concatMessages(errors: List<Throwable>, maximum: Int): String {
    val msg = errors.take(maximum).map { it.message ?: "" }.toMutableList()
    val remaining = errors.size - maximum
    if (remaining > 0) msg.add("... and $remaining more")
    return msg.joinToString("\n\n")
}

// sqlglot: errors.merge_errors
fun mergeErrors(errors: List<ParseError>): List<ParseErrorInfo> =
    errors.flatMap { it.errors }
