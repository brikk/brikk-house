package dev.brikk.house.sql.parser

/**
 * Hand-written, single-pass, table-driven SQL tokenizer.
 *
 * sqlglot: tokenizer_core.TokenizerCore — a faithful port. Method-level provenance
 * comments reference the Python counterparts (pinned to v30.12.0-44-g93d16591).
 *
 * Not thread-safe; create one instance per thread or per use (construction is cheap,
 * the config tables are shared immutable objects).
 */
class Tokenizer(private val config: TokenizerConfig = TokenizerConfig.BASE) {

    private var sql: String = ""
    private var size: Int = 0
    private var tokens: MutableList<Token> = mutableListOf()
    private var start: Int = 0
    private var current: Int = 0
    private var line: Int = 1
    private var col: Int = 0
    private var comments: MutableList<String> = mutableListOf()
    private var char: Char = NONE
    private var end: Boolean = false
    private var peek: Char = NONE
    private var prevTokenLine: Int = -1

    // sqlglot: TokenizerCore.reset
    private fun reset() {
        sql = ""
        size = 0
        tokens = mutableListOf()
        start = 0
        current = 0
        line = 1
        col = 0
        comments = mutableListOf()
        char = NONE
        end = false
        peek = NONE
        prevTokenLine = -1
    }

    /**
     * Returns the list of tokens corresponding to [sql].
     *
     * sqlglot: TokenizerCore.tokenize
     */
    fun tokenize(sql: String): List<Token> {
        reset()
        this.sql = sql
        this.size = sql.length

        try {
            scan()
        } catch (e: TokenError) {
            throw e
        } catch (e: Exception) {
            val ctxStart = maxOf(current - 50, 0)
            val ctxEnd = minOf(current + 50, size - 1).coerceAtLeast(ctxStart)
            val context = this.sql.substring(ctxStart, ctxEnd)
            throw TokenError("Error tokenizing '$context'", e)
        }

        return tokens
    }

    // sqlglot: TokenizerCore._scan
    private fun scan(checkSemicolon: Boolean = false) {
        val identifiers = config.identifiers

        while (size != 0 && !end) {
            var cur = current

            // Skip spaces here rather than iteratively calling advance() for performance reasons
            while (cur < size) {
                val c = sql[cur]
                if (c == ' ' || c == '\t') cur++ else break
            }

            val offset = if (cur > current) cur - current else 1

            start = cur
            advance(offset)

            if (char != NONE && !char.isWhitespace()) {
                when {
                    char in DIGIT_CHARS -> scanNumber()
                    identifiers.containsKey(char) -> scanIdentifier(identifiers.getValue(char))
                    else -> scanKeywords()
                }
            }

            if (checkSemicolon && peek == ';') break
        }

        if (tokens.isNotEmpty() && comments.isNotEmpty()) {
            tokens.last().comments.addAll(comments)
            comments = mutableListOf()
        }
    }

    // sqlglot: TokenizerCore._chars
    private fun chars(size: Int): String {
        if (size == 1) return char.toString()
        val s = current - 1
        val e = s + size
        return if (e <= this.size) sql.substring(s, e) else ""
    }

    // sqlglot: TokenizerCore._advance
    private fun advance(i: Int = 1, alnum: Boolean = false) {
        val c = char

        if (c == '\n' || c == '\r') {
            // Ensures we don't count an extra line if we get a \r\n line break sequence
            if (!(c == '\r' && peek == '\n')) {
                col = i
                line += 1
            }
        } else {
            col += i
        }

        current += i
        end = current >= size
        char = if (current - 1 in 0 until size) sql[current - 1] else NONE
        peek = if (end) NONE else sql[current]

        if (alnum && char.isLetterOrDigit()) {
            // Batch consecutive alphanumeric chars
            var c2 = col
            var cur = current
            var e2 = end
            var p2 = peek

            while (p2.isLetterOrDigit()) {
                c2 += 1
                cur += 1
                e2 = cur >= size
                p2 = if (e2) NONE else sql[cur]
            }

            col = c2
            current = cur
            end = e2
            peek = p2
            char = sql[cur - 1]
        }
    }

    // sqlglot: TokenizerCore._text
    private val text: String
        get() = sql.substring(start, current)

    // sqlglot: TokenizerCore._add
    private fun add(tokenType: TokenType, text: String? = null) {
        prevTokenLine = line

        if (comments.isNotEmpty() && tokenType == TokenType.SEMICOLON && tokens.isNotEmpty()) {
            tokens.last().comments.addAll(comments)
            comments = mutableListOf()
        }

        val tokenText = text ?: sql.substring(start, current)

        tokens.add(
            Token(
                tokenType,
                text = tokenText,
                line = line,
                col = col,
                start = start,
                end = current - 1,
                comments = comments,
            )
        )
        comments = mutableListOf()

        // If we have either a semicolon or a begin token before the command's token, we'll parse
        // whatever follows the command's token as a string
        if (
            tokenType in config.commands &&
            peek != ';' &&
            (tokens.size == 1 || tokens[tokens.size - 2].tokenType in config.commandPrefixTokens)
        ) {
            val commandStart = current
            val tokenCount = tokens.size
            scan(checkSemicolon = true)
            while (tokens.size > tokenCount) tokens.removeAt(tokens.size - 1)
            val commandText = sql.substring(commandStart, current).trim()
            if (commandText.isNotEmpty()) add(TokenType.STRING, commandText)
        }
    }

    // sqlglot: TokenizerCore._scan_keywords
    private fun scanKeywords() {
        val singleTokens = config.singleTokens
        var matchSize = 0
        var word: String? = null
        var chars = StringBuilder().append(char)
        var c = char
        var cEmpty = false
        var prevSpace = false
        var skip = false
        var trie = config.keywordTrie
        var singleToken = singleTokens.containsKey(c)

        while (true) {
            if (!skip) {
                val sub = trie.children[asciiUpper(c)] ?: break
                trie = sub
                if (trie.isWord) word = chars.toString()
            }

            val endPos = current + matchSize
            matchSize += 1

            if (endPos < size) {
                c = sql[endPos]
                singleToken = singleToken || singleTokens.containsKey(c)
                val isSpace = c.isWhitespace()

                if (!isSpace || !prevSpace) {
                    if (isSpace) c = ' '
                    chars.append(c)
                    prevSpace = isSpace
                    skip = false
                } else {
                    skip = true
                }
            } else {
                cEmpty = true
                break
            }
        }

        if (word != null) {
            if (scanString(word)) return
            if (scanComment(word)) return
            if (prevSpace || singleToken || cEmpty) {
                advance(matchSize - 1)
                val upper = word.uppercase()
                add(config.keywords.getValue(upper), text = upper)
                return
            }
        }

        val single = singleTokens[char]
        if (single != null) {
            add(single, text = char.toString())
            return
        }

        scanVar()
    }

    // sqlglot: TokenizerCore._scan_comment
    private fun scanComment(commentStart: String): Boolean {
        if (!config.comments.containsKey(commentStart)) return false

        val commentStartLine = line
        val commentStartSize = commentStart.length
        val commentEnd = config.comments[commentStart]

        if (commentEnd != null) {
            // Skip the comment's start delimiter
            advance(commentStartSize)

            var commentCount = 1
            val commentEndSize = commentEnd.length
            val nestedComments = config.nestedComments

            while (!end) {
                if (chars(commentEndSize) == commentEnd) {
                    commentCount -= 1
                    if (commentCount == 0) break
                }

                advance(alnum = true)

                // Nested comments are allowed by some dialects, e.g. databricks, duckdb, postgres
                if (nestedComments && !end && chars(commentEndSize) == commentStart) {
                    advance(commentStartSize)
                    commentCount += 1
                }
            }

            val t = text
            val sliceEnd = t.length - commentEndSize + 1
            comments.add(if (sliceEnd < commentStartSize) "" else t.substring(commentStartSize, sliceEnd))
            advance(commentEndSize - 1)
        } else {
            while (!end && peek != '\n' && peek != '\r') {
                advance(alnum = true)
            }
            comments.add(text.substring(commentStartSize))
        }

        if (
            commentStart == config.hintStart &&
            tokens.isNotEmpty() &&
            tokens.last().tokenType in config.tokensPrecedingHint
        ) {
            add(TokenType.HINT)
        }

        // Leading comment is attached to the succeeding token, whilst trailing comment to the
        // preceding. Multiple consecutive comments are preserved by appending them to the current
        // comments list.
        if (commentStartLine == prevTokenLine) {
            tokens.last().comments.addAll(comments)
            comments = mutableListOf()
            prevTokenLine = line
        }

        return true
    }

    // sqlglot: TokenizerCore._scan_number
    private fun scanNumber() {
        if (char == '0') {
            when (asciiUpper(peek)) {
                'B' -> return if (config.hasBitStrings) scanBits() else add(TokenType.NUMBER)
                'X' -> return if (config.hasHexStrings) scanHex() else add(TokenType.NUMBER)
            }
        }

        var decimal = false
        var scientific = 0
        val singleTokens = config.singleTokens

        var isUnderscoreSeparated = false
        var numberText = ""
        var numericLiteral = ""
        var numericType: TokenType? = null

        while (true) {
            if (peek in DIGIT_CHARS) {
                // Batch consecutive digits: scan ahead to find how many
                var e = current + 1
                while (e < size && sql[e] in DIGIT_CHARS) e++
                advance(e - current)
            } else if (peek == '.' && !decimal) {
                if (
                    (tokens.isNotEmpty() && tokens.last().tokenType == TokenType.PARAMETER) ||
                    !config.numbersCanHaveDecimals
                ) {
                    break
                }
                decimal = true
                advance()
            } else if ((peek == '-' || peek == '+') && scientific == 1) {
                // Only consume +/- if followed by a digit
                if (current + 1 < size && sql[current + 1] in DIGIT_CHARS) {
                    scientific += 1
                    advance()
                } else {
                    break
                }
            } else if (asciiUpper(peek) == 'E' && scientific == 0) {
                scientific += 1
                advance()
            } else if (peek == '_' && config.numbersCanBeUnderscoreSeparated) {
                isUnderscoreSeparated = true
                advance()
            } else if (peek.isLetter() || peek == '_') {
                numberText = text

                while (peek != NONE && !peek.isWhitespace() && !singleTokens.containsKey(peek)) {
                    numericLiteral += peek
                    advance()
                }

                numericType = config.numericLiterals[numericLiteral.uppercase()]
                    ?.let { config.keywords[it] }

                if (numericType != null) {
                    break
                } else if (config.identifiersCanStartWithDigit) {
                    add(TokenType.VAR)
                    return
                }

                advance(-numericLiteral.length)
                break
            } else {
                break
            }
        }

        var finalText = numberText.ifEmpty { sql.substring(start, current) }

        // Normalize inputs such as 100_000 to 100000
        if (isUnderscoreSeparated) finalText = finalText.replace("_", "")

        add(TokenType.NUMBER, finalText)

        // Normalize inputs such as 123L to 123::BIGINT so that they're parsed as casts
        if (numericType != null) {
            add(TokenType.DCOLON, "::")
            add(numericType, numericLiteral)
        }
    }

    // sqlglot: TokenizerCore._scan_bits
    private fun scanBits() {
        advance()
        val value = extractValue()
        // If `value` can't be converted to a binary, fallback to tokenizing it as an identifier
        val digits = value.drop(2)
        if (digits.isNotEmpty() && digits.all { it == '0' || it == '1' }) {
            add(TokenType.BIT_STRING, digits) // Drop the 0b
        } else {
            add(TokenType.IDENTIFIER)
        }
    }

    // sqlglot: TokenizerCore._scan_hex
    private fun scanHex() {
        advance()
        val value = extractValue()
        // If `value` can't be converted to a hex, fallback to tokenizing it as an identifier
        val digits = value.drop(2)
        if (digits.isNotEmpty() && digits.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            add(TokenType.HEX_STRING, digits) // Drop the 0x
        } else {
            add(TokenType.IDENTIFIER)
        }
    }

    // sqlglot: TokenizerCore._extract_value
    private fun extractValue(): String {
        while (true) {
            val c = peek
            if (c != NONE && !c.isWhitespace() && !config.singleTokens.containsKey(c)) {
                advance(alnum = true)
            } else {
                break
            }
        }
        return text
    }

    // sqlglot: TokenizerCore._scan_string
    private fun scanString(startDelim: String): Boolean {
        var base = 0
        var tokenType = TokenType.STRING
        var endDelim: String

        val quoteEnd = config.quotes[startDelim]
        val format = config.formatStrings[startDelim]

        if (quoteEnd != null) {
            endDelim = quoteEnd
        } else if (format != null) {
            endDelim = format.end
            tokenType = format.tokenType

            if (tokenType == TokenType.HEX_STRING) {
                base = 16
            } else if (tokenType == TokenType.BIT_STRING) {
                base = 2
            } else if (tokenType == TokenType.HEREDOC_STRING) {
                advance()

                val tag = if (endDelim.length == 1 && char == endDelim[0]) {
                    ""
                } else {
                    extractString(
                        endDelim,
                        rawString = true,
                        raiseUnmatched = !config.heredocTagIsIdentifier,
                    )
                }

                if (
                    tag.isNotEmpty() &&
                    config.heredocTagIsIdentifier &&
                    (end || tag.all { it.isDigit() } || tag.any { it.isWhitespace() })
                ) {
                    if (!end) advance(-1)
                    advance(-tag.length)
                    add(config.heredocStringAlternative)
                    return true
                }

                endDelim = "$startDelim$tag$endDelim"
            }
        } else {
            return false
        }

        advance(startDelim.length)
        val text = extractString(
            endDelim,
            escapes = if (tokenType == TokenType.BYTE_STRING) {
                config.byteStringEscapes
            } else {
                config.stringEscapes
            },
            rawString = tokenType == TokenType.RAW_STRING,
        )

        if (base != 0 && text.isNotEmpty()) {
            val valid = when (base) {
                2 -> text.all { it == '0' || it == '1' }
                16 -> text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                else -> true
            }
            if (!valid) {
                throw TokenError("Numeric string contains invalid characters from $line:$start")
            }
        }

        add(tokenType, text)
        return true
    }

    // sqlglot: TokenizerCore._scan_identifier
    private fun scanIdentifier(identifierEnd: String) {
        advance()
        val escapes = if (identifierEnd.length == 1) {
            config.identifierEscapes + identifierEnd[0]
        } else {
            config.identifierEscapes
        }
        val text = extractString(identifierEnd, escapes = escapes)
        add(TokenType.IDENTIFIER, text)
    }

    // sqlglot: TokenizerCore._scan_var
    private fun scanVar() {
        val varSingleTokens = config.varSingleTokens
        val singleTokens = config.singleTokens

        while (true) {
            val p = peek
            if (p == NONE || p.isWhitespace()) break
            if (p !in varSingleTokens && singleTokens.containsKey(p)) break
            advance(alnum = true)
        }

        val tokenType = if (tokens.isNotEmpty() && tokens.last().tokenType == TokenType.PARAMETER) {
            TokenType.VAR
        } else {
            config.keywords[sql.substring(start, current).uppercase()] ?: TokenType.VAR
        }
        add(tokenType)
    }

    // sqlglot: TokenizerCore._extract_string
    private fun extractString(
        delimiter: String,
        escapes: Set<Char>? = null,
        rawString: Boolean = false,
        raiseUnmatched: Boolean = true,
    ): String {
        var text = StringBuilder()
        val delimSize = delimiter.length
        val esc = escapes ?: config.stringEscapes
        val unescapedSequences = config.unescapedSequences
        val escapeFollowChars = config.escapeFollowChars
        val quotes = config.quotes

        // Fast path via indexOf when the string is simple... no \ or other escapes
        if (delimSize == 1) {
            val delimChar = delimiter[0]
            val pos = current - 1
            val endIdx = sql.indexOf(delimChar, pos)

            if (
                // the closing delimiter was found
                endIdx != -1 &&
                // there's no doubled delimiter (e.g. '' escape), or the delimiter isn't an escape
                (endIdx + 1 >= size || sql[endIdx + 1] != delimChar || delimChar !in esc) &&
                // no backslash in the string that would need escape processing
                (
                    !(unescapedSequences.isNotEmpty() || '\\' in esc) ||
                        sql.indexOf('\\', pos).let { it == -1 || it >= endIdx }
                    )
            ) {
                var newlines = 0
                for (i in pos until endIdx) if (sql[i] == '\n') newlines++
                if (newlines > 0) {
                    line += newlines
                    col = endIdx - sql.lastIndexOf('\n', endIdx - 1)
                } else {
                    col += endIdx - pos
                }

                current = endIdx + 1
                end = current >= size
                char = sql[endIdx]
                peek = if (end) NONE else sql[current]
                return sql.substring(pos, endIdx)
            }
        }

        while (true) {
            if (!rawString && unescapedSequences.isNotEmpty() && peek != NONE && char in esc) {
                val unescaped = unescapedSequences["$char$peek"]
                if (unescaped != null) {
                    advance(2)
                    text.append(unescaped)
                    continue
                }
            }

            val isValidCustomEscape =
                escapeFollowChars.isNotEmpty() && char == '\\' && peek !in escapeFollowChars

            if (
                (config.stringEscapesAllowedInRawStrings || !rawString) &&
                char in esc &&
                (
                    (delimSize == 1 && peek == delimiter[0]) ||
                        peek in esc ||
                        isValidCustomEscape
                    ) &&
                (!quotes.containsKey(char.toString()) || char == peek)
            ) {
                if (delimSize == 1 && peek == delimiter[0]) {
                    text.append(peek)
                } else if (isValidCustomEscape && char != peek) {
                    text.append(peek)
                } else {
                    text.append(char).append(peek)
                }

                if (current + 1 < size) {
                    advance(2)
                } else {
                    throw TokenError("Missing $delimiter from $line:$current")
                }
            } else {
                if (chars(delimSize) == delimiter) {
                    if (delimSize > 1) advance(delimSize - 1)
                    break
                }

                if (end) {
                    if (!raiseUnmatched) return text.append(char).toString()
                    throw TokenError("Missing $delimiter from $line:$start")
                }

                val cur = current - 1
                advance(alnum = true)
                text.append(sql, cur, current - 1)
            }
        }

        return text.toString()
    }

    private companion object {
        /** Stand-in for Python's empty-string "no char" sentinel. */
        const val NONE: Char = '\u0000'

        // sqlglot: tokenizer_core._DIGIT_CHARS
        val DIGIT_CHARS: CharRange = '0'..'9'

        // sqlglot: tokenizer_core._CHAR_UPPER (ASCII-only uppercase, a-z)
        fun asciiUpper(c: Char): Char = if (c in 'a'..'z') c - 32 else c
    }
}
