package dev.brikk.house.sql.metadata

/*
 * HANDWRITTEN (not generated): engine-GRAMMAR knowledge for Trino, curated by reading
 * the pinned Trino 481 sources — it cannot be derived from the `SHOW FUNCTIONS` dump
 * that generates GeneratedTrinoFunctionCatalog.kt, precisely because these names are
 * absent from the registry. tools/generate_trino_functions.py wires this set into the
 * generated FunctionCatalog constructor (grammarBuiltins = TRINO_GRAMMAR_BUILTINS).
 *
 * Sources (verify on refresh, both in reference/trino at the 481 pin):
 *  - core/trino-grammar/src/main/antlr4/io/trino/grammar/sql/SqlBase.g4 — dedicated
 *    grammar alternatives (rule labels cited per name below).
 *  - core/trino-parser/src/main/java/io/trino/sql/parser/AstBuilder.java
 *    visitFunctionCall — parser special forms: they parse through the generic
 *    #functionCall alternative but are lifted into dedicated AST nodes by name before
 *    analysis, and are never registered in the function registry.
 *
 * Inclusion rule: function-shaped names an SQL text can legitimately call in Trino
 * that `SHOW FUNCTIONS` does NOT list. Names whose grammar rule exists but that ARE
 * registered at 481 (TRIM, SUBSTRING, NORMALIZE, LISTAGG, CURRENT_DATE, MAP) are
 * deliberately excluded — the registry catalog already clears them. ARRAY is excluded
 * too: its constructor is bracket syntax (`ARRAY[...]`, #arrayConstructor), so a
 * function-shaped `ARRAY(...)` call would NOT parse — listing it would mask a real
 * failure.
 */

/** Trino names parsed at the grammar/parser level, absent from `SHOW FUNCTIONS`. */
val TRINO_GRAMMAR_BUILTINS: Set<String> = setOf(
    // SqlBase.g4 primaryExpression alternatives:
    "POSITION",             // #position: POSITION '(' valueExpression IN valueExpression ')'
    "ROW",                  // #rowConstructor: ROW '(' fieldConstructor (',' ...)* ')'
    "EXISTS",               // #exists: EXISTS '(' query ')'
    "UNIQUE",               // #unique: UNIQUE '(' query ')'
    "CAST",                 // #cast: CAST '(' expression AS type ')'
    "TRY_CAST",             // #cast: TRY_CAST '(' expression AS type ')'
    "OVERLAY",              // #overlay: OVERLAY '(' ... PLACING ... FROM ... ')'
    "EXTRACT",              // #extract: EXTRACT '(' identifier FROM valueExpression ')'
    "GROUPING",             // #groupingOperation: GROUPING '(' (qualifiedName ...)? ')'
    "JSON_EXISTS",          // #jsonExists
    "JSON_VALUE",           // #jsonValue
    "JSON_QUERY",           // #jsonQuery
    "JSON_OBJECT",          // #jsonObject
    "JSON_ARRAY",           // #jsonArray
    // SqlBase.g4 primaryExpression niladic/precision forms (CURRENT_DATE itself IS
    // registered at 481, so it lives in the generated catalog, not here):
    "CURRENT_TIME",         // #currentTime
    "CURRENT_TIMESTAMP",    // #currentTimestamp
    "LOCALTIME",            // #localTime
    "LOCALTIMESTAMP",       // #localTimestamp
    "CURRENT_USER",         // #currentUser
    "CURRENT_CATALOG",      // #currentCatalog
    "CURRENT_SCHEMA",       // #currentSchema
    "CURRENT_PATH",         // #currentPath
    // SqlBase.g4 relationPrimary alternatives (function-shaped in table position):
    "UNNEST",               // #unnest: UNNEST '(' expression (',' expression)* ')'
    "JSON_TABLE",           // #jsonTable: JSON_TABLE '(' jsonPathInvocation COLUMNS ... ')'
    // AstBuilder.visitFunctionCall parser special forms (dedicated AST nodes by name):
    "IF",                   // -> IfExpression
    "NULLIF",               // -> NullIfExpression
    "COALESCE",             // -> CoalesceExpression
    "TRY",                  // -> TryExpression
    "FORMAT",               // -> Format
)
