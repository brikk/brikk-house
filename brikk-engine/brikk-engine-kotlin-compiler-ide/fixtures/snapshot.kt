// SNAPSHOT
// RUN: acceptance.SnapshotKt
package acceptance

import dev.brikk.house.sql.runtime.*

@BrikkSql
fun records() = Sql.doris("SELECT id FROM sample.analytics.records")

fun id(row: RecordsOut): Long = row.id

fun main() {
    check(RecordsOut::class.java.getMethod("getId").returnType == Long::class.javaPrimitiveType)
    check(records().render() == "SELECT id FROM sample.analytics.records")
    println("relocated JSON snapshot deserialization and schema-backed generation OK")
}
