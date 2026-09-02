package dev.brikk.house.sql.compiler.analysis

/**
 * Reads the `Sql.<dialect>(<constant string>)` call out of a function declaration's *source
 * text*. Used only when FIR cannot give us the body (IDE lazy bodies); mirrors what
 * `constSqlStringOrNull` accepts on FIR: a raw or escaped string literal without interpolation,
 * optionally followed by `.trimIndent()` / `.trimMargin()`.
 */
object SqlLiteralText {

    private val SQL_CALL = Regex("""\bSql\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val RAW_INTERPOLATION = Regex("""\$(\{|[A-Za-z_])""")

    /** `(dialect, sqlText)` or `null` when the text has no such call or the literal is not constant. */
    fun parse(text: CharSequence): Pair<String, String>? {
        val match = SQL_CALL.find(text) ?: return null
        val dialect = match.groupValues[1]
        var i = skipWs(text, match.range.last + 1)
        val (literal, afterLiteral) = when {
            text.startsWith("\"\"\"", i) -> rawString(text, i) ?: return null
            i < text.length && text[i] == '"' -> escapedString(text, i) ?: return null
            else -> return null
        }
        var sql = literal
        i = skipWs(text, afterLiteral)
        while (i < text.length && text[i] == '.') {
            val rest = text.subSequence(i, text.length)
            sql = when {
                rest.startsWith(".trimIndent()") -> { i += ".trimIndent()".length; sql.trimIndent() }
                rest.startsWith(".trimMargin()") -> { i += ".trimMargin()".length; sql.trimMargin() }
                else -> return null
            }
            i = skipWs(text, i)
        }
        if (i >= text.length || text[i] != ')') return null
        return dialect to sql
    }

    private fun skipWs(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Kotlin raw string: opened by `"""`, closed by `"""`; extra quotes right after belong to the content. */
    private fun rawString(text: CharSequence, start: Int): Pair<String, Int>? {
        val contentStart = start + 3
        var close = text.indexOf("\"\"\"", contentStart)
        if (close < 0) return null
        while (close + 3 < text.length && text[close + 3] == '"') close++
        val content = text.subSequence(contentStart, close).toString()
        if (RAW_INTERPOLATION.containsMatchIn(content)) return null
        return content to close + 3
    }

    /** Kotlin escaped string with the standard escapes; `$` interpolation makes it non-constant. */
    private fun escapedString(text: CharSequence, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return sb.toString() to i + 1
                '\n' -> return null
                '$' -> {
                    val next = text.getOrNull(i + 1)
                    if (next == '{' || next == '_' || (next != null && next.isLetter())) return null
                    sb.append(c); i++
                }
                '\\' -> {
                    val e = text.getOrNull(i + 1) ?: return null
                    when (e) {
                        't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r')
                        '\'' -> sb.append('\''); '"' -> sb.append('"'); '\\' -> sb.append('\\'); '$' -> sb.append('$')
                        'u' -> {
                            val hex = text.subSequence(i + 2, minOf(i + 6, text.length)).toString()
                            if (hex.length != 4) return null
                            sb.append(hex.toInt(16).toChar()); i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }
}
