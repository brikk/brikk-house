package dev.brikk.house.sql.parser

/**
 * Immutable tokenizer configuration for one dialect.
 *
 * sqlglot: the class-level tables of tokens.Tokenizer (plus the dialect-level flags
 * that tokens.Tokenizer._init_core pulls from the Dialect). Dialects will provide
 * their own instances of this in later phases; defaults are the base "sqlglot"
 * dialect tables from [BaseTokenizerTables].
 */
class TokenizerConfig(
    val singleTokens: Map<Char, TokenType> = BaseTokenizerTables.SINGLE_TOKENS,
    val keywords: Map<String, TokenType> = BaseTokenizerTables.KEYWORDS,
    val quotes: Map<String, String> = BaseTokenizerTables.QUOTES,
    val formatStrings: Map<String, StringFormat> = BaseTokenizerTables.FORMAT_STRINGS,
    val identifiers: Map<Char, String> = BaseTokenizerTables.IDENTIFIERS,
    val comments: Map<String, String?> = BaseTokenizerTables.COMMENTS,
    val stringEscapes: Set<Char> = BaseTokenizerTables.STRING_ESCAPES,
    val byteStringEscapes: Set<Char> = BaseTokenizerTables.BYTE_STRING_ESCAPES,
    val identifierEscapes: Set<Char> = BaseTokenizerTables.IDENTIFIER_ESCAPES,
    val escapeFollowChars: Set<Char> = BaseTokenizerTables.ESCAPE_FOLLOW_CHARS,
    val commands: Set<TokenType> = BaseTokenizerTables.COMMANDS,
    val commandPrefixTokens: Set<TokenType> = BaseTokenizerTables.COMMAND_PREFIX_TOKENS,
    val nestedComments: Boolean = BaseTokenizerTables.NESTED_COMMENTS,
    val hintStart: String = BaseTokenizerTables.HINT_START,
    val tokensPrecedingHint: Set<TokenType> = BaseTokenizerTables.TOKENS_PRECEDING_HINT,
    val hasBitStrings: Boolean = false,
    val hasHexStrings: Boolean = false,
    val numericLiterals: Map<String, String> = BaseTokenizerTables.NUMERIC_LITERALS,
    val varSingleTokens: Set<Char> = BaseTokenizerTables.VAR_SINGLE_TOKENS,
    val stringEscapesAllowedInRawStrings: Boolean =
        BaseTokenizerTables.STRING_ESCAPES_ALLOWED_IN_RAW_STRINGS,
    val heredocTagIsIdentifier: Boolean = BaseTokenizerTables.HEREDOC_TAG_IS_IDENTIFIER,
    val heredocStringAlternative: TokenType = BaseTokenizerTables.HEREDOC_STRING_ALTERNATIVE,
    // sqlglot: Dialect.NUMBERS_CAN_BE_UNDERSCORE_SEPARATED (base default false)
    val numbersCanBeUnderscoreSeparated: Boolean = false,
    val numbersCanHaveDecimals: Boolean = BaseTokenizerTables.NUMBERS_CAN_HAVE_DECIMALS,
    // sqlglot: Dialect.IDENTIFIERS_CAN_START_WITH_DIGIT (base default false)
    val identifiersCanStartWithDigit: Boolean = false,
    // sqlglot: Dialect.UNESCAPED_SEQUENCES (base default empty)
    val unescapedSequences: Map<String, String> = emptyMap(),
) {
    /**
     * sqlglot: _TokenizerBase.__init_subclass__ _KEYWORD_TRIE — a trie over all
     * keyword/comment/quote/format-string starts that contain a space or any
     * single-token char (plain word keywords are resolved via [keywords] lookup
     * in Tokenizer.scanVar instead).
     */
    val keywordTrie: TrieNode = buildTrie(
        (keywords.keys.asSequence() +
            comments.keys.asSequence() +
            quotes.keys.asSequence() +
            formatStrings.keys.asSequence())
            .filter { key -> " " in key || key.any { it in singleTokens } }
            .map { it.uppercase() }
            .asIterable()
    )

    companion object {
        /** The base "sqlglot" dialect configuration. */
        val BASE: TokenizerConfig = TokenizerConfig()
    }
}
