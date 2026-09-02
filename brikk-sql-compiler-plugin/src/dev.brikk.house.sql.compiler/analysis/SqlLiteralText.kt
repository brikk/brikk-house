package dev.brikk.house.sql.compiler.analysis

/**
 * Reads the `Sql.<dialect>(<template>)` call out of a function declaration's *source text*.
 * Used only when FIR cannot give us the body (IDE lazy bodies); mirrors what
 * `SqlTemplateFir.read` accepts on FIR: a raw or escaped string, `$name` / `${name}` entries
 * (classified by the caller into slot / bind / const), optionally followed by
 * `.trimIndent()` / `.trimMargin()`. Any other `${...}` content makes the template unreadable.
 */
object SqlLiteralText {

    private val SQL_CALL = Regex("""\bSql\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    private val IDENT = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /** `(dialect, template)` or `null` when the text has no such call or the literal is not readable. */
    fun parse(text: CharSequence, classify: (String) -> SqlPiece): Pair<String, SqlTemplate>? {
        val match = SQL_CALL.find(text) ?: return null
        val dialect = match.groupValues[1]
        var i = skipWs(text, match.range.last + 1)
        val (pieces, afterLiteral) = when {
            text.startsWith("\"\"\"", i) -> rawString(text, i, classify) ?: return null
            i < text.length && text[i] == '"' -> escapedString(text, i, classify) ?: return null
            else -> return null
        }
        var template = SqlTemplate(pieces)
        i = skipWs(text, afterLiteral)
        while (i < text.length && text[i] == '.') {
            val rest = text.subSequence(i, text.length)
            template = when {
                rest.startsWith(".trimIndent()") -> { i += ".trimIndent()".length; template.trimmed { it.trimIndent() } }
                rest.startsWith(".trimMargin()") -> { i += ".trimMargin()".length; template.trimmed { it.trimMargin() } }
                else -> return null
            }
            i = skipWs(text, i)
        }
        if (i >= text.length || text[i] != ')') return null
        return dialect to template
    }

    private fun skipWs(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Accumulates literal text and `$` entries into pieces. */
    private class Pieces(private val classify: (String) -> SqlPiece) {
        val out = ArrayList<SqlPiece>()
        private val buf = StringBuilder()
        fun text(c: Char) { buf.append(c) }
        fun text(s: CharSequence) { buf.append(s) }
        fun entry(name: String) { flush(); out += classify(name) }
        fun finish(): List<SqlPiece> { flush(); return out }
        private fun flush() { if (buf.isNotEmpty()) { out += SqlPiece.Text(buf.toString()); buf.setLength(0) } }
    }

    /**
     * Handles a `$` at [i]: `$name` or `${name}` becomes an entry and the index after it is
     * returned; a literal `$` (followed by anything else) is appended as text; `${expr}` with
     * anything but a plain identifier returns `null` (not readable).
     */
    private fun dollar(text: CharSequence, i: Int, p: Pieces): Int? {
        val next = text.getOrNull(i + 1)
        if (next == '{') {
            val close = text.indexOf('}', i + 2)
            if (close < 0) return null
            val inner = text.subSequence(i + 2, close).toString().trim()
            if (!IDENT.matches(inner)) return null
            p.entry(inner)
            return close + 1
        }
        if (next != null && (next == '_' || next.isLetter())) {
            val m = IDENT.find(text, i + 1)!!
            p.entry(m.value)
            return m.range.last + 1
        }
        p.text('$')
        return i + 1
    }

    /** Kotlin raw string: opened by `"""`, closed by `"""`; extra quotes right after belong to the content. */
    private fun rawString(text: CharSequence, start: Int, classify: (String) -> SqlPiece): Pair<List<SqlPiece>, Int>? {
        val contentStart = start + 3
        var close = text.indexOf("\"\"\"", contentStart)
        if (close < 0) return null
        while (close + 3 < text.length && text[close + 3] == '"') close++
        val p = Pieces(classify)
        var i = contentStart
        while (i < close) {
            if (text[i] == '$') i = dollar(text, i, p) ?: return null else { p.text(text[i]); i++ }
        }
        return p.finish() to close + 3
    }

    /** Kotlin escaped string with the standard escapes. */
    private fun escapedString(text: CharSequence, start: Int, classify: (String) -> SqlPiece): Pair<List<SqlPiece>, Int>? {
        val p = Pieces(classify)
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return p.finish() to i + 1
                '\n' -> return null
                '$' -> i = dollar(text, i, p) ?: return null
                '\\' -> {
                    val e = text.getOrNull(i + 1) ?: return null
                    when (e) {
                        't' -> p.text('\t'); 'b' -> p.text('\b'); 'n' -> p.text('\n'); 'r' -> p.text('\r')
                        '\'' -> p.text('\''); '"' -> p.text('"'); '\\' -> p.text('\\'); '$' -> p.text('$')
                        'u' -> {
                            val hex = text.subSequence(i + 2, minOf(i + 6, text.length)).toString()
                            if (hex.length != 4) return null
                            p.text(hex.toInt(16).toChar()); i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> { p.text(c); i++ }
            }
        }
        return null
    }
}
