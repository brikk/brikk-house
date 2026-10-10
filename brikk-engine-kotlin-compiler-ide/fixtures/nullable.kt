// EXPECT: Argument type mismatch
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkTrait interface Required : Partial { val id: Int }
@BrikkSql fun missing() = Sql.postgres("SELECT CAST(NULL AS INT) AS id")
fun require(src: Rel<Required>) = src
val bad = require(missing())
