package dev.brikk.house.sql.compiler.analysis

/**
 * The SQL argument of a `Sql.<dialect>(...)` call as a Kotlin string template, after each
 * `$name` entry has been classified against the enclosing function:
 *
 * | `$x` refers to                                  | becomes                          |
 * |-------------------------------------------------|----------------------------------|
 * | a `Rel` parameter (must be written `$x(...)`)   | `x` - a table-valued slot call   |
 * | any other parameter, a local, a non-const `val` | `:x` - a named bind parameter    |
 * | a `const val`                                   | its literal value, as SQL text   |
 *
 * Anything else inside `${...}` is rejected by the caller ([SqlPiece] has no case for it).
 * Plain `:name` placeholders and `name()` slot calls written as text keep working; the `$`
 * forms exist so the references are real Kotlin references (usage, rename, navigation).
 */
sealed interface SqlPiece {
    /** Literal SQL text. */
    data class Text(val text: String) : SqlPiece

    /** `$rel` -> the slot name; the SQL text must continue with `(`. */
    data class Slot(val name: String) : SqlPiece

    /** `$value` -> `:value`. */
    data class Bind(val name: String) : SqlPiece

    /** `$CONST` -> the constant's value spliced verbatim. */
    data class Const(val text: String) : SqlPiece
}

class SqlTemplate(val pieces: List<SqlPiece>, private val trim: (String) -> String = { it }) {

    /** The SQL as brikk-sql sees it (after any trimIndent/trimMargin). */
    val sql: String = trim(
        buildString {
            for (p in pieces) {
                when (p) {
                    is SqlPiece.Text -> append(p.text)
                    is SqlPiece.Slot -> append(p.name)
                    is SqlPiece.Bind -> append(':').append(p.name)
                    is SqlPiece.Const -> append(p.text)
                }
            }
        },
    )

    /** Names bound through `$name` entries, in order of first appearance. */
    val binds: List<String> = pieces.filterIsInstance<SqlPiece.Bind>().map { it.name }.distinct()

    /** Names used as slots through `$name(` entries. */
    val slots: List<String> = pieces.filterIsInstance<SqlPiece.Slot>().map { it.name }.distinct()

    /**
     * The first [SqlPiece.Slot] that is not immediately followed by `(` (whitespace allowed),
     * i.e. a `Rel` parameter referenced as if it were a value; `null` when all are well-formed.
     */
    fun malformedSlot(): SqlPiece.Slot? {
        for ((i, p) in pieces.withIndex()) {
            if (p !is SqlPiece.Slot) continue
            val next = pieces.getOrNull(i + 1) as? SqlPiece.Text
            if (next == null || !next.text.trimStart().startsWith("(")) return p
        }
        return null
    }

    /** Applies `.trimIndent()` / `.trimMargin()` semantics to the rendered text. */
    fun trimmed(f: (String) -> String): SqlTemplate = SqlTemplate(pieces) { f(trim(it)) }

    companion object {
        fun text(s: String): SqlTemplate = SqlTemplate(listOf(SqlPiece.Text(s)))
    }
}
