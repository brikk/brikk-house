// EXPECT: [BRIKK_SQL] placeholder ':missing' does not match any parameter
// EXPECT: [BRIKK_SQL] dotted placeholder
// EXPECT: [BRIKK_SQL] Column 'hidden' could not be resolved
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkSql
fun unbound() = Sql.postgres("SELECT :missing AS id")

@BrikkSql
fun dotted(obj: Int) = Sql.postgres("SELECT :obj.field AS id")

@BrikkSql
fun scoped() = Sql.postgres("WITH q AS (SELECT 1 AS id) SELECT hidden FROM q")
