package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.generator.Generator
import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * Apache DataFusion dialect — brikk-native, NO sqlglot oracle.
 *
 * sqlglot ships no DataFusion dialect, so this is not a Python-oracle port. It is a
 * thin, flag-tuned child of the BASE dialect modeled on polyglot's standalone
 * DataFusion dialect (reference/polyglot/crates/polyglot-sql/src/dialects/datafusion.rs)
 * cross-checked against the DataFusion SQL reference
 * (https://datafusion.apache.org/user-guide/sql/). DataFusion parses via sqlparser-rs
 * GenericDialect: double-quote identifiers, unquoted -> lowercase, `::` casts,
 * TRY_CAST/arrow_cast passthrough, QUALIFY, star EXCEPT/EXCLUDE, LEFT SEMI/ANTI,
 * aggregate FILTER, postgres regex ops, plural interval units, lowercase function
 * rendering, LIMIT/OFFSET, COPY TO (no INTO).
 *
 * Provenance style throughout this trio is `// brikk: ...` (there is no `// sqlglot:`
 * source to cite). Gates are fixture-/parse-acceptance based; an engine verifier is
 * deferred to phase 2 (see README + docs/brikk-extensions.md).
 *
 * Tokenizer delta verdict: NONE. BASE already supplies double-quote identifiers
 * (BaseTokenizerTables.IDENTIFIERS = {'"': '"'}) and nested comments
 * (BaseTokenizerTables.NESTED_COMMENTS = true) and the `|>` pipe operator token — the
 * only tokenizer characteristics polyglot's datafusion.rs customizes. So we use
 * TokenizerConfig.BASE unchanged; no DatafusionTokenizerTables is warranted.
 *
 * Normalization: BASE default is already LOWERCASE (Dialect.normalizationStrategy),
 * which matches DataFusion's unquoted -> lowercase folding; no override needed, but
 * we pin it explicitly for documentation.
 */
// brikk: no sqlglot oracle — flags per polyglot datafusion.rs + DataFusion SQL docs
open class DatafusionDialect : Dialect() {

    override val name: String get() = "datafusion"

    // brikk: DataFusion folds unquoted identifiers to lowercase (== BASE default).
    override val normalizationStrategy get() = NormalizationStrategy.LOWERCASE

    // brikk: tokenizer delta verdict is NONE — double-quote identifiers + nested
    // comments + `|>` are all already BASE-true. Use BASE unchanged.
    override val tokenizerConfig: TokenizerConfig get() = TokenizerConfig.BASE

    // brikk: no dialect parser hooks are required by the polyglot fixture corpus or
    // the curated SLT parse subset — the BASE parser accepts them all (`::` casts,
    // QUALIFY, star EXCEPT/EXCLUDE, semi/anti joins, aggregate FILTER, `|>` pipe,
    // TRY_CAST, arrow_cast as an anonymous func). DatafusionParser is therefore a
    // near-empty passthrough; see its KDoc for what was deliberately NOT added.
    override fun parser(errorLevel: ErrorLevel?): Parser =
        DatafusionParser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)

    override fun generator(pretty: Boolean): Generator =
        DatafusionGenerator(pretty = pretty, tokenizerConfig = tokenizerConfig)
}
