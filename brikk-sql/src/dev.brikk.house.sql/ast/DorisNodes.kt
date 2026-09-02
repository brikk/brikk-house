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
 * `from_index` (`FROM base`) is only legal in `ALTER TABLE ... ADD ROLLUP` ([DorisAddRollup]).
 */
class DorisRollupIndex(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "expressions" to true, "duplicate_key" to false, "from_index" to false,
            "properties" to false,
        )
    }
}

/**
 * One field of a typed `VARIANT<...>` schema: `[MATCH_NAME | MATCH_NAME_GLOB] 'name':type`.
 * `this` is the quoted field name (a string [Literal]), `kind` the [DataType], `match` the
 * optional pattern-prefix keyword as a String.
 */
class DorisVariantField(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true, "kind" to true, "match" to false)
    }
}

/**
 * `REFRESH MATERIALIZED VIEW [db.]mv [AUTO | COMPLETE] [PARTITION[S] (p1, ...)]`,
 * `REFRESH CATALOG c [PROPERTIES (...)]`, `REFRESH DATABASE [c.]db`. `kind` is the keyword
 * text; `method` is a [Var] (AUTO / COMPLETE); `partitions` a list of identifiers.
 * `REFRESH TABLE t` stays on sqlglot's [Refresh].
 */
class DorisRefresh(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "kind" to true, "this" to true, "method" to false, "partitions" to false, "properties" to false,
        )
    }
}

/**
 * Parameters of a Doris `CREATE INDEX name ON t (cols) [USING kind] [PROPERTIES (...)]
 * [COMMENT '...']`, in [Index.params]. sqlglot's [IndexParameters] puts USING before the
 * column list and has no slot for PROPERTIES / COMMENT. `using` is a [Var].
 */
class DorisIndexParameters(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "columns" to true, "using" to false, "properties" to false, "comment" to false,
        )
    }
}

/**
 * `ALTER TABLE t ADD [TEMPORARY] PARTITION [IF NOT EXISTS] p VALUES ... [("k" = "v")]
 * [DISTRIBUTED BY ...] [PROPERTIES (...)]`. `this` is a [Partition] holding the
 * [PartitionRange] / [PartitionList] (plus the per-partition [Properties], as in CREATE
 * TABLE definition lists); `distributed_by` a [DistributedByProperty].
 */
class DorisAddPartition(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "exists" to false, "temporary" to false, "distributed_by" to false,
            "properties" to false,
        )
    }
}

/** `ALTER TABLE t DROP [TEMPORARY] PARTITION [IF EXISTS] p [FORCE] [FROM INDEX rollup]`. */
class DorisDropPartition(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to true, "exists" to false, "temporary" to false, "force" to false, "from_index" to false,
        )
    }
}

/**
 * `ALTER TABLE t REPLACE PARTITION (p1, ...) WITH TEMPORARY PARTITION (tp1, ...)
 * [FORCE] [PROPERTIES (...)]`. `expressions` / `temporary_partitions` are identifier lists.
 */
class DorisReplacePartition(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "expressions" to true, "temporary_partitions" to true, "properties" to false, "force" to false,
        )
    }
}

/**
 * `ALTER TABLE t MODIFY PARTITION p | (p1, ...) | (*) SET ("k" = "v", ...)`. `all` is true
 * for `(*)` (then `expressions` is empty); `properties` is a [Properties] node.
 */
class DorisModifyPartition(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to false, "all" to false, "properties" to true)
    }
}

/** `ALTER TABLE t RENAME PARTITION | ROLLUP old new` (`kind` is the keyword text). */
class DorisRename(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("kind" to true, "this" to true, "to" to true)
    }
}

/**
 * `ALTER TABLE t REPLACE WITH TABLE t2 [PROPERTIES (...)]` and
 * `ALTER MATERIALIZED VIEW mv REPLACE WITH MATERIALIZED VIEW mv2 [PROPERTIES (...)]`
 * (atomic swap). `kind` is `TABLE` or `MATERIALIZED VIEW`; `this` the other object.
 */
class DorisReplaceWith(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("kind" to true, "this" to true, "properties" to false)
    }
}

/** `ALTER TABLE t ADD ROLLUP r1 (cols) [...], r2 (cols) [...]` — `expressions` are [DorisRollupIndex]. */
class DorisAddRollup(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("expressions" to true)
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
    entries["DorisVariantField"] = ExpressionRegistry.Entry(module) { DorisVariantField() }
    entries["DorisRefresh"] = ExpressionRegistry.Entry(module) { DorisRefresh() }
    entries["DorisIndexParameters"] = ExpressionRegistry.Entry(module) { DorisIndexParameters() }
    entries["DorisAddPartition"] = ExpressionRegistry.Entry(module) { DorisAddPartition() }
    entries["DorisDropPartition"] = ExpressionRegistry.Entry(module) { DorisDropPartition() }
    entries["DorisReplacePartition"] = ExpressionRegistry.Entry(module) { DorisReplacePartition() }
    entries["DorisModifyPartition"] = ExpressionRegistry.Entry(module) { DorisModifyPartition() }
    entries["DorisRename"] = ExpressionRegistry.Entry(module) { DorisRename() }
    entries["DorisReplaceWith"] = ExpressionRegistry.Entry(module) { DorisReplaceWith() }
    entries["DorisAddRollup"] = ExpressionRegistry.Entry(module) { DorisAddRollup() }
}
