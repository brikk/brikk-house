package dev.brikk.house.sql.metadata

/*
 * HANDWRITTEN (not generated): function-shaped names Doris accepts at the parser/operator
 * level, so they're absent from the Nereids function registry that generates
 * GeneratedDorisFunctionCatalog.kt (verified absent from vendor/data/doris-signatures.json).
 * generate_doris_functions.py wires this set in via `grammarBuiltins = DORIS_GRAMMAR_BUILTINS`.
 *
 * Inclusion rule (as for TRINO_GRAMMAR_BUILTINS): callable in Doris, NOT in the registry
 * dump, and actually parsed by brikk-sql (each has an AST node/operator) so listing it
 * clears a false "unknown" rather than masking a real parse failure.
 */

/** Doris names parsed as grammar special forms / operators, absent from the function registry. */
val DORIS_GRAMMAR_BUILTINS: Set<String> = setOf(
    "TIMESTAMPADD",   // TIMESTAMPADD(unit, n, dt) — unit is a keyword; rewritten to the *_ADD family
    "TIMESTAMPDIFF",  // TIMESTAMPDIFF(unit, a, b) — unit is a keyword; rewritten to the *_DIFF family
    "MOD",            // modulo: operator `a MOD b` and MySQL-compat MOD(a, b) (FMOD/PMOD are registered)
    "SYSDATE",        // MySQL-compat current-datetime special form
    "EXTRACT",        // EXTRACT(unit FROM x) — unit keyword, standard-SQL grammar form
    "CAST",           // CAST(x AS type) — grammar form
    "CONVERT",        // CONVERT(x, type) / CONVERT(x USING charset) — MySQL-compat grammar form
)
