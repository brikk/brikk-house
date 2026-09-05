package dev.brikk.house.sql.parser

import dev.brikk.house.sql.dialects.Dialects

/**
 * Dialect-name -> [TokenizerConfig] lookup.
 *
 * Delegates to the [Dialects] registry so there is exactly one list of accepted names.
 * Unknown names throw [dev.brikk.house.sql.dialects.UnknownDialectException] — they used
 * to fall back to [TokenizerConfig.BASE], which let typos and non-ported dialects
 * (`snowflake`, `postgress`, ...) tokenize silently under the wrong rules (EVAL-07).
 */
object TokenizerConfigs {
    fun forName(dialect: String): TokenizerConfig = Dialects.forName(dialect).tokenizerConfig

    fun forNameOrNull(dialect: String): TokenizerConfig? = Dialects.forNameOrNull(dialect)?.tokenizerConfig
}
