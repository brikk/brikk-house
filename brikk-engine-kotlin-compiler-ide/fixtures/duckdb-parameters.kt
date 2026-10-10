// RUN: acceptance.Duckdb_parametersKt
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkSql fun echo(n: Int) = Sql.duckdb("SELECT $n AS n")
@BrikkSql fun add(src: Rel<EchoOut>, n: Int) = Sql.duckdb("SELECT src.n + $n AS result FROM $src()")
@BrikkSql fun nullableEcho(n: Int?) = Sql.duckdb("SELECT $n AS n")

fun main() {
    check(echo(7).render() == "SELECT :n AS n")
    val composed = add(echo(2), 3)
    check(composed.bindings().values.toSet() == setOf(2, 3))
    check("n" !in composed.bindings().keys)
    check(composed.render().contains("SELECT :__brikk_bind_0_0 AS n"))
    check(nullableEcho(null).bindings() == mapOf("n" to null))
}
