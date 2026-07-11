package dev.brikk.house.sql.optimizer

import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Dot
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.parts
import dev.brikk.house.sql.dialects.Dialect
import dev.brikk.house.sql.dialects.Dialects

/**
 * Port of sqlglot/optimizer/normalize_identifiers.py.
 *
 * Normalize identifiers by converting them to either lower or upper case, ensuring
 * the semantics are preserved in each case (e.g. by respecting case-sensitivity).
 * A `case_sensitive` meta flag on a node makes this a no-op for its subtree.
 */
// sqlglot: normalize_identifiers.normalize_identifiers
fun <E : Expression> normalizeIdentifiers(
    expression: E,
    dialect: Dialect? = null,
    storeOriginalColumnIdentifiers: Boolean = false,
): E {
    val resolved = dialect ?: Dialects.BASE

    for (node in expression.walk(prune = { truthyMeta(it, "case_sensitive") })) {
        if (!truthyMeta(node, "case_sensitive")) {
            if (storeOriginalColumnIdentifiers && node is Column) {
                // TODO (Python): this does not handle non-column cases, e.g PARSE_JSON(...).key
                var parent: Expression = node
                while (parent.parent is Dot) {
                    parent = parent.parent as Dot
                }

                val parts = when (parent) {
                    is Column -> parent.parts
                    is Dot -> parent.parts
                    else -> emptyList()
                }
                node.meta["dot_parts"] = parts.map { it.name }
            }

            if (node is Identifier) {
                resolved.normalizeIdentifier(node)
            }
        }
    }

    return expression
}

// sqlglot: normalize_identifiers.normalize_identifiers (str overload)
fun normalizeIdentifiers(name: String, dialect: Dialect? = null): Identifier {
    val resolved = dialect ?: Dialects.BASE
    return normalizeIdentifiers(parseIdentifier(name, resolved), resolved)
}

// sqlglot: Expression.meta_get truthiness (bool(n.meta_get("case_sensitive")))
private fun truthyMeta(node: Expression, key: String): Boolean {
    val value = node.metaOrNull?.get(key) ?: return false
    return when (value) {
        false, "", 0, 0L -> false
        else -> true
    }
}
