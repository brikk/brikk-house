package dev.brikk.house.sql.ast

/*
 * Hand-written extensions on the GENERATED DType enum (ast/DataType.kt).
 *
 * These live here — NOT inside DataType.kt — so that regenerating the AST
 * (tools/gen_ast_nodes.py) never clobbers them. Anything that can be expressed as an
 * extension function belongs in this file (or any hand file), keeping the generated
 * files pure so `code-gen` needs zero manual re-patching.
 */

// sqlglot: datatypes.DType.into_expr — converts this DType into a DataType instance.
fun DType.intoExpr(kwargs: Args = emptyMap()): DataType {
    val dataType = DataType(args("this" to this))
    for ((k, v) in kwargs) dataType.set(k, v)
    return dataType
}
