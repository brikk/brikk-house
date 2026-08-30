package dev.brikk.house.sql.ast

import kotlin.String

/** Brikk-native AST nodes for Trino syntax not modeled by sqlglot. */
class AtLocal(initArgs: Args = emptyMap()) : Expression(initArgs) {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

class MatchPredicate(initArgs: Args = emptyMap()) : Expression(initArgs), Predicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf(
            "this" to false, "query" to true, "unique" to false, "match_type" to false,
        )
    }
}

class UniquePredicate(initArgs: Args = emptyMap()) : Expression(initArgs), SubqueryPredicate {
    override val argTypes get() = ARG_TYPES

    companion object {
        private val ARG_TYPES = argTypesOf("this" to true)
    }
}

internal fun registerNativeTrinoNodes(
    entries: kotlin.collections.MutableMap<String, ExpressionRegistry.Entry>,
) {
    val module = "brikk.trino"
    entries["AtLocal"] = ExpressionRegistry.Entry(module) { AtLocal() }
    entries["MatchPredicate"] = ExpressionRegistry.Entry(module) { MatchPredicate() }
    entries["UniquePredicate"] = ExpressionRegistry.Entry(module) { UniquePredicate() }
}
