package dev.brikk.house.sql.runtime

import dev.brikk.house.sql.ast.Anonymous
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.args
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.parser.TokenType
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.toSourcePreservingExecutable

/**
 * Engine-owned edits only: binding names and bound relation slots. The SQL library remains
 * responsible for parsing, token coordinates and pipe lowering. Never regenerate a native
 * same-dialect stage just to change these names: doing so normalizes unrelated hints,
 * operators and dialect clauses. Ambiguous coordinates fail closed instead of rewriting
 * the wrong occurrence. No regex runs over SQL text.
 */
internal fun SqlFragment.rewriteInputs(
    slots: Map<String, String>,
    bindings: Map<String, String>,
    target: String,
): String {
    val dialectObj = Dialects.forName(dialect)
    val tokens = dialectObj.tokenize(sql)
    val edits = mutableListOf<SqlEdit>()
    val slotsByUpper = slots.mapKeys { it.key.uppercase() }

    for (table in ast.findAll(Table::class)) {
        val call = table.thisArg as? Anonymous ?: continue
        val cte = slotsByUpper[call.name.uppercase()] ?: continue
        if (call.expressionsArg.isNotEmpty() || table.args["db"] != null || table.args["catalog"] != null) {
            throw UnsupportedError("Bound Rel slot '${call.name}' must be an unqualified, zero-argument call")
        }
        val start = (call.meta["start"] as? Number)?.toInt()
            ?: throw UnsupportedError("Cannot locate bound Rel slot '${call.name}' in its native SQL")
        val index = tokens.indexOfFirst { it.start == start && it.text.equals(call.name, ignoreCase = true) }
        val nameToken = tokens.getOrNull(index)
        val open = tokens.getOrNull(index + 1)
        val close = tokens.getOrNull(index + 2)
        if (nameToken == null || open?.tokenType != TokenType.L_PAREN || close?.tokenType != TokenType.R_PAREN) {
            throw UnsupportedError("Cannot locate the empty call of bound Rel slot '${call.name}'")
        }
        // Replace only the name and parentheses, retaining comments/trivia inside the call.
        edits += SqlEdit(nameToken.start, nameToken.end + 1, cte)
        edits += SqlEdit(open.start, open.end + 1, "")
        val alias = if (table.args["alias"] == null) {
            // An implicit slot qualifier must still resolve, including quoted/case-sensitive names.
            " AS ${sql.substring(nameToken.start, nameToken.end + 1)}"
        } else ""
        edits += SqlEdit(close.start, close.end + 1, alias)
    }

    val renamed = bindings.filter { (original, rendered) -> original != rendered }
    if (renamed.isNotEmpty()) {
        // SQL 0.17 owns exact parameter occurrence/name ranges. Do not infer them by
        // scanning colon punctuation (e.g. a struct's {'key': n} is not a bind).
        // Use the requested target: a deliberate translation must not be blocked by a
        // same-dialect preservation proof that the caller did not ask for.
        for (parameter in toSourcePreservingExecutable(target).parameterOccurrences) {
            val replacement = renamed[parameter.name] ?: continue
            val range = parameter.nameRange
                ?: throw UnsupportedError("Cannot locate the identifier of native binding '${parameter.name}'")
            val nameToken = tokens.singleOrNull { it.start == range.start && it.end + 1 == range.end }
                ?: throw UnsupportedError("Native parameter identifier range does not match a token")
            val renderedName = if (nameToken.tokenType == TokenType.IDENTIFIER) {
                dialectObj.generate(Identifier(args("this" to replacement, "quoted" to true)))
            } else replacement
            edits += SqlEdit(range.start, range.end, renderedName)
        }
    }

    return applySqlEdits(sql, edits)
}

/** The Engine CTE wrapper owns terminator removal, not query lowering or translation. */
internal fun embedQuery(sql: String, dialect: String): String {
    val tokens = Dialects.forName(dialect).tokenize(sql)
    val edits = mutableListOf<SqlEdit>()
    // A single fragment's terminator is not legal inside AS (...). Tokenization (not
    // string trimming) distinguishes a terminator from one inside a comment/literal.
    for (token in tokens) if (token.tokenType == TokenType.SEMICOLON) {
        edits += SqlEdit(token.start, token.end + 1, "")
    }
    val output = applySqlEdits(sql, edits)
    // A trailing line comment must not consume the generated CTE closing parenthesis.
    val tail = sql.substring(tokens.lastOrNull()?.let { it.end + 1 } ?: 0)
    return if (tail.isNotBlank() && !output.endsWith('\n')) "$output\n" else output
}

private data class SqlEdit(val start: Int, val end: Int, val replacement: String)

private fun applySqlEdits(sql: String, edits: List<SqlEdit>): String = buildString {
    var cursor = 0
    for (edit in edits.sortedBy { it.start }) {
        if (edit.start < cursor || edit.start < 0 || edit.end > sql.length || edit.end < edit.start) {
            throw UnsupportedError("Overlapping or invalid source edits while rendering native Rel SQL")
        }
        append(sql, cursor, edit.start)
        append(edit.replacement)
        cursor = edit.end
    }
    append(sql, cursor, sql.length)
}
