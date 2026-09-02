package dev.brikk.house.sql.ast

import kotlin.String

/**
 * Brikk-native AST nodes for Doris CREATE TABLE syntax not modeled by sqlglot
 * (docs/brikk-extensions.md #19). sqlglot's Doris dialect transpiles queries *into*
 * Doris and never parses `SHOW CREATE TABLE` output; these nodes cover what that output
 * contains and the inherited MySQL grammar rejects. Registered under module
 * "brikk.doris" (see [registerNativeDorisNodes]); tools/gen_ast_nodes.py scrapes this
 * file so a regen never generates over them.
 */

/** `AGGREGATE KEY (k1, k2)` — sibling of the sqlglot [DuplicateKeyProperty] / [UniqueKeyProperty]. */
class AggregateKeyProperty(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}

/**
 * Aggregate-key column aggregator suffix: `v BIGINT SUM`, `b BITMAP BITMAP_UNION`, ...
 * `this` is a [Var] holding the aggregator name (SUM | MAX | MIN | REPLACE |
 * REPLACE_IF_NOT_NULL | HLL_UNION | BITMAP_UNION | QUANTILE_UNION | GENERIC).
 */
class AggregateTypeColumnConstraint(initArgs: Args = emptyMap()) : Expression(initArgs), ColumnConstraintKind {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/**
 * `AUTO PARTITION BY RANGE (date_trunc(d, 'month')) ()` / `AUTO PARTITION BY LIST (c) ()`.
 * `this` is the wrapped [PartitionByRangeProperty] / [PartitionByListProperty].
 */
class AutoPartitionProperty(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

/**
 * `PROPERTIES ("parser" = "english", ...)` trailing an `INDEX ... USING INVERTED`
 * definition; sits in [IndexColumnConstraint]'s `options` list next to the MySQL
 * [IndexConstraintOption] entries. `expressions` are [Property] nodes.
 */
class IndexPropertiesOption(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
    }
}

/**
 * One entry of a Doris `ROLLUP (...)` clause: `name (cols) [DUPLICATE KEY (cols)]
 * [PROPERTIES (...)]` (DorisParser.g4 `rollupDef`). Distinct from sqlglot's StarRocks
 * [RollupIndex], whose grammar is `name (cols) [FROM base] [PROPERTIES (...)]`.
 * `duplicate_key` is a list of identifiers; `properties` is a [Properties] node.
 */
class DorisRollupIndex(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expressions" to true, "duplicate_key" to false, "properties" to false,
        )
    }
}

internal fun registerNativeDorisNodes(
    entries: kotlin.collections.MutableMap<String, ExpressionRegistry.Entry>,
) {
    val module = "brikk.doris"
    entries["AggregateKeyProperty"] = ExpressionRegistry.Entry(module) { AggregateKeyProperty() }
    entries["AggregateTypeColumnConstraint"] = ExpressionRegistry.Entry(module) { AggregateTypeColumnConstraint() }
    entries["AutoPartitionProperty"] = ExpressionRegistry.Entry(module) { AutoPartitionProperty() }
    entries["IndexPropertiesOption"] = ExpressionRegistry.Entry(module) { IndexPropertiesOption() }
    entries["DorisRollupIndex"] = ExpressionRegistry.Entry(module) { DorisRollupIndex() }
}
