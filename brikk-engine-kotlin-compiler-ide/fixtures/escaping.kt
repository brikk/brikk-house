// RUN: acceptance.EscapingKt
// WARN: local SQL shape escape
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkTrait interface HasId : Partial { val id: Int }
@BrikkSql fun source() = Sql.postgres("SELECT 1 AS id")
@BrikkSql fun <T: HasId> identity(src: Rel<T>) = Sql.postgres("FROM src()")
fun escaped() = identity(source())

fun main() { check(escaped().render().contains("SELECT 1 AS id")) }
