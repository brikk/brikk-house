// RUN: acceptance.TrinoKt
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkSql fun source(n: Int) = Sql.trino("SELECT 1 AS id, 'x' AS code WHERE $n > 0")
@BrikkSql fun keep(src: Rel<SourceOut>, minimum: Int) = Sql.trino("FROM $src() |> WHERE id > $minimum |> SELECT id, code")

fun main() {
    check(source(7).dialect == "trino")
    check(source(7).render() == "SELECT 1 AS id, 'x' AS code WHERE :n > 0")
    val composed = keep(source(7), 3)
    check(composed.bindings() == mapOf("n" to 7, "minimum" to 3))
    val rendered = composed.render()
    check("WITH s0 AS" in rendered && "id > :minimum" in rendered)
    check("src()" !in rendered && "|>" !in rendered)
}
