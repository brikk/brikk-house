package dev.brikk.house.sql.metadata

/*
 * HANDWRITTEN (not generated): engine-GRAMMAR knowledge for DuckDB — names the
 * (Postgres-derived) parser accepts as function-shaped calls that duckdb_functions()
 * does NOT list (they become dedicated expression nodes at parse time, so they never
 * enter the registry that generates GeneratedDuckdbFunctionCatalog.kt).
 * tools/generate_duckdb_functions.py wires this set into the generated FunctionCatalog
 * constructor (grammarBuiltins = DUCKDB_GRAMMAR_BUILTINS).
 *
 * Verified against the pinned engine (v1.5.4, python duckdb module): each name below
 * parses as `NAME(args)` (grammar-accepted; GROUPING/GROUPING_ID fail only at BIND
 * time outside a grouped query) while `SELECT DISTINCT function_name FROM
 * duckdb_functions()` lacks it.
 *
 * Inclusion rule (deliberately minimal): only names brikk-sql generators can actually
 * fallback-emit. Grammar-level names that every generator renders through a dedicated
 * renderer (IF, TRY, CAST, TRY_CAST, EXTRACT) never reach the capability check and are
 * excluded; IFNULL is excluded because every dialect parses it to the Coalesce node,
 * which renders as COALESCE.
 */

/** DuckDB names parsed at the grammar level, absent from duckdb_functions(). */
val DUCKDB_GRAMMAR_BUILTINS: Set<String> = setOf(
    "COALESCE",     // parser special form (dedicated expression, not a registry call)
    "GROUPING",     // grammar-level grouping operation (binder-restricted to grouped queries)
    "GROUPING_ID",  // alias form of the grouping operation, same grammar path
)
