// DRAFT
// DRAFT-EXPECT: NOT final or composed executable SQL
// DRAFT-EXPECT: -- Stages: 2
// DRAFT-EXPECT: -- Rel input slots: src
// DRAFT-EXPECT: SELECT /* keep */ 1 AS id WHERE :label <> ''
// DRAFT-EXPECT: FROM src() |> EXTEND :value AS extra
// DRAFT-ABSENT: RUNTIME_ONLY_SECRET_VALUE
// DRAFT-ABSENT: WITH s0
// RUN: acceptance.DraftKt
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkTrait interface HasId : Partial { val id: Int }
@BrikkSql fun source(label: String) = Sql.postgres("SELECT /* keep */ 1 AS id WHERE $label <> ''")
@BrikkSql fun <T: HasId> extend(src: Rel<T>, value: Int) = Sql.postgres("FROM $src() |> EXTEND $value AS extra")

fun main() {
    val rel = extend(source("RUNTIME_ONLY_SECRET_VALUE"), 7)
    check(rel.render().startsWith("WITH "))
    check(rel.bindings().values.toSet() == setOf("RUNTIME_ONLY_SECRET_VALUE", 7))
}
