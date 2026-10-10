// EXPECT: [BRIKK_SQL] placeholder ':missing'
// EXPECT: [BRIKK_SQL] Column 'evnt_at' could not be resolved
// ERROR-AT: :missing
// ERROR-AT: evnt_at
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkSql
fun escaped(n: Int) = Sql.postgres("SELECT '😀' AS note, $n AS id, \n :missing AS x")

@BrikkSql
fun trimmed() = Sql.postgres("""
    |WITH q AS (SELECT 1 AS id)
    |SELECT evnt_at FROM q
""".trimMargin())
