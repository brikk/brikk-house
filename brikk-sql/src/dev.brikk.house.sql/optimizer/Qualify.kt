package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

// Import aliases: the rule functions share names with qualify()'s Boolean options
// (which mirror Python's keyword arguments).
import dev.brikk.house.sql.optimizer.isolateTableSelects as isolateTableSelectsRule
import dev.brikk.house.sql.optimizer.qualifyColumns as qualifyColumnsRule
import dev.brikk.house.sql.optimizer.quoteIdentifiers as quoteIdentifiersRule
import dev.brikk.house.sql.optimizer.validateQualifyColumns as validateQualifyColumnsRule

/**
 * Port of sqlglot/optimizer/qualify.py — rewrite the AST to have normalized and
 * qualified tables and columns. This step is necessary for all further optimizations.
 *
 * Example:
 *   schema = {"tbl": {"col": "INT"}}
 *   qualify(parseOne("SELECT col FROM tbl"), schema = schema).sql()
 *   => SELECT "tbl"."col" AS "col" FROM "tbl" AS "tbl"
 */
// sqlglot: qualify.qualify (same defaults as Python)
fun <E : Expression> qualify(
    expression: E,
    dialect: Dialect? = null,
    db: Any? = null,
    catalog: Any? = null,
    schema: Any? = null,
    expandAliasRefs: Boolean = true,
    expandStars: Boolean = true,
    inferSchema: Boolean? = null,
    isolateTables: Boolean = false,
    qualifyColumns: Boolean = true,
    allowPartialQualification: Boolean = false,
    validateQualifyColumns: Boolean = true,
    quoteIdentifiers: Boolean = true,
    identify: Boolean = true,
    canonicalizeTableAliases: Boolean = false,
    onQualify: ((Table) -> Unit)? = null,
    sql: String? = null,
): E {
    val resolvedSchema = ensureSchema(schema, dialect = dialect)
    val resolvedDialect = dialect ?: Dialects.BASE

    var result: E = normalizeIdentifiers(
        expression,
        dialect = resolvedDialect,
        storeOriginalColumnIdentifiers = true,
    )
    result = qualifyTables(
        result,
        db = db,
        catalog = catalog,
        dialect = resolvedDialect,
        onQualify = onQualify,
        canonicalizeTableAliases = canonicalizeTableAliases,
    )

    if (isolateTables) {
        result = isolateTableSelectsRule(result, schema = resolvedSchema)
    }

    if (qualifyColumns) {
        result = qualifyColumnsRule(
            result,
            resolvedSchema,
            expandAliasRefs = expandAliasRefs,
            expandStars = expandStars,
            inferSchema = inferSchema,
            allowPartialQualification = allowPartialQualification,
        )
    }

    if (quoteIdentifiers) {
        result = quoteIdentifiersRule(result, dialect = resolvedDialect, identify = identify)
    }

    if (validateQualifyColumns) {
        validateQualifyColumnsRule(result, sql = sql)
    }

    return result
}
