package dev.brikk.house.sql.metadata

/*
 * HANDWRITTEN (not generated): function-shaped names StarRocks accepts at the
 * parser/operator level, so they're absent from the function registry that generates
 * GeneratedStarrocksFunctionCatalog.kt. tools/generate_starrocks_functions.py wires this
 * set in via `grammarBuiltins = STARROCKS_GRAMMAR_BUILTINS`.
 *
 * Inclusion rule (as for DORIS_GRAMMAR_BUILTINS): callable in StarRocks, NOT in the
 * registry dump, and actually parsed by brikk-sql (each has an AST node/operator) so
 * listing it clears a false "unknown" rather than masking a real parse failure. This set
 * starts from the shared MySQL-family grammar forms; the extraction step refines it
 * against the pinned 4.1.x source + live engine.
 */

/** StarRocks names parsed as grammar special forms / operators, absent from the registry. */
val STARROCKS_GRAMMAR_BUILTINS: Set<String> = setOf(
    "TIMESTAMPADD",   // TIMESTAMPADD(unit, n, dt) — unit is a keyword
    "TIMESTAMPDIFF",  // TIMESTAMPDIFF(unit, a, b) — unit is a keyword
    "MOD",            // modulo: operator `a MOD b` and MOD(a, b)
    "EXTRACT",        // EXTRACT(unit FROM x) — unit keyword, standard-SQL grammar form
    "CAST",           // CAST(x AS type) — grammar special form
    "CONVERT",        // CONVERT(x, type) — grammar special form
)
