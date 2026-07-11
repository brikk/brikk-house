package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Func
import dev.brikk.house.sql.ast.From
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Join
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.ast.Subquery
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.TableAlias
import dev.brikk.house.sql.ast.Values
import dev.brikk.house.sql.ast.With
import dev.brikk.house.sql.ast.nameSequence
import dev.brikk.house.sql.ast.selectStarFrom
import dev.brikk.house.sql.ast.toIdentifier
import dev.brikk.house.sql.ast.unpivot
import dev.brikk.house.sql.ast.unwrap
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

/**
 * Port of sqlglot/optimizer/qualify_tables.py.
 *
 * Rewrite the AST to have fully qualified tables. Join constructs such as
 * `(t1 JOIN t2) AS t` are expanded into `(SELECT * FROM t1 AS t1, t2 AS t2) AS t`.
 */
// sqlglot: qualify_tables.qualify_tables
fun <E : Expression> qualifyTables(
    expression: E,
    db: Any? = null,
    catalog: Any? = null,
    onQualify: ((Table) -> Unit)? = null,
    dialect: Dialect? = null,
    canonicalizeTableAliases: Boolean = false,
): E {
    val resolvedDialect = dialect ?: Dialects.BASE
    val nextAliasName = nameSequence("_")

    // sqlglot: `if db := db or None` (empty strings are falsy)
    val dbIdentifier: Identifier? = parseTablePart(db, resolvedDialect)
    val catalogIdentifier: Identifier? = parseTablePart(catalog, resolvedDialect)

    fun qualify(table: Table) {
        if (table.thisArg is Identifier) {
            if (dbIdentifier != null && table.args["db"] == null) {
                table.set("db", dbIdentifier.copy())
            }
            if (catalogIdentifier != null && table.args["catalog"] == null &&
                table.args["db"] != null
            ) {
                table.set("catalog", catalogIdentifier.copy())
            }
        }
    }

    if ((dbIdentifier != null || catalogIdentifier != null) && expression !is Query) {
        val with_ = expression.args["with_"] as? With
        val cteNames = with_?.expressionsArg
            ?.filterIsInstance<Expression>()
            ?.map { it.aliasOrName }
            ?.toSet()
            ?: emptySet()

        for (node in expression.walk(prune = { it is Query })) {
            if (node is Table && node.name !in cteNames) {
                qualify(node)
            }
        }
    }

    // sqlglot: qualify_tables._set_alias
    fun setAlias(
        expression: Expression,
        canonicalAliases: MutableMap<String, String>,
        targetAlias: String? = null,
        scope: Scope? = null,
        normalize: Boolean = false,
        columns: List<Any?>? = null,
    ) {
        val alias = expression.args["alias"] as? TableAlias ?: TableAlias()
        val target = targetAlias.takeUnless { it.isNullOrEmpty() }

        val newAliasName: String
        if (canonicalizeTableAliases) {
            newAliasName = nextAliasName()
            canonicalAliases[alias.name.ifEmpty { target ?: "" }] = newAliasName
        } else if (alias.name.isEmpty()) {
            var name = target ?: nextAliasName()
            if (normalize && target != null) {
                name = normalizeIdentifiers(name, dialect = resolvedDialect).name
            }
            newAliasName = name
        } else {
            return
        }

        alias.set("this", toIdentifier(newAliasName))

        if (!columns.isNullOrEmpty()) {
            alias.set(
                "columns",
                columns.map { if (it is String) toIdentifier(it) else (it as Expression).copy() },
            )
        }

        expression.set("alias", alias)

        scope?.renameSource(null, newAliasName)
    }

    for (scope in traverseScope(expression)) {
        val localColumns = scope.localColumns
        val canonicalAliases = mutableMapOf<String, String>()

        for (query in scope.subqueries) {
            val subquery = query.parent
            if (subquery is Subquery) {
                val unwrapped = subquery.unwrap()
                if (unwrapped.parent is Create && unwrapped !== subquery) {
                    // Function bodies may require wrapping parentheses, e.g. in BigQuery
                    // `... AS ((SELECT 1))` the outer parens delimit the body itself
                    unwrapped.set("this", subquery)
                } else {
                    unwrapped.replace(subquery)
                }
            }
        }

        for (derivedTable in scope.derivedTables) {
            val unnested = derivedTable.unnest()
            if (unnested is Table) {
                val joins = unnested.args["joins"]
                unnested.set("joins", null)
                (derivedTable.thisArg as Expression).replace(selectStarFrom(unnested.copy()))
                (derivedTable.thisArg as Expression).set("joins", joins)
            }

            setAlias(derivedTable, canonicalAliases, scope = scope)
            val pivot = (derivedTable.args["pivots"] as? List<*>)?.getOrNull(0) as? Expression
            if (pivot != null) {
                setAlias(pivot, canonicalAliases)
            }
        }

        val tableAliases = mutableMapOf<String, Expression>()

        for ((sourceName, source) in scope.sources.entries.toList()) {
            var name = sourceName
            if (source is Table) {
                // When the name is empty, it means that we have a non-table source,
                // e.g. a pivoted cte
                val isRealTableSource = name.isNotEmpty()

                val pivot = (source.args["pivots"] as? List<*>)?.getOrNull(0)
                    as? dev.brikk.house.sql.ast.Pivot
                if (pivot != null) {
                    name = source.name
                }

                val tableThis = source.thisArg
                val tableAlias = source.args["alias"] as? TableAlias
                var functionColumns: List<Any?>? = null
                if (tableThis is Func) {
                    val defaultColumns =
                        resolvedDialect.defaultFunctionsColumnNames[(tableThis as Expression)::class]
                    if (tableAlias == null) {
                        functionColumns = defaultColumns ?: emptyList()
                    } else if (tableAlias.columns.isNotEmpty()) {
                        functionColumns = tableAlias.columns
                    } else if (defaultColumns != null) {
                        functionColumns = listOf(source.aliasOrName)
                        source.set("alias", null)
                        name = ""
                    }
                }

                setAlias(
                    source,
                    canonicalAliases,
                    targetAlias = name.ifEmpty { source.name }.ifEmpty { null },
                    normalize = true,
                    columns = functionColumns,
                )

                val sourceFqn = source.parts.joinToString(".") { it.name }
                val hadExplicitAlias = tableAlias != null && tableAlias.name.isNotEmpty()
                if (!hadExplicitAlias || sourceFqn !in tableAliases) {
                    tableAliases[sourceFqn] =
                        ((source.args["alias"] as Expression).thisArg as Expression).copy()
                }

                if (pivot != null) {
                    val pivotTargetAlias = if (pivot.unpivot) source.alias else null
                    setAlias(
                        pivot,
                        canonicalAliases,
                        targetAlias = pivotTargetAlias,
                        normalize = true,
                    )

                    // This case corresponds to a pivoted CTE, we don't want to qualify that
                    if (scope.sources[source.aliasOrName] is Scope) {
                        continue
                    }
                }

                if (isRealTableSource) {
                    qualify(source)

                    onQualify?.invoke(source)
                }
            } else if (source is Scope && source.isUdtf) {
                val udtf = source.expression
                setAlias(udtf, canonicalAliases)

                val tableAlias = udtf.args["alias"] as TableAlias

                if (udtf is Values && tableAlias.columns.isEmpty()) {
                    val columnAliases = resolvedDialect.generateValuesAliases(udtf)
                        .map { normalizeIdentifiers(it, dialect = resolvedDialect) }
                    tableAlias.set("columns", columnAliases)
                }
            }
        }

        for (table in scope.tables) {
            if (table.alias.isEmpty() && (table.parent is From || table.parent is Join)) {
                setAlias(table, canonicalAliases, targetAlias = table.name)
            }
        }

        for (column in localColumns) {
            val columnTable = column.table

            if (column.db.isNotEmpty()) {
                val tableAlias = tableAliases[
                    column.parts.dropLast(1).joinToString(".") { it.name },
                ]

                if (tableAlias != null) {
                    // sqlglot: exp.COLUMN_PARTS[1:] = ("table", "db", "catalog")
                    for (part in listOf("table", "db", "catalog")) {
                        column.set(part, null)
                    }

                    column.set("table", tableAlias.copy())
                }
            } else if (canonicalAliases.isNotEmpty() && columnTable.isNotEmpty()) {
                val canonicalTable = canonicalAliases[columnTable] ?: ""
                if (canonicalTable != columnTable) {
                    // Amend existing aliases, e.g. t.c -> _0.c if t is aliased to _0
                    column.set("table", toIdentifier(canonicalTable))
                }
            }
        }
    }

    return expression
}

// sqlglot: parse + normalize of the db/catalog options in qualify_tables
private fun parseTablePart(part: Any?, dialect: Dialect): Identifier? {
    val identifier = when (part) {
        null, "" -> return null
        is Identifier -> part
        is String -> parseIdentifier(part, dialect)
        else -> throw IllegalArgumentException(
            "db/catalog must be a String or Identifier, got: ${part::class.simpleName}"
        )
    }
    identifier.meta["is_table"] = true
    return normalizeIdentifiers(identifier, dialect = dialect)
}
