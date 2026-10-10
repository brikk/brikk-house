package dev.brikk.house.sql.compiler.analysis

/**
 * Maps SQL UTF-16 offsets back into a supported Kotlin literal/template. Literal escapes
 * map to their complete authored escape, interpolations to their complete entry. Trimming
 * only removes characters: if the transformation cannot be proved as a subsequence, or
 * FIR folded an entry so its origin cannot be recovered, return null rather than guess.
 */
internal object SqlLiteralRanges {
    fun map(argument: String, template: SqlTemplate, start: Int, end: Int): IntRange? {
        if (start < 0 || end <= start || end > template.sql.length) return null
        var i = argument.indexOfFirst { !it.isWhitespace() }
        if (i < 0 || argument[i] != '"') return null
        val raw = argument.startsWith("\"\"\"", i)
        i += if (raw) 3 else 1
        val value = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        val entries = template.pieces.filterNot { it is SqlPiece.Text }.iterator()
        fun append(text: String, range: IntRange) {
            value.append(text)
            repeat(text.length) { ranges += range }
        }
        var closed = false
        while (i < argument.length) {
            if (raw && argument.startsWith("\"\"\"", i)) { closed = true; break }
            if (!raw && argument[i] == '"') { closed = true; break }
            val from = i
            if (!raw && argument[i] == '\\') {
                val escape = argument.getOrNull(i + 1) ?: return null
                val char = when (escape) {
                    't' -> '\t'; 'b' -> '\b'; 'n' -> '\n'; 'r' -> '\r'
                    '\'' -> '\''; '"' -> '"'; '\\' -> '\\'; '$' -> '$'
                    'u' -> argument.substring(i + 2, minOf(i + 6, argument.length)).toIntOrNull(16)?.toChar() ?: return null
                    else -> return null
                }
                i += if (escape == 'u') 6 else 2
                append(char.toString(), from until i)
            } else if (argument[i] == '$' && (argument.getOrNull(i + 1) == '{' ||
                    argument.getOrNull(i + 1)?.let { it == '_' || it.isLetter() } == true)) {
                if (!entries.hasNext()) return null
                i = if (argument.getOrNull(i + 1) == '{') {
                    val close = argument.indexOf('}', i + 2)
                    if (close < 0) return null
                    close + 1
                } else {
                    var after = i + 2
                    while (argument.getOrNull(after)?.let { it == '_' || it.isLetterOrDigit() } == true) after++
                    after
                }
                append(SqlTemplate(listOf(entries.next())).sql, from until i)
            } else {
                append(argument[i].toString(), i..i)
                i++
            }
        }
        if (!closed || entries.hasNext()) return null
        // Do not accept a decoded literal that differs from the template before trimming.
        if (value.toString() != SqlTemplate(template.pieces).sql) return null
        val mapped = mutableListOf<IntRange>()
        var cursor = 0
        for (char in template.sql) {
            while (cursor < value.length && value[cursor] != char) cursor++
            if (cursor == value.length) return null
            mapped += ranges[cursor++]
        }
        return mapped[start].first..mapped[end - 1].last
    }
}
