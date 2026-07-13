package dev.brikk.house.sql.dialects

import dev.brikk.house.sql.parser.ErrorLevel
import dev.brikk.house.sql.parser.Parser
import dev.brikk.house.sql.parser.TokenizerConfig

/**
 * DataFusion parser — brikk-native, NO sqlglot oracle. A near-empty passthrough over
 * the BASE [Parser].
 *
 * The polyglot fixture corpus and the curated DataFusion sqllogictest parse subset are
 * all accepted by the BASE grammar as-is, so no dialect hooks were added. Concretely,
 * the following are already BASE parser behaviors (verified via the fixture/SLT parse
 * gates, not a sqlglot oracle):
 *  - `::` cast operator and CAST/TRY_CAST
 *  - `arrow_cast(...)` / `arrow_typeof(...)` (parse as anonymous functions)
 *  - QUALIFY, SELECT * EXCEPT (...) and SELECT * EXCLUDE (...)
 *  - LEFT SEMI / LEFT ANTI joins
 *  - aggregate FILTER (WHERE ...)
 *  - the `|>` pipe operator (PIPE_GT -> PipeQuery)
 *  - postgres-style regex operators (~, ~*, !~, !~*)
 *  - plural interval units, LIMIT/OFFSET in either order, COPY ... TO
 *
 * DELIBERATELY NOT ADDED (out of scope for thin phase 1; would need an engine verifier
 * to justify): DataFusion Arrow-native type parsers (Int8/Int16/Utf8/... as first-class
 * DType surface), arrow_cast type-literal validation, DataFusion-specific function
 * signature parsing / a FunctionCatalog (phase 2), and any typing/EXPRESSION_METADATA
 * wiring (annotate falls back to BASE).
 */
// brikk: no sqlglot oracle — BASE parser accepts the datafusion fixture + SLT corpus
open class DatafusionParser(
    errorLevel: ErrorLevel? = null,
    tokenizerConfig: TokenizerConfig = TokenizerConfig.BASE,
) : Parser(errorLevel = errorLevel, tokenizerConfig = tokenizerConfig)
